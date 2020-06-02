package org.webpieces.router.impl.routers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.ctx.api.RequestContext;
import org.webpieces.router.api.exceptions.InternalErrorRouteFailedException;
import org.webpieces.router.api.exceptions.NotFoundException;
import org.webpieces.router.api.exceptions.SpecificRouterInvokeException;
import org.webpieces.router.impl.RouterFutureUtil;
import org.webpieces.router.impl.model.RouterInfo;
import org.webpieces.router.impl.proxyout.ProxyStreamHandle;
import org.webpieces.router.impl.routeinvoker.RouterStreamRef;
import org.webpieces.util.futures.FutureHelper;
import org.webpieces.util.logging.SupressedExceptionLog;

import com.webpieces.http2engine.api.StreamRef;
import com.webpieces.http2engine.api.StreamWriter;

public class DScopedRouter extends EScopedRouter {

	private static final Logger log = LoggerFactory.getLogger(DScopedRouter.class);

	private ENotFoundRouter pageNotFoundRouter;
	private EInternalErrorRouter internalSvrErrorRouter;
	private RouterFutureUtil futureUtil;

	private FutureHelper futureHelper;

	public DScopedRouter(
			RouterInfo routerInfo, 
			Map<String, EScopedRouter> pathPrefixToNextRouter, 
			List<AbstractRouter> routers, 
			ENotFoundRouter notFoundRouter, 
			EInternalErrorRouter internalErrorRouter,
			RouterFutureUtil futureUtil,
			FutureHelper futureHelper
	) {
		super(futureUtil, routerInfo, pathPrefixToNextRouter, routers);
		this.pageNotFoundRouter = notFoundRouter;
		this.internalSvrErrorRouter = internalErrorRouter;
		this.futureUtil = futureUtil;
		this.futureHelper = futureHelper;
	}

	@Override
	public RouterStreamRef invokeRoute(RequestContext ctx, ProxyStreamHandle handler, String subPath) {
		RouterStreamRef streamRef = futureUtil.catchBlock(
				() -> invokeRouteCatchNotFound(ctx, handler, subPath),
				(t) -> tryRenderWebAppErrorControllerResult(ctx, handler, t, false)
		);
		
		CompletableFuture<StreamWriter> newWriter = streamRef.getWriter().thenApply(w -> createProxy(w, ctx, handler));
		
		return new RouterStreamRef("dScopedRouter", newWriter, streamRef);
	}

	private StreamWriter createProxy(StreamWriter strWriter, RequestContext ctx, ProxyStreamHandle handler) {
		return new NonStreamingWebAppErrorProxy(futureHelper, strWriter, handler,
				(t) -> tryRenderWebAppErrorControllerResult(ctx, handler, t, true));
	}

	private RouterStreamRef tryRenderWebAppErrorControllerResult(RequestContext ctx, ProxyStreamHandle handler, Throwable t, boolean forceEndOfStream) {
		if(ExceptionWrap.isChannelClosed(t)) {
			//if the socket was closed before we responded, do not log a failure
			if(log.isTraceEnabled())
				log.trace("async exception due to socket being closed", t);
			return new RouterStreamRef("channelClosed");
		}

		String failedRoute = "<Unknown Route>";
		if(t instanceof SpecificRouterInvokeException)
			failedRoute = ((SpecificRouterInvokeException) t).getMatchInfo()+"";

		log.error("There is three parts to this error message... request, route found, and the exception "
				+ "message.  You should\nread the exception message below  as well as the RouterRequest and RouteMeta.\n\n"
				+ctx.getRequest()+"\n\n"+failedRoute+".  \n\nNext, server will try to render apps 5xx page\n\n", t);
		SupressedExceptionLog.log(log, t);


		//If it is a streaming, controller AND response has already been sent, we cannot render the web apps error controller
		//page so in that case, fail, and cancel the stream
		if(handler.hasSentResponseAlready()) {
			return new RouterStreamRef("responseAlreadySent");
		}
		
		return invokeWebAppErrorController(t, ctx, handler, failedRoute, forceEndOfStream);
	}

	/**
	 * NOTE: We have to catch any exception from the method processNotFound so we can't catch and call internalServerError in this
	 * method without nesting even more!!! UGH, more nesting sucks
	 */
	private RouterStreamRef invokeRouteCatchNotFound(RequestContext ctx, ProxyStreamHandle handler, String subPath) {
		return futureUtil.catchBlock(
				() -> super.invokeRoute(ctx, handler, subPath),
				(t) -> {
					if (t instanceof NotFoundException)
						return notFound((NotFoundException) t, ctx, handler);
					
					return null; //return original StreamRef
				}
		);
	}

	private RouterStreamRef invokeWebAppErrorController(
			Throwable exc, RequestContext requestCtx, ProxyStreamHandle handler, Object failedRoute, boolean forceEndOfStream) {
		//This method is simply to translate the exception to InternalErrorRouteFailedException so higher levels
		//can determine if it was our bug or the web applications bug in it's Controller for InternalErrors
		return futureUtil.catchBlockWrap(
			() -> internalSvrErrorRouter.invokeErrorRoute(requestCtx, handler, forceEndOfStream),
			(t) -> convert(failedRoute, t)
		);
	}

	private InternalErrorRouteFailedException convert(Object failedRoute, Throwable t) {
		return new InternalErrorRouteFailedException(t, failedRoute);
	}
	
	private RouterStreamRef notFound(NotFoundException exc, RequestContext requestCtx, ProxyStreamHandle handler) {
		return futureUtil.catchBlockWrap(
			() -> pageNotFoundRouter.invokeNotFoundRoute(requestCtx, handler, exc),
			(e) -> new RuntimeException("NotFound Route had an exception", e)
		);
	}
	
	public String getDomain() {
		return routerInfo.getRouterId();
	}
}
