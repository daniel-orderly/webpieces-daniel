package org.webpieces.httpclient.impl;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.webpieces.httpclient.api.HttpClient;
import org.webpieces.httpclient.api.HttpClientSocket;
import org.webpieces.httpclient.api.HttpsSslEngineFactory;
import org.webpieces.httpcommon.api.Http2SettingsMap;
import org.webpieces.httpcommon.api.RequestSender;
import org.webpieces.httpcommon.api.ResponseListener;
import org.webpieces.httpcommon.api.ServerListener;
import org.webpieces.httpparser.api.HttpParser;
import org.webpieces.httpparser.api.dto.HttpRequest;
import org.webpieces.httpparser.api.dto.HttpResponse;
import org.webpieces.nio.api.ChannelManager;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

import com.webpieces.hpack.api.HpackParser;

public class HttpsClientImpl implements HttpClient {

	private static final Logger log = LoggerFactory.getLogger(HttpClientImpl.class);
	private ChannelManager mgr;
	private HttpParser httpParser;
	private HpackParser http2Parser;
	private HttpsSslEngineFactory factory;
	private Http2SettingsMap http2SettingsMap;

	public HttpsClientImpl(ChannelManager mgr, HttpParser httpParser, HpackParser http2Parser, HttpsSslEngineFactory factory, Http2SettingsMap http2SettingsMap) {
		this.mgr = mgr;
		this.httpParser = httpParser;
		this.http2Parser = http2Parser;
		this.factory = factory;
		this.http2SettingsMap = http2SettingsMap;
	}

	@Override
	public CompletableFuture<HttpResponse> sendSingleRequest(InetSocketAddress addr, HttpRequest request) {
		HttpClientSocket socket = openHttpSocket(addr+"");
		CompletableFuture<RequestSender> connect = socket.connect(addr);
		return connect.thenCompose(requestSender -> requestSender.send(request));
	}

	@Override
	public void sendSingleRequest(InetSocketAddress addr, HttpRequest request, ResponseListener listener) {
		HttpClientSocket socket = openHttpSocket(addr+"");

		CompletableFuture<RequestSender> connect = socket.connect(addr);
		connect.thenAccept(requestListener -> requestListener.sendRequest(request, true, listener))
			.exceptionally(e -> fail(socket, listener, e));
	}

	private Void fail(HttpClientSocket socket, ResponseListener listener, Throwable e) {
		CompletableFuture<Void> closeSocket = socket.closeSocket();
		closeSocket.exceptionally(ee -> {
			log.error("could not close socket due to exception");
			return null;
		});
		listener.failure(e);
		return null;
	}

	@Override
	public HttpClientSocket openHttpSocket(String idForLogging) {
		return openHttpSocket(idForLogging, null);
	}

	@Override
	public HttpClientSocket openHttpSocket(String idForLogging, ServerListener listener) {
		return new HttpClientSocketImpl(mgr, idForLogging, factory, httpParser, http2Parser, listener, http2SettingsMap);
	}


}
