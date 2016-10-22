package com.webpieces.http2parser.impl;

import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;
import com.webpieces.http2parser.api.*;
import com.webpieces.http2parser.api.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.data.api.BufferPool;
import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;
import org.webpieces.data.api.DataWrapperGeneratorFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import static com.webpieces.http2parser.api.dto.Http2FrameType.*;

public class Http2ParserImpl implements Http2Parser {
    private final DataWrapperGenerator dataGen = DataWrapperGeneratorFactory.createDataWrapperGenerator();
    private static final Logger log = LoggerFactory.getLogger(Http2ParserImpl.class);

    private final BufferPool bufferPool;
    private final Map<Class<? extends Http2Frame>, FrameMarshaller> dtoToMarshaller = new HashMap<>();

    public Http2ParserImpl(BufferPool bufferPool) {
        this.bufferPool = bufferPool;

        dtoToMarshaller.put(Http2Data.class, new DataMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2Headers.class, new HeadersMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2Priority.class, new PriorityMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2RstStream.class, new RstStreamMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2Settings.class, new SettingsMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2PushPromise.class, new PushPromiseMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2Ping.class, new PingMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2GoAway.class, new GoAwayMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2WindowUpdate.class, new WindowUpdateMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2Continuation.class, new ContinuationMarshaller(bufferPool, dataGen));
    }

    @Override
    public FrameMarshaller getMarshaller(Class<? extends Http2Frame> frameClass) {
        return dtoToMarshaller.get(frameClass);
    }

    @Override
    public DataWrapper prepareToParse() {
        return dataGen.emptyWrapper();
    }

    // includes header length
    private int peekLengthOfFrame(DataWrapper data) {
        ByteBuffer lengthBytes = ByteBuffer.wrap(data.readBytesAt(0, 3));
        int length = lengthBytes.getShort() << 8;
        length |= lengthBytes.get();
        return length + 9; // add 9 bytes for the header
    }

    private Class<? extends Http2Frame> getFrameClassForType(Http2FrameType type) {
        switch (type) {
            case DATA:
                return Http2Data.class;
            case HEADERS:
                return Http2Headers.class;
            case PRIORITY:
                return Http2Priority.class;
            case RST_STREAM:
                return Http2RstStream.class;
            case SETTINGS:
                return Http2Settings.class;
            case PUSH_PROMISE:
                return Http2PushPromise.class;
            case PING:
                return Http2Ping.class;
            case GOAWAY:
                return Http2GoAway.class;
            case WINDOW_UPDATE:
                return Http2WindowUpdate.class;
            case CONTINUATION:
                return Http2Continuation.class;
            default:
                return Http2Data.class; // TODO: change to Optional/None
        }
    }

    private int getLength(DataWrapper data) {
        ByteBuffer headerByteBuffer = bufferPool.nextBuffer(9);
        headerByteBuffer.put(data.readBytesAt(0, 9));
        headerByteBuffer.flip();

        // Get 4 bytes and just drop the rightmost one.
        return headerByteBuffer.getInt() >>> 8;
    }

    private byte getFrameTypeId(DataWrapper data) {
        return data.readByteAt(3);
    }

    private byte getFlagsByte(DataWrapper data) {
        return data.readByteAt(4);
    }

    private int getStreamId(DataWrapper data) {
        ByteBuffer streamIdBuffer = bufferPool.nextBuffer(4);
        streamIdBuffer.put(data.readBytesAt(5, 4));
        streamIdBuffer.flip();

        // Ignore the reserved bit
        return streamIdBuffer.getInt() & 0x7FFFFFFF;
    }

    // ignores what's left over at the end of the datawrapper
    @Override
    public Http2Frame unmarshal(DataWrapper data) {
        int length = getLength(data);
        byte frameTypeId = getFrameTypeId(data);
        byte flagsByte = getFlagsByte(data);
        int streamId = getStreamId(data);
        Http2FrameType frameType = Http2FrameType.fromId(frameTypeId);

        Class<? extends Http2Frame> frameClass = getFrameClassForType(frameType);
        try {
            Http2Frame frame = frameClass.newInstance();
            FrameMarshaller marshaller = dtoToMarshaller.get(frameClass);

            frame.setStreamId(streamId);
            Optional<DataWrapper> maybePayload;

            if (length > 0) {
                List<? extends DataWrapper> splitWrappers = dataGen.split(data, 9);
                DataWrapper payloadPlusMore = splitWrappers.get(1);
                List<? extends DataWrapper> split = dataGen.split(payloadPlusMore, length);
                maybePayload = Optional.of(split.get(0));
            } else {
                maybePayload = Optional.empty();
            }

            marshaller.unmarshalFlagsAndPayload(frame, flagsByte, maybePayload);

            return frame;

        } catch (InstantiationException | IllegalAccessException e) {
            // TODO: deal with exception
            return null; // should reraise in some fashion
        }

    }

    private byte getFrameTypeByte(Http2Frame frame) {
        return frame.getFrameType().getId();
    }

