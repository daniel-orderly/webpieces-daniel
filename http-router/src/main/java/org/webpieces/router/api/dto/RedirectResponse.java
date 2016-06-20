package org.webpieces.router.api.dto;

public class RedirectResponse {

	public boolean isHttps;
	public String domain;
	public String redirectToPath;

	public RedirectResponse(Boolean isHttps, String domain, String redirectToPath) {
		this.isHttps = isHttps;
		this.domain = domain;
		this.redirectToPath = redirectToPath;
	}

	@Override
	public String toString() {
		return "Response [isHttps=" + isHttps + ", domain=" + domain + ", path=" + redirectToPath + "]";
	}
	
}
