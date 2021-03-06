package com.googlecode.richrest.server;

import java.io.IOException;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.googlecode.richrest.Action;
import com.googlecode.richrest.server.mapper.DefaultActionMapper;
import com.googlecode.richrest.server.provider.SpringActionProvider;
import com.googlecode.richrest.util.BeanUtils;
import com.googlecode.richrest.util.ClassUtils;
import com.googlecode.richrest.util.ExceptionUtils;
import com.googlecode.richrest.util.logger.Logger;
import com.googlecode.richrest.util.logger.LoggerFactory;

/**
 * Action请求Servlet接收器.
 * <p>
 * web.xml配置：
 * <pre>
 * &lt;servlet&gt;
 *     &lt;servlet-name&gt;actionServlet&lt;/servlet-name&gt;
 *     &lt;servlet-class&gt;com.googlecode.richrest.server.ActionServlet&lt;/servlet-class&gt;
 *     &lt;init-param&gt;
 *         &lt;param-name&gt;actionProvider&lt;/param-name&gt;
 *         &lt;param-value&gt;
 *             com.googlecode.richrest.server.factory.SpringActionProvider
 *         &lt;/param-value&gt;
 *     &lt;/init-param&gt;
 *     &lt;init-param&gt;
 *         &lt;param-name&gt;actionMapper&lt;/param-name&gt;
 *         &lt;param-value&gt;
 *             com.googlecode.richrest.server.mapper.DefaultActionMapper
 *         &lt;/param-value&gt;
 *     &lt;/init-param&gt;
 *     &lt;load-on-startup&gt;1&lt;/load-on-startup&gt;
 * &lt;/servlet&gt;
 * &lt;servlet-mapping&gt;
 *     &lt;servlet-name&gt;actionServlet&lt;/servlet-name&gt;
 *     &lt;url-pattern&gt;*.data&lt;/url-pattern&gt;
 * &lt;/servlet-mapping&gt;
 * </pre>
 * @author <a href="mailto:liangfei0201@gmail.com">liangfei</a>
 */
public class ActionServlet extends HttpServlet {

	private static final long serialVersionUID = 5273076711496326339L;

	/**
	 * 日志输出接口
	 */
	protected final Logger logger = LoggerFactory.getLogger(getClass());

	protected ActionServletContext actionServletContext;

	// 初始化
	@Override
	public void init() throws ServletException {
		super.init();
		actionServletContext = new ActionServletContext(getServletContext(), getServletConfig());
		ActionServletContext.init(actionServletContext); // 初始化上下文
		try {
			// 加载Action工厂
			ActionProvider actionProvider = getActionProvider();
			if (actionProvider == null)// 缺省使用SpringActionProvider
				actionProvider = new SpringActionProvider();
			ActionServletContext.getContext().setActionProvider(actionProvider);
			actionInterceptor = reduceActionInterceptorChain(actionProvider.getActionInterceptors());

			// 加载Action接收器
			ActionMapper actionMapper = getActionMapper();
			if (actionMapper == null) // 缺省使用ActionMapper
				actionMapper = new DefaultActionMapper();
			ActionServletContext.getContext().setActionMapper(actionMapper);
		} catch (ServletException e) {
			logger.error(e.getMessage(), e);
			throw e;
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new ServletException(e.getMessage(), e);
		} finally {
			ActionServletContext.destroy();
		}
	}

	// 销毁
	@Override
	public void destroy() {
		super.destroy();
	}

