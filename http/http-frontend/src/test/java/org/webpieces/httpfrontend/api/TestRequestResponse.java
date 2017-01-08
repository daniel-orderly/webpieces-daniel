package org.webpieces.httpfrontend.api;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.webpieces.data.api.BufferCreationPool;
import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;
import org.webpieces.data.api.DataWrapperGeneratorFactory;
import org.webpieces.httpcommon.Requests;
import org.webpieces.httpcommon.Responses;
import org.webpieces.httpparser.api.HttpParser;
import org.webpieces.httpparser.api.HttpParserFactory;
import org.webpieces.httpparser.api.Memento;
import org.webpieces.httpparser.api.common.Header;
import org.webpieces.httpparser.api.common.KnownHeaderName;
import org.webpieces.httpparser.api.dto.HttpPayload;
import org.webpieces.httpparser.api.dto.HttpRequest;
import org.webpieces.httpparser.api.dto.HttpResponse;
import org.webpieces.httpparser.api.dto.KnownHttpMethod;
import org.webpieces.httpparser.api.dto.KnownStatusCode;
import org.webpieces.nio.api.handlers.DataListener;

import com.twitter.hpack.Decoder;
import com.webpieces.hpack.api.HpackParser;
import com.webpieces.hpack.api.HpackParserFactory;
import com.webpieces.hpack.api.UnmarshalState;
import com.webpieces.hpack.api.dto.Http2Headers;
import com.webpieces.hpack.api.dto.Http2Push;
import com.webpieces.http2parser.api.dto.DataFrame;
import com.webpieces.http2parser.api.dto.SettingsFrame;
import com.webpieces.http2parser.api.dto.lib.Http2Msg;
import com.webpieces.http2parser.api.dto.lib.Http2Setting;
import com.webpieces.http2parser.api.dto.lib.SettingsParameter;

public class TestRequestResponse {

    private HttpParser httpParser;
    private HpackParser http2Parser;
    private DataWrapperGenerator dataGen = DataWrapperGeneratorFactory.createDataWrapperGenerator();
    private SettingsFrame settingsFrame = new SettingsFrame();
    private Decoder decoder;
    private List<Http2Setting> settings = new ArrayList<>();
    private static String blahblah = "blah blah blah";

    @Before
    public void setup() {
        BufferCreationPool pool = new BufferCreationPool();

        httpParser = HttpParserFactory.createParser(pool);
        http2Parser = HpackParserFactory.createParser(pool, true);
        decoder = new Decoder(4096, 4096);
        settings.add(new Http2Setting(SettingsParameter.SETTINGS_MAX_FRAME_SIZE, 16384L));
        settingsFrame.setSettings(settings);
    }

    private ByteBuffer processRequestWithRequestListener(HttpRequest request, RequestListenerForTest requestListenerForTest)
            throws InterruptedException, ExecutionException {
        MockServer mockServer = new MockServer(80, false, requestListenerForTest);
        DataListener dataListener = mockServer.getDataListener();

        ByteBuffer buffer = httpParser.marshalToByteBuffer(request);
        dataListener.incomingData(mockServer.getMockTcpChannel(), buffer);

        // TODO: fix this to wait until we're done, not just sleep, which is fragile.
        Thread.sleep(1000);

        return mockServer.getMockTcpChannel().getWriteLog();
    }

    private void simpleHttp11RequestWithListener(RequestListenerForTest listener) throws InterruptedException, ExecutionException {
        HttpRequest request = Requests.createRequest(KnownHttpMethod.GET, "/");
        ByteBuffer bytesWritten = processRequestWithRequestListener(request, listener);
        Assert.assertTrue(new String(bytesWritten.array()).contains("HTTP/1.1 200 OK\r\n" +
            "Content-Length: 0\r\n"));
    }

