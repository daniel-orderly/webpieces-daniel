package org.webpieces.router.impl.loader.svc;

import org.webpieces.router.api.exceptions.NotFoundException;

public class RouteInfoForNotFound implements RouteData {

	private final NotFoundException notFoundException;

	public NotFoundException getNotFoundException() {
		return notFoundException;
	}

	public RouteInfoForNotFound(NotFoundException notFoundException) {
		this.notFoundException = notFoundException;
	}

}