	// 请求适配
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		doPost(request, response);
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		doPost(request, response);
	}

	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		doPost(request, response);
	}

	@Override
	protected void doHead(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		doPost(request, response);
	}

	// 请求处理
	@SuppressWarnings("unchecked")
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		ActionServletContext.init(actionServletContext); // 初始化上下文
		try {
			ServletSerializer serializer = ActionServletContext.getContext().getActionMapper().getSerializer(request);
			response.setContentType(serializer.getContentType());

			String actionName = ActionServletContext.getContext().getActionMapper().getActionName(request); // 获取Action名称
			if (actionName == null) // action名称不允许为空
				throw new NullPointerException("Can not resolve action name by request: " + request.getRequestURI());

			Action<Serializable, Serializable> action = ActionServletContext.getContext().getActionProvider().getAction(actionName); // 获取Action实例
			if (action == null) // action实例不允许为空
				throw new NullPointerException("Not existed action: " + actionName);
			ActionDelegate delegate = new ActionDelegate(actionInterceptor, action);
			Class<? extends Serializable> modelClass = getModelClass(action);

			ActionContext.init(request, response, actionName, action); // 初始化上下文
			try {
				// 接收客户端传过来的对象
				Serializable model = serializer.deserialize(modelClass, request);
				Serializable result;
				try {
					result = doExceute(actionName, delegate, model);
				} catch (ServletForwardException e) {
					throw e;
				} catch (Exception e) { // 从Action中抛出的业务性异常，直接序列化到客户端
					result = e;
				}
				serializer.serialize(result, response);
			} finally {
				ActionContext.destroy(); // 销毁上下文
			}
		} catch (ServletForwardException e) {
			Serializable forwardModel = e.getModel();
			request.setAttribute("model", forwardModel);
			if (forwardModel instanceof Map) {
				Map map = (Map)forwardModel;
				for (Iterator iterator = map.entrySet().iterator(); iterator.hasNext();) {
					Map.Entry entry = (Map.Entry)iterator.next();
					request.setAttribute(String.valueOf(entry.getKey()), entry.getValue());
				}
			} else {
				Map<String, Object> map = BeanUtils.getProperties(forwardModel);
				for (Map.Entry<String, Object> entry : map.entrySet()) {
					request.setAttribute(entry.getKey(), entry.getValue());
				}
			}
			request.getRequestDispatcher(e.getTarget()).forward(request, response);
			return;
		} catch (Throwable e) { // 服务器错误
			logger.error(e.getMessage(), e);
			String msg = ExceptionUtils.getDetailMessage(e);
			response.sendError(500, msg); // 不能序列化则发送HTTP错误信息报头
		} finally {
			ActionServletContext.destroy();
		}
	}

	protected Serializable doExceute(String actionName, Action<Serializable, Serializable> action, Serializable model) throws Exception {
		Serializable result; // 返回结果
		try {
			result = action.execute(model); // 执行
		} catch (ActionForwardException e) {
			String forwardActionName = e.getTarget(); // 不为空
			Serializable forwardModel = e.getModel();
			Action<Serializable, Serializable> forwardAction = ActionServletContext.getContext().getActionProvider().getAction(forwardActionName); // 获取Action实例
			if (forwardAction == null) // action实例不允许为空
				throw new NullPointerException("Not existed action: " + forwardActionName);
			ActionDelegate forwardActionDelegate = new ActionDelegate(actionInterceptor, forwardAction);
			ActionContext.getContext().pushAction(forwardActionName, forwardAction);
			try {
				return doExceute(forwardActionName, forwardActionDelegate, forwardModel);
			} finally {
				ActionContext.getContext().popAction();
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	protected Class<? extends Serializable> getModelClass(Action<Serializable, Serializable> action) throws Exception {
		return (Class<? extends Serializable>) ClassUtils.getMethod(action.getClass(), "execute").getParameterTypes()[0];
	}

	/**
	 * ActionProvider的配置参数名
	 */
	protected static final String ACTION_PROVIDER_PARAM_NAME = "actionProvider";

	/**
	 * 加载Action实例化工厂，子类可通过覆写该方法，拦截加载方式
	 * @return Action实例化工厂
	 * @throws Exception 异常均向上抛出，由框架统一处理
	 */
	protected ActionProvider getActionProvider() throws Exception {
		// 加载Action工厂
		String actionProviderClassName = super.getInitParameter(ACTION_PROVIDER_PARAM_NAME);
		if (actionProviderClassName != null && actionProviderClassName.trim().length() > 0) {
			try {
				Class<?> actionProviderClass = ClassUtils.forName(actionProviderClassName.trim());
				if (ActionProvider.class.isAssignableFrom(actionProviderClass)) {
					return (ActionProvider)actionProviderClass.newInstance();
				} else {
					logger.error(actionProviderClass.getName() + " unimplementet interface " + ActionProvider.class.getName());
				}
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
		return null;
	}

	/**
	 * ActionMapper的配置参数名
	 */
	protected static final String ACTION_MAPPER_PARAM_NAME = "actionMapper";

	/**
	 * 加载Action接收决策器，子类可通过覆写该方法，拦截加载方式
	 * @return Action接收决策器
	 * @throws Exception 异常均向上抛出，由框架统一处理
	 */
	protected ActionMapper getActionMapper() throws Exception {
		// 加载模型转换器
		String actionMapperClassName = super.getInitParameter(ACTION_MAPPER_PARAM_NAME);
		if (actionMapperClassName != null && actionMapperClassName.trim().length() > 0) {
			try {
				Class<?> actionMapperClass = ClassUtils.forName(actionMapperClassName.trim());
				if (ActionMapper.class.isAssignableFrom(actionMapperClass)) {
					return (ActionMapper)actionMapperClass.newInstance();
				} else {
					logger.error(actionMapperClass.getName() + " unimplementet interface " + ActionMapper.class.getName());
				}
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
		return null;
	}

	protected ActionInterceptor actionInterceptor;

	/**
	 * 将拦截器列表归约成链
	 * @param sctionInterceptors 拦截器列表
	 * @return 拦截器链的第一个拦截器
	 */
	protected ActionInterceptor reduceActionInterceptorChain(List<ActionInterceptor> actionInterceptors) {
		if (actionInterceptors == null || actionInterceptors.size() == 0)
			return null;
		ActionInterceptor lastActionInterceptor = null;
		for (int i = actionInterceptors.size() - 1; i >= 0; i --) { // 倒序拼装拦截器链
			ActionInterceptor actionInterceptor = (ActionInterceptor)actionInterceptors.get(i);
			if (actionInterceptor != null)
				lastActionInterceptor = new ActionInterceptorChain(actionInterceptor, lastActionInterceptor);
		}
		return lastActionInterceptor;
	}

	// 拦截器链
	protected static final class ActionInterceptorChain implements ActionInterceptor {

		// 当前拦截器
		private final ActionInterceptor currentActionInterceptor;

		// 下一拦截器
		private final ActionInterceptor nextActionInterceptor;

		/**
		 * 构造拦截器链
		 * @param currentActionInterceptor 当前拦截器
		 * @param nextActionInterceptor 下一拦截器
		 */
		ActionInterceptorChain(ActionInterceptor currentActionInterceptor, ActionInterceptor nextActionInterceptor) {
			if (currentActionInterceptor == null)
				throw new NullPointerException("currentActionInterceptor == null");
			this.currentActionInterceptor = currentActionInterceptor;
			this.nextActionInterceptor = nextActionInterceptor;
		}

		public Serializable intercept(Action<Serializable, Serializable> action, Serializable object) throws Exception {
			if (nextActionInterceptor == null)
				return currentActionInterceptor.intercept(action, object); // 如果没有下一拦载器，传入实际Action实例
			return currentActionInterceptor.intercept(new ActionDelegate(nextActionInterceptor, action), object); // 如果有下一拦载器，则代理下一拦截器
		}

	}

	/**
	 * Action委托于拦截器调用
	 * @author <a href="mailto:liangfei0201@gmail.com">liangfei</a>
	 */
	protected static final class ActionDelegate implements Action<Serializable, Serializable> {

		private final ActionInterceptor actionInterceptor;

		private final Action<Serializable, Serializable> action;

		ActionDelegate(ActionInterceptor actionInterceptor, Action<Serializable, Serializable> action) {
			if (action == null)
				throw new NullPointerException("action == null!");
			this.actionInterceptor = actionInterceptor;
			this.action = action;
		}

		public Serializable execute(Serializable object) throws Exception {
			if (actionInterceptor != null)
				return actionInterceptor.intercept(action, object); // 如果有拦截器，则执行拦截器
			return action.execute(object); // 否则，直接执行Action实例
		}

	}

}
