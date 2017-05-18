package org.webpieces.webserver.dev;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.webpieces.compiler.api.CompileConfig;
import org.webpieces.devrouter.api.DevRouterModule;
import org.webpieces.httpcommon.Requests;
import org.webpieces.httpparser.api.dto.HttpRequest;
import org.webpieces.httpparser.api.dto.KnownHttpMethod;
import org.webpieces.httpparser.api.dto.KnownStatusCode;
import org.webpieces.templatingdev.api.TemplateCompileConfig;
import org.webpieces.util.file.VirtualFile;
import org.webpieces.util.file.VirtualFileImpl;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;
import org.webpieces.webserver.ResponseExtract;
import org.webpieces.webserver.WebserverForTest;
import org.webpieces.webserver.test.AbstractWebpiecesTest;
import org.webpieces.webserver.test.Asserts;
import org.webpieces.webserver.test.FullResponse;
import org.webpieces.webserver.test.Http11Socket;
import org.webpieces.webserver.test.PlatformOverridesForTest;

import com.google.inject.Module;
import com.google.inject.util.Modules;

public class TestDevRefreshPageWithNoRestarting extends AbstractWebpiecesTest {

	private static final Logger log = LoggerFactory.getLogger(TestDevSynchronousErrors.class);
	
	
	private File stashedExistingCodeDir;
	private File existingCodeLoc;
	private String userDir;

	private Http11Socket http11Socket;
	
	@Before
	public void setUp() throws ClassNotFoundException, IOException {
		Asserts.assertWasCompiledWithParamNames("test");
		userDir = System.getProperty("user.dir");
		log.info("running from dir="+userDir);

		existingCodeLoc = new File(userDir+"/src/test/java/org/webpieces/webserver/dev/app");
		
		//developers tend to exit their test leaving the code in a bad state so if they run it again, restore the original
		//version for them(if we change the original version, we have to copy it to this directory as well though :(
		File original = new File(userDir+"/src/test/devServerTest/devServerOriginal");
		FileUtils.copyDirectory(original, existingCodeLoc, null);
		
		//cache existing code for use by teardown...

		stashedExistingCodeDir = new File(System.getProperty("java.io.tmpdir")+"/webpiecesTestDevServer/app");
		FileUtils.copyDirectory(existingCodeLoc, stashedExistingCodeDir);
		
		//list all source paths here as you add them(or just create for loop)
		//These are the list of directories that we detect java file changes under
		List<VirtualFile> srcPaths = new ArrayList<>();
		srcPaths.add(new VirtualFileImpl(userDir+"/src/test/java"));
		
		VirtualFile metaFile = new VirtualFileImpl(userDir + "/src/test/resources/devMeta.txt");
		log.info("LOADING from meta file="+metaFile.getCanonicalPath());
		
		//html and json template file encoding...
		TemplateCompileConfig templateConfig = new TemplateCompileConfig(srcPaths);
		//java source files encoding...
		CompileConfig devConfig = new CompileConfig(srcPaths);
		
		Module platformOverrides = Modules.combine(
										new DevRouterModule(devConfig),
										new PlatformOverridesForTest(mgr, time, mockTimer, templateConfig));
		
		WebserverForTest webserver = new WebserverForTest(platformOverrides, null, false, metaFile);
		webserver.start();
		http11Socket = http11Simulator.openHttp();
	}
	
	@After
	public void tearDown() throws IOException {
		//delete any modifications and restore the original code...
		FileUtils.deleteDirectory(existingCodeLoc);
		FileUtils.copyDirectory(stashedExistingCodeDir, existingCodeLoc);
	}
	
	@Test
	public void testGuiceModuleAddAndControllerChange() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/home");
		http11Socket.send(req);
		verifyPageContents("user=Dean Hiller");
		
		simulateDeveloperMakesChanges("src/test/devServerTest/guiceModule");
		
		http11Socket.send(req);
		verifyPageContents("newuser=Joseph");
	}

	//Different than swapping out meta 
	@Test
	public void testJustControllerChanged() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/home");
		http11Socket.send(req);
		verifyPageContents("user=Dean Hiller");
		
		simulateDeveloperMakesChanges("src/test/devServerTest/controllerChange");
		
		http11Socket.send(req);
		verifyPageContents("user=CoolJeff");
	}

	@Test
	public void testRouteAdditionWithNewControllerPath() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/newroute");
		http11Socket.send(req);
		
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_404_NOTFOUND);
		
		simulateDeveloperMakesChanges("src/test/devServerTest/routeChange");
		
		http11Socket.send(req);
		verifyPageContents("Existing Route Page");
	}
	
	@Test
	public void testFilterChanged() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/filter");
		http11Socket.send(req);
		
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_303_SEEOTHER);
		Assert.assertEquals("http://myhost.com/home", response.getRedirectUrl());
		
		simulateDeveloperMakesChanges("src/test/devServerTest/filterChange");
		
		http11Socket.send(req);

		response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_303_SEEOTHER);
		Assert.assertEquals("http://myhost.com/causeError", response.getRedirectUrl());
	}

	@Test
	public void testNotFoundDisplaysWithIframeANDSpecialUrl() {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/notFound");
		http11Socket.send(req);
		
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_404_NOTFOUND);

		//platform should convert request into a development not found page which has an iframe
		//of the original page with a query param to tell platform to display original 
		//page requested 
		response.assertContains("<iframe src=\"/notFound?webpiecesShowPage=true");
	}
	
	@Test
	public void testNotFoundFilterNotChangedAndTwoRequests() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/anyNotFound?webpiecesShowPage");
		http11Socket.send(req);
		verify404PageContents("value1=something1");

		http11Socket.send(req);

		verify404PageContents("value1=something1");
	}
	
	@Test
	public void testNotFoundRouteModifiedAndControllerModified() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/anyNotfound?webpiecesShowPage=true");
		http11Socket.send(req);
		verify404PageContents("value1=something1");
		
		simulateDeveloperMakesChanges("src/test/devServerTest/notFound");
		
		http11Socket.send(req);
		verify404PageContents("value2=something2");
	}

	@Test
	public void testNotFoundFilterModified() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/enableFilter?webpiecesShowPage=true");
		http11Socket.send(req);

		verify303("http://myhost.com/home");
		
		simulateDeveloperMakesChanges("src/test/devServerTest/notFound");
		
		http11Socket.send(req);
		
		verify303("http://myhost.com/filter");
	}

	private void verify303(String url) {
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_303_SEEOTHER);
		Assert.assertEquals(url, response.getRedirectUrl());
	}
	
	@Test
	public void testInternalErrorModifiedAndControllerModified() throws IOException {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/causeError");
		http11Socket.send(req);
		verify500PageContents("InternalError1=error1");
		
		simulateDeveloperMakesChanges("src/test/devServerTest/internalError");
		
		http11Socket.send(req);
		verify500PageContents("InternalError2=error2");		
	}
	
	private void simulateDeveloperMakesChanges(String directory) throws IOException {
		File srcDir = new File(userDir+"/"+directory);
		FileUtils.copyDirectory(srcDir, existingCodeLoc, null, false);
	}

	private void verifyPageContents(String contents) {
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_200_OK);
		response.assertContains(contents);
	}
	
	private void verify404PageContents(String contents) {
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_404_NOTFOUND);
		response.assertContains(contents);
	}
	
	private void verify500PageContents(String contents) {
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_500_INTERNAL_SVR_ERROR);
		response.assertContains(contents);
	}
	
}
