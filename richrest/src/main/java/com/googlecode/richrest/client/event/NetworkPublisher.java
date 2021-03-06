package com.googlecode.richrest.client.event;

/**
 * 连接事件发布器
 * @author <a href="mailto:liangfei0201@gmail.com">liangfei</a>
 */
public class NetworkPublisher extends Publisher<NetworkListener, NetworkEvent> {

	@Override
	protected void doEvent(NetworkListener listener, NetworkEvent event) {
		if (event.isConnected())
			listener.onConnected(event);
		else
			listener.onDisconnected(event);
	}

}
