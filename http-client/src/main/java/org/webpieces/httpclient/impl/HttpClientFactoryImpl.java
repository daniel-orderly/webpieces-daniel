package org.webpieces.httpclient.impl;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.webpieces.httpclient.api.HttpClient;
import org.webpieces.httpclient.api.HttpClientFactory;
import org.webpieces.nio.api.ChannelManager;
import org.webpieces.nio.api.ChannelManagerFactory;
import org.webpieces.util.threading.NamedThreadFactory;

import com.webpieces.data.api.BufferCreationPool;
import com.webpieces.httpparser.api.HttpParser;
import com.webpieces.httpparser.api.HttpParserFactory;

public class HttpClientFactoryImpl extends HttpClientFactory {

	@Override
	public HttpClient createHttpClient(ChannelManager mgr, HttpParser parser) {
		return new HttpClientImpl(mgr, parser);
	}

	@Override
	public HttpClient createHttpClient(int numThreads) {
		Executor executor = Executors.newFixedThreadPool(numThreads, new NamedThreadFactory("httpclient"));
		BufferCreationPool pool = new BufferCreationPool(false, 2000, 1000);
		HttpParser parser = HttpParserFactory.createParser(pool);
		ChannelManagerFactory factory = ChannelManagerFactory.createFactory();
		ChannelManager mgr = factory.createMultiThreadedChanMgr("httpClientChanMgr", pool, executor);
		
		return createHttpClient(mgr, parser);
	}

}
