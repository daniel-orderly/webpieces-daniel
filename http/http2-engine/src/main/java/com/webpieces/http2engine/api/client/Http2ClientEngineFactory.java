package com.webpieces.http2engine.api.client;

import java.util.concurrent.Executor;

import com.webpieces.hpack.api.HpackParser;
import com.webpieces.http2engine.impl.client.Level1ClientEngine;
import com.webpieces.http2engine.impl.shared.HeaderSettings;

public class Http2ClientEngineFactory {

	public Http2ClientEngine createClientParser(HpackParser lowLevelParser, Executor backupPool, ClientEngineListener resultListener, HeaderSettings localSettings) {
		return new Level1ClientEngine(lowLevelParser, resultListener, localSettings, backupPool);
	}
	
	public Http2ClientEngine createClientParser(String id, HpackParser lowLevelParser, Executor backupPool, ClientEngineListener resultListener) {
		HeaderSettings localSettings = new HeaderSettings();
		localSettings.setId(id);
		
		//allow 100 frames of room
		long initialWindowSize = 100 * localSettings.getMaxFrameSize();
		//localSettings.setInitialWindowSize(1000);
		localSettings.setInitialWindowSize(initialWindowSize);
		return createClientParser(lowLevelParser, backupPool, resultListener, localSettings);
	}
}
