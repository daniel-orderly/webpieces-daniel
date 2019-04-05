package org.webpieces.router.impl.routebldr;

import org.webpieces.router.api.controller.actions.Action;
import org.webpieces.router.impl.dto.RouteType;
import org.webpieces.router.impl.loader.LoadedController;
import org.webpieces.router.impl.loader.svc.MethodMeta;
import org.webpieces.router.impl.routers.AbstractDynamicRouter;
import org.webpieces.router.impl.routers.EHtmlRouter;
import org.webpieces.util.filters.Service;

public class RouterAndInfo {

	private final AbstractDynamicRouter router;
	private final RouteInfo routeInfo;
	private final RouteType routeType;
	private final LoadedController loadedController;
	private final Service<MethodMeta, Action>  svcProxy;

	public RouterAndInfo(AbstractDynamicRouter router, RouteInfo routeInfo, RouteType routeType, LoadedController loadedController, Service<MethodMeta, Action>  svc) {
		this.router = router;
		this.routeInfo = routeInfo;
		this.routeType = routeType;
		this.loadedController = loadedController;
		this.svcProxy = svc;
	}

	public AbstractDynamicRouter getRouter() {
		return router;
	}

	public RouteInfo getRouteInfo() {
		return routeInfo;
	}

	public RouteType getRouteType() {
		return routeType;
	}

	public LoadedController getLoadedController() {
		return loadedController;
	}

	public Service<MethodMeta, Action> getSvcProxy() {
		return svcProxy;
	}

}
