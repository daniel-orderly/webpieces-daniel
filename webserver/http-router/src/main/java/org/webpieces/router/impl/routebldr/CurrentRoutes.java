package org.webpieces.router.impl.routebldr;

import org.webpieces.router.impl.model.RouteModuleInfo;

import java.util.List;

public class CurrentRoutes {

	public static ThreadLocal<RouteModuleInfo> currentPackage = new ThreadLocal<>();

	public static RouteModuleInfo get() {
		return currentPackage.get();
	}
	
	public static void set(RouteModuleInfo info) {
		currentPackage.set(info);
	}

	public static void setProcessCorsHook(Class<? extends ProcessCors> corsProcessor) {
		RouteModuleInfo routeModuleInfo = get();
		if(routeModuleInfo == null)
			throw new IllegalStateException("This method can only be called within a file implementing Routes.java");

		routeModuleInfo.setCorProcessor(corsProcessor);
	}

}
