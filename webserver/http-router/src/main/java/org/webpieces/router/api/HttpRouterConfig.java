package org.webpieces.router.api;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.webpieces.util.file.VirtualFile;

import com.google.inject.Module;

public class HttpRouterConfig {

	private VirtualFile metaFile;
	
	private Charset fileEncoding = StandardCharsets.UTF_8;
	private Charset urlEncoding = StandardCharsets.UTF_8;
	
	/**
	 * WebApps can override remote services to mock them out for testing or swap prod classes with
	 * an in-memory implementation such that tests can remain single threaded
	 */
	private Module webappOverrides;
	
	public VirtualFile getMetaFile() {
		return metaFile;
	}
	public Module getOverridesModule() {
		return webappOverrides;
	}
	public HttpRouterConfig setMetaFile(VirtualFile routersFile) {
		if(!routersFile.exists())
			throw new IllegalArgumentException("path="+routersFile+" does not exist");
		else if(routersFile.isDirectory())
			throw new IllegalArgumentException("path="+routersFile+" is a directory and needs to be a file");
		this.metaFile = routersFile;
		return this;
	}
	public HttpRouterConfig setWebappOverrides(Module webappOverrides) {
		this.webappOverrides = webappOverrides;
		return this;
	}
	public Charset getFileEncoding() {
		return fileEncoding;
	}
	public HttpRouterConfig setFileEncoding(Charset fileEncoding) {
		this.fileEncoding = fileEncoding;
		return this;
	}
	public Charset getUrlEncoding() {
		return urlEncoding;
	}

}
