package org.webpieces.nio.impl.ssl;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.nio.api.SSLEngineFactory;
import org.webpieces.nio.api.SSLEngineFactoryWithHost;
import org.webpieces.nio.api.channels.Channel;
import org.webpieces.nio.api.channels.TCPChannel;
import org.webpieces.nio.api.handlers.ConnectionListener;
import org.webpieces.nio.api.handlers.DataListener;
import org.webpieces.ssl.api.AsyncSSLEngine;
import org.webpieces.ssl.api.AsyncSSLFactory;
import org.webpieces.ssl.api.SslListener;

import com.webpieces.data.api.BufferPool;

public class SslTCPChannel extends SslChannel implements TCPChannel {

	private final static Logger log = LoggerFactory.getLogger(SslTCPChannel.class);
	private AsyncSSLEngine sslEngine;
	private SslTryCatchListener clientDataListener;
	private final TCPChannel realChannel;
	
	private SocketDataListener socketDataListener = new SocketDataListener();
	private ConnectionListener conectionListener;
	private CompletableFuture<Channel> sslConnectfuture;
	private CompletableFuture<Channel> closeFuture;
	private SslListener sslListener = new OurSslListener();
	private SSLEngineFactory sslFactory;
	private BufferPool pool;
	
	public SslTCPChannel(Function<SslListener, AsyncSSLEngine> function, TCPChannel realChannel) {
		super(realChannel);
		sslEngine = function.apply(sslListener );
		this.realChannel = realChannel;
	}

	public SslTCPChannel(BufferPool pool, TCPChannel realChannel2, ConnectionListener connectionListener, SSLEngineFactory sslFactory) {
		super(realChannel2);
		this.pool = pool;
		this.realChannel = realChannel2;
		this.conectionListener = connectionListener;
		this.sslFactory = sslFactory;
	}

	@Override
	public CompletableFuture<Channel> connect(SocketAddress addr, DataListener listener) {
		clientDataListener = new SslTryCatchListener(listener);
		CompletableFuture<Channel> future = realChannel.connect(addr, socketDataListener);
		
		return future.thenCompose( c -> beginHandshake());
	}

	public SocketDataListener getSocketDataListener() {
		return socketDataListener;
	}

	private CompletableFuture<Channel> beginHandshake() {
		sslConnectfuture = new CompletableFuture<Channel>();
		sslEngine.beginHandshake();
		return sslConnectfuture;
	}
	
	@Override
	public CompletableFuture<Channel> write(ByteBuffer b) {
		return sslEngine.feedPlainPacket(b).thenApply(v -> this);
	}

	@Override
	public CompletableFuture<Channel> close() {
		closeFuture = new CompletableFuture<>();		
		sslEngine.close();
		return closeFuture;
	}

