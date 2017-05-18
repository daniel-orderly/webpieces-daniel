package org.webpieces.webserver.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;
import org.webpieces.data.api.DataWrapperGeneratorFactory;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

import com.webpieces.http2engine.api.StreamWriter;
import com.webpieces.http2parser.api.dto.DataFrame;

public class ChunkedStream extends OutputStream {

	private static final Logger log = LoggerFactory.getLogger(ChunkedStream.class);
	private static final DataWrapperGenerator wrapperFactory = DataWrapperGeneratorFactory.createDataWrapperGenerator();

	private ByteArrayOutputStream str = new ByteArrayOutputStream();

	private StreamWriter responseWriter;
	private int size;
	private String type;

	public ChunkedStream(StreamWriter responseWriter, int size, boolean compressed) {
		this.responseWriter = responseWriter;
		this.size = size;
		this.str = new ByteArrayOutputStream(size);
		if(compressed)
			this.type = "compressed";
		else
			this.type = "not compressed";
	}

	@Override
	public void write(int b) throws IOException {
		str.write(b);
		
		if(str.size() >= size) {
			writeDataOut();
		}
	}

	@Override
	public void flush() {
		if(str.size() > 0) {
			writeDataOut();
		}
	}
	
	@Override
	public void close() {
		if(str.size() > 0) {
			writeDataOut();
		}
		
		DataFrame frame = new DataFrame();
		responseWriter.send(frame);
	}
	
	private void writeDataOut() {
		byte[] data = str.toByteArray();
		str = new ByteArrayOutputStream();
		DataWrapper body = wrapperFactory.wrapByteArray(data);
		log.info("writing "+type+" data="+body.getReadableSize()+" to socket="+responseWriter);
		
		DataFrame frame = new DataFrame();
		frame.setEndOfStream(false);
		frame.setData(body);
		responseWriter.send(frame);
	}

}
