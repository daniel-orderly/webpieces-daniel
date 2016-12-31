package org.webpieces.http2client;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.webpieces.data.api.DataWrapper;
import org.webpieces.http2client.api.Http2ResponseListener;
import org.webpieces.http2client.api.Http2ServerListener;
import org.webpieces.http2client.api.Http2Socket;
import org.webpieces.http2client.api.PushPromiseListener;
import org.webpieces.http2client.api.dto.Http2Headers;
import org.webpieces.http2client.api.dto.PartialResponse;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

import com.webpieces.http2parser.api.dto.Http2Frame;
import com.webpieces.http2parser.api.dto.Http2GoAway;
import com.webpieces.http2parser.api.dto.Http2UnknownFrame;

public class IntegColoradoEdu {

	private static final Logger log = LoggerFactory.getLogger(IntegColoradoEdu.class);
	
	public static void main(String[] args) throws InterruptedException {
		boolean isHttp = true;
		
		String host = "www.colorado.edu";
		int port = 443;
		if(isHttp)
			port = 80;
		
		Http2Headers req = createRequest(host);

		log.info("starting socket");
		ChunkedResponseListener listener = new ChunkedResponseListener();
		
		InetSocketAddress addr = new InetSocketAddress(host, port);
		Http2Socket socket = IntegGoogleHttps.createHttpClient("oneTimerHttp2Socket", isHttp, addr);
		
		socket
			.connect(addr, new ServerListenerImpl())
			.thenAccept(socet -> socket.sendRequest(req, listener, true))
			.exceptionally(e -> reportException(socket, e));

		Thread.sleep(100000);
	}

	private static Void reportException(Http2Socket socket, Throwable e) {
		log.error("exception on socket="+socket, e);
		return null;
	}

	private static class ServerListenerImpl implements Http2ServerListener {

		@Override
		public void farEndClosed(Http2Socket socket) {
			log.info("far end closed");
		}

		@Override
		public void failure(Exception e) {
			log.warn("exception", e);
		}

		@Override
		public void incomingControlFrame(Http2Frame lowLevelFrame) {
			if(lowLevelFrame instanceof Http2GoAway) {
				Http2GoAway goAway = (Http2GoAway) lowLevelFrame;
				DataWrapper debugData = goAway.getDebugData();
				String debug = debugData.createStringFrom(0, debugData.getReadableSize(), StandardCharsets.UTF_8);
				log.info("go away received.  debug="+debug);
			} else 
				throw new UnsupportedOperationException("not done yet.  frame="+lowLevelFrame);
		}
		
	}
	
	private static class ChunkedResponseListener implements Http2ResponseListener, PushPromiseListener {

		@Override
		public void incomingPartialResponse(PartialResponse response) {
			log.info("incoming part of response="+response);
		}

		@Override
		public PushPromiseListener newIncomingPush(int streamId) {
			return this;
		}
		
		@Override
		public void serverCancelledRequest() {
			log.info("server cancelled request");
		}

		@Override
		public void incomingPushPromise(PartialResponse response) {
			log.info("incoming push promise");
		}

	}
	
	private static Http2Headers createRequest(String host) {
//		GET / HTTP/1.1
//		Host: www.colorado.edu
//		User-Agent: curl/7.43.0
//		Accept: */*
//		
//		HttpRequestLine requestLine = new HttpRequestLine();
//		requestLine.setMethod(KnownHttpMethod.GET);
//		requestLine.setUri(new HttpUri("/"));
//		
//		HttpRequest req = new HttpRequest();
//		req.setRequestLine(requestLine);
//		req.addHeader(new Header(KnownHeaderName.HOST, host));
//		req.addHeader(new Header(KnownHeaderName.ACCEPT, "*/*"));
//		req.addHeader(new Header(KnownHeaderName.USER_AGENT, "webpieces/0.9"));
		return null;
	}
}
