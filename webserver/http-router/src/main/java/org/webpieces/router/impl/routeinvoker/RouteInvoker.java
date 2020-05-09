package org.webpieces.router.impl.routeinvoker;

import java.util.concurrent.CompletableFuture;

import com.webpieces.http2engine.api.StreamWriter;
import org.webpieces.ctx.api.RequestContext;
import org.webpieces.router.api.ResponseStreamer;
import org.webpieces.router.api.RouterStreamHandle;
import org.webpieces.router.impl.ReverseRoutes;
import org.webpieces.router.impl.loader.LoadedController;
import org.webpieces.router.impl.routers.DynamicInfo;
import org.webpieces.router.impl.services.RouteData;
import org.webpieces.router.impl.services.RouteInfoForStatic;

public interface RouteInvoker {

	void init(ReverseRoutes reverseRoutes);

	//Even I admit this is a bit ridiculous BUT each one in DevRouteInvoker ONLY is slightly different.  It's very very
	//annoying.  If we were only doing ProdRouteInvoker, then they are all the same except the invokeNotFound and invokeStatic
	//which makes this very annoying!!  I could pass in the function!!!  if I do that, all of this collapses
	
	CompletableFuture<StreamWriter> invokeErrorController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data);

	CompletableFuture<StreamWriter> invokeHtmlController(InvokeInfo invokeInfo, DynamicInfo dynamicInfo, RouteData data);

	CompletableFuture<StreamWriter> invokeContentController(InvokeInfo invokeInfo, DynamicInfo dynamicInfo, RouteData data);
	
	CompletableFuture<StreamWriter> invokeNotFound(InvokeInfo invokeInfo, LoadedController loadedController, RouteData data);

	CompletableFuture<StreamWriter> invokeStatic(RequestContext ctx, RouterStreamHandle handler, RouteInfoForStatic data);

}