    @Override
    public int getFrameLength(Http2Frame frame) {
        FrameMarshaller marshaller = dtoToMarshaller.get(frame.getClass());
        DataWrapper payload = marshaller.marshalPayload(frame);
        return payload.getReadableSize();
    }

    @Override
    public DataWrapper marshal(Http2Frame frame) {
        FrameMarshaller marshaller = dtoToMarshaller.get(frame.getClass());

        if(marshaller == null)
            return null; //throw here

        ByteBuffer header = ByteBuffer.allocate(9);
        DataWrapper payload = marshaller.marshalPayload(frame);

        int length = payload.getReadableSize();
        header.put((byte) (length >>> 16));
        header.putShort((short) length);

        header.put(getFrameTypeByte(frame));
        header.put(marshaller.marshalFlags(frame));

        // 1 bit reserved, streamId MSB is always 0, see setStreamId()
        header.putInt(frame.getStreamId());
        header.flip();

        return dataGen.chainDataWrappers(dataGen.wrapByteBuffer(header), payload);
    }

    @Override
    public DataWrapper marshal(List<Http2Frame> frames) {
        DataWrapper data = dataGen.emptyWrapper();
        for(Http2Frame frame: frames) {
            data = dataGen.chainDataWrappers(data, marshal(frame));
        }
        return data;
    }

    private static Map<Http2FrameType, Integer> fixedFrameLengthByType = new HashMap<>();
    private static List<Http2FrameType> connectionLevelFrames = new ArrayList<>();

    static {
        fixedFrameLengthByType.put(PRIORITY, 5);
        fixedFrameLengthByType.put(RST_STREAM, 4);
        fixedFrameLengthByType.put(PING, 8);
        fixedFrameLengthByType.put(WINDOW_UPDATE, 4);

        connectionLevelFrames.add(SETTINGS);
        connectionLevelFrames.add(CONTINUATION);
        connectionLevelFrames.add(HEADERS);
        connectionLevelFrames.add(PUSH_PROMISE);
        connectionLevelFrames.add(RST_STREAM);
        connectionLevelFrames.add(WINDOW_UPDATE);
    }


    @Override
    public ParserResult parse(DataWrapper oldData, DataWrapper newData, Decoder decoder, Map<Http2Settings.Parameter, Integer> settings) {
        DataWrapper wrapperToParse;
        List<Http2Frame> frames = new LinkedList<>();
        List<Http2Frame> hasHeaderFragmentList = new LinkedList<>();

        if (oldData.getReadableSize() > 0) {
            wrapperToParse = dataGen.chainDataWrappers(oldData, newData);
        }
        else {
            wrapperToParse = newData;
        }

        DataWrapper wrapperToReturn = wrapperToParse; // we might return moredata if there are header framesn

        // Loop until a return (ack)
        while (true) {
            int lengthOfData = wrapperToParse.getReadableSize();
            if (lengthOfData < 9) {
                // Not even a frame header
                return new ParserResultImpl(frames, wrapperToReturn);
            } else {
                // peek for length, add 9 bytes for the header
                int payloadLength =  getLength(wrapperToParse);
                Http2FrameType frameType = Http2FrameType.fromId(getFrameTypeId(wrapperToParse));
                Integer fixedLengthForType = fixedFrameLengthByType.get(frameType);

                if(payloadLength > settings.get(Http2Settings.Parameter.SETTINGS_MAX_FRAME_SIZE) ||
                        (fixedLengthForType != null && payloadLength != fixedLengthForType) ||
                        (frameType == SETTINGS && payloadLength % 6 != 0)) {
                    int streamId = getStreamId(wrapperToParse);
                    boolean isConnectionLevel = connectionLevelFrames.contains(frameType) || streamId == 0x0;

                    throw new ParseException(Http2ErrorCode.FRAME_SIZE_ERROR, streamId, isConnectionLevel);
                }

                int totalLength = payloadLength + 9;
                if (lengthOfData < totalLength) {
                    // not a whole frame
                    return new ParserResultImpl(frames, wrapperToReturn);
                } else {
                    // parse a single frame, look for more
                    List<? extends DataWrapper> split = dataGen.split(wrapperToParse, totalLength);
                    Http2Frame frame = unmarshal(split.get(0));

                    // If this is a header frame, we have to make sure we get all the header
                    // frames before adding them to our framelist
                    if(Arrays.asList(HEADERS, PUSH_PROMISE, CONTINUATION).contains(frameType)) {
                        if(frameType == CONTINUATION) {
                            if(hasHeaderFragmentList.isEmpty()) {
                                throw new ParseException(Http2ErrorCode.PROTOCOL_ERROR, frame.getStreamId(), true);
                            }
                            if(hasHeaderFragmentList.get(0).getFrameType() == HEADERS && frame.getStreamId() != hasHeaderFragmentList.get(0).getStreamId()) {
                                throw new ParseException(Http2ErrorCode.PROTOCOL_ERROR, frame.getStreamId(), true);
                            }
                            if(hasHeaderFragmentList.get(0).getFrameType() == PUSH_PROMISE && frame.getStreamId() != ((Http2PushPromise) hasHeaderFragmentList.get(0)).getPromisedStreamId()) {
                                throw new ParseException(Http2ErrorCode.PROTOCOL_ERROR, frame.getStreamId(), true);
                            }
                        }
                        hasHeaderFragmentList.add(frame);
                        if(((HasHeaderFragment) frame).isEndHeaders()) {
                            // Now we set the full header list on the first frame and just return that
                            Http2Frame firstFrame = hasHeaderFragmentList.get(0);
                            DataWrapper allSerializedHeaders = dataGen.emptyWrapper();
                            for(Http2Frame iterFrame: hasHeaderFragmentList) {
                                allSerializedHeaders = dataGen.chainDataWrappers(allSerializedHeaders, ((HasHeaderFragment) iterFrame).getHeaderFragment());
                            }
                            ((HasHeaderList) firstFrame).setHeaderList(deserializeHeaders(allSerializedHeaders, decoder));
                            ((HasHeaderFragment) firstFrame).setEndHeaders(true); // fake setting end headers
                            frames.add(firstFrame);

                            hasHeaderFragmentList.clear();
                            wrapperToParse = split.get(1);
                            wrapperToReturn = wrapperToParse;
                        }
                        else
                        {
                            wrapperToParse = split.get(1);
                            // wrapperToReturn stays unchanged because we haven't reached the end of the headers
                        }
                    } else {
                        frames.add(frame);
                        wrapperToParse = split.get(1);
                        wrapperToReturn = wrapperToParse;
                        // I don't think we need to do this because the ack won't be sent so the
                        // other side shouldn't start sending frames under the new settings until
                        // the ack is sent/received.
//                        if(frameType == SETTINGS) {
//                            // If we get a settings frame return immediately without parsing more frames,
//                            // because it might affect the parsing of subsequent frames.
//                            return new ParserResultImpl(frames, wrapperToReturn);
//                        }
                    }
                }
            }
        }
    }