    private void upgradeHttp2RequestWithListener(RequestListenerForTest listener) throws InterruptedException, ExecutionException {
        HttpRequest request = Requests.createRequest(KnownHttpMethod.GET, "/");
        request.addHeader(new Header(KnownHeaderName.UPGRADE, "h2c"));
        request.addHeader(new Header(KnownHeaderName.CONNECTION, "Upgrade, HTTP2-Settings"));
        String base64 = http2Parser.marshalSettingsPayload(settingsFrame.getSettings());

        request.addHeader(new Header(KnownHeaderName.HTTP2_SETTINGS, base64 + " "));

        ByteBuffer bytesWritten = processRequestWithRequestListener(request, listener);

        Memento memento = httpParser.prepareToParse();
        httpParser.parse(memento, dataGen.wrapByteBuffer(bytesWritten));
        List<HttpPayload> parsedMessages = memento.getParsedMessages();
        DataWrapper leftOverData = memento.getLeftOverData();

        // Check that we got an approved upgrade
        Assert.assertEquals(parsedMessages.size(), 1);
        Assert.assertTrue(HttpResponse.class.isInstance(parsedMessages.get(0)));
        HttpResponse responseGot = (HttpResponse) parsedMessages.get(0);
        Assert.assertEquals(responseGot.getStatusLine().getStatus().getKnownStatus(), KnownStatusCode.HTTP_101_SWITCHING_PROTOCOLS);

        UnmarshalState result = http2Parser.prepareToUnmarshal(4096, 4096);
        // Check that we got a settings frame, a headers frame, and a data frame
        result = http2Parser.unmarshal(result, leftOverData, Integer.MAX_VALUE);
        List<Http2Msg> frames = result.getParsedFrames();

        Assert.assertEquals(3, frames.size());
        Assert.assertTrue(SettingsFrame.class.isInstance(frames.get(0)));
        Assert.assertTrue(Http2Headers.class.isInstance(frames.get(1)));
        Assert.assertTrue(DataFrame.class.isInstance(frames.get(2)));
    }

    private void http2ResponseWithData(RequestListenerForTest listener) throws InterruptedException, ExecutionException {
        HttpRequest request = Requests.createRequest(KnownHttpMethod.GET, "/");
        request.addHeader(new Header(KnownHeaderName.UPGRADE, "h2c"));
        request.addHeader(new Header(KnownHeaderName.CONNECTION, "Upgrade, HTTP2-Settings"));
        String base64 = http2Parser.marshalSettingsPayload(settingsFrame.getSettings());

        request.addHeader(new Header(KnownHeaderName.HTTP2_SETTINGS, base64 + " "));

        ByteBuffer bytesWritten = processRequestWithRequestListener(request, listener);

        Memento memento = httpParser.prepareToParse();
        httpParser.parse(memento, dataGen.wrapByteBuffer(bytesWritten));
        List<HttpPayload> parsedMessages = memento.getParsedMessages();
        DataWrapper leftOverData = memento.getLeftOverData();

        // Check that we got an approved upgrade
        Assert.assertEquals(parsedMessages.size(), 1);
        Assert.assertTrue(HttpResponse.class.isInstance(parsedMessages.get(0)));
        HttpResponse responseGot = (HttpResponse) parsedMessages.get(0);
        Assert.assertEquals(responseGot.getStatusLine().getStatus().getKnownStatus(), KnownStatusCode.HTTP_101_SWITCHING_PROTOCOLS);

        UnmarshalState result = http2Parser.prepareToUnmarshal(4096, 4096);
        // Check that we got a settings frame, a headers frame, and a data frame
        result = http2Parser.unmarshal(result, leftOverData, Integer.MAX_VALUE);
        List<Http2Msg> frames = result.getParsedFrames();

        Assert.assertEquals(3, frames.size());
        Assert.assertTrue(SettingsFrame.class.isInstance(frames.get(0)));
        Assert.assertTrue(Http2Headers.class.isInstance(frames.get(1)));
        Assert.assertTrue(DataFrame.class.isInstance(frames.get(2)));

        Assert.assertArrayEquals(((DataFrame) frames.get(2)).getData().createByteArray(), blahblah.getBytes());
    }

