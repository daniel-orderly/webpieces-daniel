package org.webpieces.webserver.tags;

import org.junit.Before;
import org.junit.Test;
import org.webpieces.httpcommon.Requests;
import org.webpieces.httpparser.api.dto.HttpRequest;
import org.webpieces.httpparser.api.dto.KnownHttpMethod;
import org.webpieces.httpparser.api.dto.KnownStatusCode;
import org.webpieces.util.file.VirtualFileClasspath;
import org.webpieces.webserver.ResponseExtract;
import org.webpieces.webserver.WebserverForTest;
import org.webpieces.webserver.test.AbstractWebpiecesTest;
import org.webpieces.webserver.test.FullResponse;
import org.webpieces.webserver.test.Http11Socket;

public class TestAHrefTag extends AbstractWebpiecesTest {

	
	private Http11Socket http11Socket;
	
	@Before
	public void setUp() {
		VirtualFileClasspath metaFile = new VirtualFileClasspath("tagsMeta.txt", WebserverForTest.class.getClassLoader());
		WebserverForTest webserver = new WebserverForTest(platformOverrides, null, false, metaFile);
		webserver.start();
		http11Socket = http11Simulator.openHttp();
	}

	@Test
	public void testAHref() {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/ahref");
		
		http11Socket.send(req);
		
        FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_200_OK);
		response.assertContains("<a href=`/verbatim` id=`myid`>My link2</a>".replace('`', '"'));
		response.assertContains("<a href=`/if`>My render link2</a>".replace('`', '"'));
		response.assertContains("<a href=`/else`>My full route link</a>".replace('`', '"'));
		response.assertContains("<a href=`/redirect/Dean+Hiller`>PureALink</a>".replace('`', '"'));
		response.assertContains("Link but no ahref='/redirect/Dean+Hiller'");
	}
	

}