	private class OurSslListener implements SslListener {
		@Override
		public void encryptedLinkEstablished() {
			if(sslConnectfuture != null)
				sslConnectfuture.complete(SslTCPChannel.this);
			else {
				CompletableFuture<DataListener> future = conectionListener.connected(SslTCPChannel.this, false);
				if(!future.isDone())
					conectionListener.failed(SslTCPChannel.this, new IllegalArgumentException("Client did not return a datalistener"));
				try {
					clientDataListener = new SslTryCatchListener(future.get());
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}

		@Override
		public CompletableFuture<Void> packetEncrypted(ByteBuffer engineToSocketData) {
			return realChannel.write(engineToSocketData).thenApply(c -> empty());
		}
		
		public Void empty() {
			return null;
		}

		@Override
		public void sendEncryptedHandshakeData(ByteBuffer engineToSocketData) {
			realChannel.write(engineToSocketData);
			//we don't care about future as we won't write anything out anyways until we get
			//data back and we have not fired connected to client so he should also not be writing yet too
		}
		
		@Override
		public void packetUnencrypted(ByteBuffer out) {
			clientDataListener.incomingData(SslTCPChannel.this, out);
		}

		@Override
		public void runTask(Runnable r) {
			//we are multithreaded underneath anyways using SessionExecutor so
			//we mine as well run this on same thread.
			r.run();
		}

		@Override
		public void closed(boolean clientInitiated) {
			if(!clientInitiated)
				clientDataListener.farEndClosed(SslTCPChannel.this);
			else if(closeFuture == null)
				throw new RuntimeException("bug, this should not be possible");
			else
				closeFuture.complete(SslTCPChannel.this);
		}
	}
	
	private class SocketDataListener implements DataListener {
		@Override
		public void incomingData(Channel channel, ByteBuffer b) {
			if(sslEngine == null) {
				setupSSLEngine(channel, b);
			}

			sslEngine.feedEncryptedPacket(b);
		}

		private void setupSSLEngine(Channel channel, ByteBuffer b) {
			try {
				setupSSLEngineImpl(channel, b);
			} catch (SSLException e) {
				throw new RuntimeException(e);
			}
		}
		
		private void setupSSLEngineImpl(Channel channel, ByteBuffer b) throws SSLException {
			SSLEngine engine;
			if(sslFactory instanceof SSLEngineFactoryWithHost) {
				SSLEngineFactoryWithHost sslFactoryWithHost = (SSLEngineFactoryWithHost) sslFactory;
				SSLEngine dummyForSni = createSslEngine();
				ByteBuffer out = pool.nextBuffer(dummyForSni.getSession().getApplicationBufferSize());
				ByteBuffer duplicate = b.duplicate();
				SSLEngineResult unwrap = dummyForSni.unwrap(duplicate, out);
				if(unwrap.getStatus() != Status.OK)
					throw new IllegalArgumentException("status="+unwrap.getStatus()+" so we should support this one?");
				
				List<SNIServerName> serverNames = dummyForSni.getSSLParameters().getServerNames();
				List<String> names = translate(serverNames);
				String host = null;
				if(serverNames.size() == 0) {
					log.warn("SNI servernames missing from client.  channel="+channel.getRemoteAddress());
				} else if(serverNames.size() > 1) {
					log.warn("SNI servernames are too many. names="+names+" channel="+channel.getRemoteAddress());
				}
				
				engine = sslFactoryWithHost.createSslEngine(host);
			} else {
				engine = sslFactory.createSslEngine();
			}
			
			sslEngine = AsyncSSLFactory.create(realChannel+"", engine, pool, sslListener);
		}
	
		private List<String> translate(List<SNIServerName> serverNames) {
			List<String> names = new ArrayList<>();
			for(SNIServerName sniName : serverNames) {
				String name = new String(sniName.getEncoded());
				names.add(name);
			}
			return names;
		}

		@Override
		public void farEndClosed(Channel channel) {
			clientDataListener.farEndClosed(SslTCPChannel.this);
		}
	
		@Override
		public void failure(Channel channel, ByteBuffer data, Exception e) {
			clientDataListener.failure(SslTCPChannel.this, data, e);
		}
	
		@Override
		public void applyBackPressure(Channel channel) {
			clientDataListener.applyBackPressure(SslTCPChannel.this);
		}
	
		@Override
		public void releaseBackPressure(Channel channel) {
			clientDataListener.releaseBackPressure(SslTCPChannel.this);
		}
	}
	
	public SSLEngine createSslEngine() {
		try {
			// Create/initialize the SSLContext with key material
	
			InputStream in = getClass().getClassLoader().getResourceAsStream("selfsigned.jks");
			
			char[] passphrase = "password".toCharArray();
			// First initialize the key and trust material.
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(in, passphrase);
			SSLContext sslContext = SSLContext.getInstance("TLS");
			
			//****************Server side specific*********************
			// KeyManager's decide which key material to use.
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, passphrase);
			sslContext.init(kmf.getKeyManagers(), null, null);		
			//****************Server side specific*********************
			
			SSLEngine engine = sslContext.createSSLEngine();
			engine.setUseClientMode(false);
			
			return engine;
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public boolean getKeepAlive() {
		return realChannel.getKeepAlive();
	}

	@Override
	public void setKeepAlive(boolean b) {
		realChannel.setKeepAlive(b);
	}

	public DataListener getDataListener() {
		return socketDataListener;
	}

}
