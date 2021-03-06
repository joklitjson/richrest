package com.googlecode.richrest.server.provider;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import com.googlecode.richrest.Action;
import com.googlecode.richrest.server.ActionInterceptor;
import com.googlecode.richrest.server.ExceptionHandler;
import com.googlecode.richrest.server.util.PropertiesUtils;
import com.googlecode.richrest.util.ClassUtils;

/**
 * 基于actions.properties配置Action工厂实现
 * @author <a href="mailto:liangfei0201@gmail.com">liangfei</a>
 */
public class SimpleActionProvider extends AbstractActionProvider {

	protected final Map<String, Action<Serializable, Serializable>> actions = new HashMap<String, Action<Serializable, Serializable>>();

	protected static final String DEFAULT_PROPERTIES_NAME = "actions.properties";

	protected String getPropertiesName() {
		return DEFAULT_PROPERTIES_NAME;
	}

	@SuppressWarnings("unchecked")
	protected void init(ServletContext servletContext, ServletConfig servletConfig) {
		try {
			Map<String, ActionInterceptor> interceptors = new TreeMap<String, ActionInterceptor>();
			Properties properties = PropertiesUtils.getProperties(getPropertiesName());
			for (Map.Entry<?, ?> entry : properties.entrySet()) {
				String key = (String)entry.getKey();
				String value = (String)entry.getValue();
				try {
					Object obj = ClassUtils.forName(value).newInstance();
					if (obj instanceof Action)
						actions.put(key, (Action<Serializable, Serializable>)obj);
					else if (obj instanceof ExceptionHandler)
						exceptionHandlers.put((Class<? extends Throwable>) ClassUtils.forName(key), (ExceptionHandler)obj);
					else if (obj instanceof ActionInterceptor)
						interceptors.put(key, (ActionInterceptor)obj);
					else
						throw new ClassCastException(obj.getClass().getName() + " unimplement interface " + Action.class.getName() + " or " + ActionInterceptor.class.getName());
				} catch (Exception e) {
					throw new RuntimeException("load config: \" + getPropertiesName() + \" failure! please verify item: \"" + key + ":" + value + "\"! cause:" + e.getMessage(), e);
				}
			}
			properties.clear();
			for (ActionInterceptor interceptor : interceptors.values()) {
				actionInterceptors.add(interceptor);
			}
			interceptors.clear();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	protected Action<Serializable, Serializable> findAction(String actionName) throws Exception {
		return actions.get(actionName);
	}

	protected final List<ActionInterceptor> actionInterceptors = new ArrayList<ActionInterceptor>();

	protected List<ActionInterceptor> findActionInterceptors() throws Exception {
		return actionInterceptors;
	}

	protected final Map<Class<? extends Throwable>, ExceptionHandler> exceptionHandlers = new HashMap<Class<? extends Throwable>, ExceptionHandler>();

	public ExceptionHandler getExceptionHandler(Class<? extends Throwable> exceptionClass) {
		return exceptionHandlers.get(exceptionClass);
	}

}