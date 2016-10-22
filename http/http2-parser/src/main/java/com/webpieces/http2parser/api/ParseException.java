package com.webpieces.http2parser.api;

import com.webpieces.http2parser.api.dto.Http2ErrorCode;

public class ParseException extends RuntimeException {

    private static final long serialVersionUID = -2704718008204232741L;
    private Http2ErrorCode errorCode;
    private int streamId = 0x0;
    private boolean connectionLevel = false;

    public ParseException() {
        super();
    }

    public ParseException(Http2ErrorCode errorCode, int streamId, boolean connectionLevel) {
        this.errorCode = errorCode;
        this.streamId = streamId;
        this.connectionLevel = connectionLevel;
    }

    public ParseException(Http2ErrorCode errorCode, int streamId) {
        super();
        this.errorCode = errorCode;
        this.streamId = streamId;
    }

    public ParseException(Http2ErrorCode errorCode) {
        this.errorCode = errorCode;
        this.connectionLevel = true;
    }

    public Http2ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getStreamId() {
        return streamId;
    }

    public boolean hasStream() {
        return streamId == 0x0;
    }

    public boolean isConnectionLevel() {
        return connectionLevel;
    }
}
