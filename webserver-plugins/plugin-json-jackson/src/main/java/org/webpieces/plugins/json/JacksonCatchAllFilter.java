package org.webpieces.plugins.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.httpparser.api.dto.KnownStatusCode;
import org.webpieces.router.api.controller.actions.Action;
import org.webpieces.router.api.controller.actions.RenderContent;
import org.webpieces.router.api.exceptions.HttpException;
import org.webpieces.router.api.routes.MethodMeta;
import org.webpieces.router.api.routes.RouteFilter;
import org.webpieces.router.impl.compression.MimeTypes.MimeTypeResult;
import org.webpieces.util.filters.Service;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webpieces.http2parser.api.dto.StatusCode;

public abstract class JacksonCatchAllFilter extends RouteFilter<JsonConfig> {

	private static final Logger log = LoggerFactory.getLogger(JacksonCatchAllFilter.class);
	public static final MimeTypeResult MIME_TYPE = new MimeTypeResult("application/json", StandardCharsets.UTF_8);
	private final ObjectMapper mapper;

	private Boolean isNotFoundFilter;
	private Pattern pattern;

	public JacksonCatchAllFilter(ObjectMapper mapper) {
		this.mapper = mapper;
	}
	@Override
	public CompletableFuture<Action> filter(MethodMeta meta, Service<MethodMeta, Action> nextFilter) {
		if(isNotFoundFilter)
			return createNotFoundResponse(nextFilter, meta);

		return nextFilter.invoke(meta).handle((a, t) -> translateFailure(a, t));
	}

	@Override
	public void initialize(JsonConfig config) {
		this.isNotFoundFilter = config.isNotFoundFilter();
		this.pattern = config.getFilterPattern();
	}

	protected Action translateFailure(Action action, Throwable t) {
		if(t != null) {
			if(t instanceof HttpException) {
				return translate((HttpException)t);
			}
			
			log.error("Internal Server Error", t);
			return translateError(t);
		} else {
			return action;
		}
	}

	private Action translate(HttpException t) {
		byte[] content = translateHttpException(t);
		StatusCode status = t.getStatusCode();
		return new RenderContent(content, status.getCode(), status.getReason(), MIME_TYPE);		
	}

	protected RenderContent translateError(Throwable t) {
		byte[] content = translateServerError(t);
		KnownStatusCode status = KnownStatusCode.HTTP_500_INTERNAL_SVR_ERROR;
		return new RenderContent(content, status.getCode(), status.getReason(), MIME_TYPE);
	}

	protected CompletableFuture<Action> createNotFoundResponse(Service<MethodMeta, Action> nextFilter, MethodMeta meta) {
		Matcher matcher = pattern.matcher(meta.getCtx().getRequest().relativePath);
		if(!matcher.matches())
			return nextFilter.invoke(meta);
		
		return CompletableFuture.completedFuture(
					createNotFound()
				);
	}

	protected Action createNotFound() {
		byte[] content = createNotFoundJsonResponse();		
		return new RenderContent(content, KnownStatusCode.HTTP_404_NOTFOUND.getCode(), KnownStatusCode.HTTP_404_NOTFOUND.getReason(), MIME_TYPE);
	}

	protected byte[] translateHttpException(HttpException t) {
		JsonError error = new JsonError();
		error.setError(t.getStatusCode().getReason()+" : "+t.getMessage());
		error.setCode(t.getStatusCode().getCode());

		return translateJson(mapper, error);
	}


	protected byte[] createNotFoundJsonResponse() {
		JsonError error = new JsonError();
		error.setError("This url does not exist.  try another url");
		error.setCode(404);
		return translateJson(mapper, error);
	}

	protected byte[] translateServerError(Throwable t) {
		JsonError error = new JsonError();
		error.setError("Server ran into a bug, please report");
		error.setCode(500);
		return translateJson(mapper, error);
	}
	
	protected byte[] translateJson(ObjectMapper mapper, Object error) {
		try {
			return mapper.writeValueAsBytes(error);
		} catch (JsonGenerationException e) {
			throw new RuntimeException(e);
		} catch (JsonMappingException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
}