    private void http2WithPushPromise(RequestListenerForTest listener) throws InterruptedException, ExecutionException {


        HttpRequest request = Requests.createRequest(KnownHttpMethod.GET, "/");
        request.addHeader(new Header(KnownHeaderName.UPGRADE, "h2c"));
        request.addHeader(new Header(KnownHeaderName.CONNECTION, "Upgrade, HTTP2-Settings"));
        String base64 = http2Parser.marshalSettingsPayload(settingsFrame.getSettings());

        request.addHeader(new Header(KnownHeaderName.HTTP2_SETTINGS, base64 + " "));

        ByteBuffer bytesWritten = processRequestWithRequestListener(request, listener);

        Memento memento = httpParser.prepareToParse();
        httpParser.parse(memento, dataGen.wrapByteBuffer(bytesWritten));
        List<HttpPayload> parsedMessages = memento.getParsedMessages();
        DataWrapper leftOverData = memento.getLeftOverData();

        // Check that we got an approved upgrade
        Assert.assertEquals(parsedMessages.size(), 1);
        Assert.assertTrue(HttpResponse.class.isInstance(parsedMessages.get(0)));
        HttpResponse responseGot = (HttpResponse) parsedMessages.get(0);
        Assert.assertEquals(responseGot.getStatusLine().getStatus().getKnownStatus(), KnownStatusCode.HTTP_101_SWITCHING_PROTOCOLS);

        UnmarshalState result = http2Parser.prepareToUnmarshal(4096, 4096);
        // Check that we got a settings frame, a headers frame, and a data frame, then a push promise frame
        // then a headers then a data frame
        result = http2Parser.unmarshal(result, leftOverData, Integer.MAX_VALUE);
        List<Http2Msg> frames = result.getParsedFrames();

        Assert.assertEquals(6, frames.size());
        Assert.assertTrue(SettingsFrame.class.isInstance(frames.get(0)));
        Assert.assertTrue(Http2Headers.class.isInstance(frames.get(1)));
        Assert.assertTrue(DataFrame.class.isInstance(frames.get(2)));
        Assert.assertTrue(Http2Push.class.isInstance(frames.get(3)));
        Assert.assertTrue(Http2Headers.class.isInstance(frames.get(4)));
        Assert.assertTrue(DataFrame.class.isInstance(frames.get(5)));
    }

    @Test
    public void testSimpleHttp11Request() throws InterruptedException, ExecutionException {
        HttpResponse response = Responses.createResponse(KnownStatusCode.HTTP_200_OK, dataGen.emptyWrapper());
        RequestListenerForTest listenerChunked = new RequestListenerForTestWithResponses(response, true);
        simpleHttp11RequestWithListener(listenerChunked);

        RequestListenerForTest listenerNotChunked = new RequestListenerForTestWithResponses(response, false);
        simpleHttp11RequestWithListener(listenerNotChunked);
    }

    @Test
    public void testUpgradeHttp2Request() throws InterruptedException, ExecutionException {
        HttpResponse response = Responses.createResponse(KnownStatusCode.HTTP_200_OK, dataGen.emptyWrapper());
        RequestListenerForTest listenerChunked = new RequestListenerForTestWithResponses(response, true);
        upgradeHttp2RequestWithListener(listenerChunked);

        RequestListenerForTest listenerNotChunked = new RequestListenerForTestWithResponses(response, false);
        upgradeHttp2RequestWithListener(listenerNotChunked);
    }

    @Test
    public void testHttp2ResponseWithData() throws InterruptedException, ExecutionException {
        HttpResponse response = Responses.createResponse(KnownStatusCode.HTTP_200_OK, dataGen.wrapByteArray(blahblah.getBytes()));
        RequestListenerForTest listenerChunked = new RequestListenerForTestWithResponses(response, true);
        http2ResponseWithData(listenerChunked);

        RequestListenerForTest listenerNotChunked = new RequestListenerForTestWithResponses(response, false);
        http2ResponseWithData(listenerNotChunked);
    }

    @Test
    public void testHttp2WithPushPromiseResponses() throws InterruptedException, ExecutionException {
        HttpResponse response = Responses.createResponse(KnownStatusCode.HTTP_200_OK, dataGen.wrapByteArray(blahblah.getBytes()));
        List<HttpResponse> responses = new ArrayList<>();
        responses.add(response);
        responses.add(response);

        RequestListenerForTest listenerChunked = new RequestListenerForTestWithResponses(responses, true);
        http2WithPushPromise(listenerChunked);

        RequestListenerForTest listenerNotChunked = new RequestListenerForTestWithResponses(responses, false);
        http2WithPushPromise(listenerNotChunked);
    }


}
