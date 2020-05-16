package org.webpieces.nio.impl.threading;

import java.util.concurrent.CompletableFuture;

import org.slf4j.MDC;
import org.webpieces.nio.api.channels.Channel;
import org.webpieces.nio.api.channels.RegisterableChannel;
import org.webpieces.nio.api.channels.TCPChannel;
import org.webpieces.nio.api.handlers.ConnectionListener;
import org.webpieces.nio.api.handlers.DataListener;
import org.webpieces.util.threading.SessionExecutor;

public class ThreadConnectionListener implements ConnectionListener {

	private ConnectionListener connectionListener;
	private SessionExecutor executor;

	public ThreadConnectionListener(ConnectionListener connectionListener, SessionExecutor executor) {
		this.connectionListener = connectionListener;
		this.executor = executor;
	}

	@Override
	public CompletableFuture<DataListener> connected(Channel channel, boolean isReadyForWrites) {
		CompletableFuture<DataListener> future = new CompletableFuture<DataListener>();

		ThreadTCPChannel proxy = new ThreadTCPChannel((TCPChannel) channel, executor);

		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				MDC.put("socket", channel+"");
				try {
					CompletableFuture<DataListener> dataListener = connectionListener.connected(proxy, isReadyForWrites);
					//transfer the listener to the future to be used
					dataListener
						.thenAccept(listener -> translate(proxy, future, listener))
						.exceptionally(e -> {
							future.completeExceptionally(e);
							return null;
						});
				} finally {
					MDC.put("socket", null);
				}
			}
		};
		
		executor.execute(proxy, runnable);
		
		return future;
	}

	private void translate(ThreadTCPChannel proxy, CompletableFuture<DataListener> future, DataListener listener) {
		DataListener wrappedDataListener = new ThreadDataListener(proxy, listener, executor);
		future.complete(wrappedDataListener);
	}
	
	@Override
	public void failed(RegisterableChannel channel, Throwable e) {
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				connectionListener.failed(channel, e);
			}
		};
		
		executor.execute(channel, new SessionRunnable(runnable, channel));
	}
}