    @Override
    public DataWrapper serializeHeaders(LinkedList<HasHeaderFragment.Header> headers, Encoder encoder, ByteArrayOutputStream out) {
        for (HasHeaderFragment.Header header : headers) {
            try {
                encoder.encodeHeader(
                        out,
                        header.header.toLowerCase().getBytes(),
                        header.value.getBytes(),
                        false);
            } catch (IOException e) {
                // TODO: reraise appropriately
            }
        }
        return dataGen.wrapByteArray(out.toByteArray());
    }

    @Override
    public List<Http2Frame> createHeaderFrames(
            LinkedList<HasHeaderFragment.Header> headers,
            Http2FrameType startingFrameType,
            int streamId,
            Map<Http2Settings.Parameter, Integer> remoteSettings,
            Encoder encoder,
            ByteArrayOutputStream out) {
        List<Http2Frame> headerFrames = new LinkedList<>();

        DataWrapper serializedHeaders = serializeHeaders(headers, encoder, out);
        int maxFrameSize = remoteSettings.get(Http2Settings.Parameter.SETTINGS_MAX_FRAME_SIZE) - 16; // subtract a little to deal with the extra bits on some of the header frame types)
        boolean firstFrame = true;
        boolean lastFrame = false;
        DataWrapper fragment;
        try {
            while (true) {
                if (serializedHeaders.getReadableSize() <= maxFrameSize) {
                    lastFrame = true;
                    fragment = serializedHeaders;
                } else {
                    List<? extends DataWrapper> split = dataGen.split(serializedHeaders, maxFrameSize);
                    fragment = split.get(0);
                    serializedHeaders = split.get(1);
                }

                Http2Frame frame;
                if (firstFrame) {
                    frame = getFrameClassForType(startingFrameType).newInstance();
                    if (frame.getFrameType() == PUSH_PROMISE) {
                        // If push promise the caller will have to set the streamid of the first frame
                        ((Http2PushPromise) frame).setPromisedStreamId(streamId);
                    } else {
                        frame.setStreamId(streamId); // for push promise the first
                    }
                } else {
                    frame = new Http2Continuation();
                    frame.setStreamId(streamId);
                }
                ((HasHeaderFragment) frame).setHeaderFragment(fragment);
                headerFrames.add(frame);

                if (lastFrame) {
                    ((HasHeaderFragment) frame).setEndHeaders(true);
                    break;
                }
                firstFrame = false;
            }
            return headerFrames;
        }
        catch (InstantiationException | IllegalAccessException e) {
            // TODO: deal with exception here
            return null;
        }
    }

    @Override
    public LinkedList<HasHeaderFragment.Header> deserializeHeaders(DataWrapper data, Decoder decoder) {
        LinkedList<HasHeaderFragment.Header> headers = new LinkedList<>();

        byte[] bytes = data.createByteArray();
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        try {
            decoder.decode(in, (name, value, sensitive) ->
                    headers.add(new HasHeaderFragment.Header(new String(name).toLowerCase(), new String(value)))
            );
        } catch (IOException e) {
            // TODO: reraise appropriately here
        }
        decoder.endHeaderBlock();
        return headers;
    }
}
