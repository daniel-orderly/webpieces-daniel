package com.webpieces.http2engine.impl.shared;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.webpieces.javasm.api.Memento;
import org.webpieces.javasm.api.NoTransitionListener;
import org.webpieces.javasm.api.State;
import org.webpieces.javasm.api.StateMachine;
import org.webpieces.javasm.api.StateMachineFactory;

import com.webpieces.hpack.api.dto.Http2Headers;
import com.webpieces.hpack.api.dto.Http2Push;
import com.webpieces.http2engine.impl.shared.Http2Event.Http2SendRecieve;
import com.webpieces.http2parser.api.dto.DataFrame;
import com.webpieces.http2parser.api.dto.lib.PartialStream;

public class Level4ClientStateMachine {

	private StateMachine stateMachine;
	private State idleState;
	private Level5RemoteFlowControl remoteFlowControl;
	private Level5LocalFlowControl localFlowControl;
	private State closed;
	
	public Level4ClientStateMachine(String id,
			Executor backUpPool,
			Level5RemoteFlowControl remoteFlowControl, 
			Level5LocalFlowControl localFlowControl
	) {
		this.remoteFlowControl = remoteFlowControl;
		this.localFlowControl = localFlowControl;
		StateMachineFactory factory = StateMachineFactory.createFactory();
		stateMachine = factory.createStateMachine(backUpPool, id);

		idleState = stateMachine.createState("idle");
		State openState = stateMachine.createState("Open");
		State reservedState = stateMachine.createState("Reserved(remote)");
		State halfClosedLocal = stateMachine.createState("Half Closed(local)");
		closed = stateMachine.createState("closed");
	
		NoTransitionImpl failIfNoTransition = new NoTransitionImpl();
		idleState.addNoTransitionListener(failIfNoTransition);
		openState.addNoTransitionListener(failIfNoTransition);
		reservedState.addNoTransitionListener(failIfNoTransition);
		halfClosedLocal.addNoTransitionListener(failIfNoTransition);
		closed.addNoTransitionListener(failIfNoTransition);
		
		Http2Event sentHeaders = new Http2Event(Http2SendRecieve.SEND, Http2PayloadType.HEADERS);
		Http2Event recvPushPromise = new Http2Event(Http2SendRecieve.RECEIVE, Http2PayloadType.PUSH_PROMISE);
		Http2Event sentEndStreamFlag = new Http2Event(Http2SendRecieve.SEND, Http2PayloadType.END_STREAM_FLAG);
		Http2Event sentResetStream = new Http2Event(Http2SendRecieve.SEND, Http2PayloadType.RESET_STREAM);
		Http2Event recvHeaders = new Http2Event(Http2SendRecieve.RECEIVE, Http2PayloadType.HEADERS);
		Http2Event receivedResetStream = new Http2Event(Http2SendRecieve.RECEIVE, Http2PayloadType.RESET_STREAM);
		Http2Event recvEndStreamFlag = new Http2Event(Http2SendRecieve.RECEIVE, Http2PayloadType.END_STREAM_FLAG);
		Http2Event dataSend = new Http2Event(Http2SendRecieve.SEND, Http2PayloadType.DATA);
		Http2Event dataRecv = new Http2Event(Http2SendRecieve.RECEIVE, Http2PayloadType.DATA);
		
		stateMachine.createTransition(idleState, openState, sentHeaders);
		stateMachine.createTransition(idleState, reservedState, recvPushPromise);
		stateMachine.createTransition(openState, halfClosedLocal, sentEndStreamFlag);
		stateMachine.createTransition(openState, closed, sentResetStream, receivedResetStream);		
		stateMachine.createTransition(reservedState, halfClosedLocal, recvHeaders);
		stateMachine.createTransition(reservedState, closed, sentResetStream, receivedResetStream);
		stateMachine.createTransition(halfClosedLocal, closed, recvEndStreamFlag, receivedResetStream, sentResetStream);
		
		//extra transitions defined such that we can catch unknown transitions
		stateMachine.createTransition(openState, openState, dataSend, sentHeaders);

		stateMachine.createTransition(halfClosedLocal, halfClosedLocal, recvHeaders, dataRecv);
	}

	private static class NoTransitionImpl implements NoTransitionListener {
		@Override
		public void noTransitionFromEvent(State state, Object event) {
			throw new RuntimeException("No transition defined on statemachine for event="+event+" when in state="+state);
		}
	}
	
	public CompletableFuture<Void> fireToSocket(Stream stream, PartialStream payload) {
		Memento state = stream.getCurrentState();
		Http2PayloadType payloadType = translate(payload);
		Http2Event event = new Http2Event(Http2SendRecieve.SEND, payloadType);

		CompletableFuture<State> result = stateMachine.fireEvent(state, event);
		return result.thenCompose( s -> {
					//sometimes a single frame has two events :( per http2 spec
					if(payload.isEndOfStream())
						return stateMachine.fireEvent(state, new Http2Event(Http2SendRecieve.SEND, Http2PayloadType.END_STREAM_FLAG));			
					return CompletableFuture.completedFuture(s);
				}).thenCompose( s -> 
					//if no exceptions occurred, send it on to flow control layer
					remoteFlowControl.sendPayloadToSocket(stream, payload)
				);
	}
	
	public Memento createStateMachine(String streamId) {
		return stateMachine.createMementoFromState("stream"+streamId, idleState);
	}
	
	private Http2PayloadType translate(PartialStream payload) {
		if(payload instanceof Http2Headers) {
			return Http2PayloadType.HEADERS;
		} else if(payload instanceof DataFrame) {
			return Http2PayloadType.DATA;
		} else if(payload instanceof Http2Push) {
			return Http2PayloadType.PUSH_PROMISE;
		} else
			throw new IllegalArgumentException("unknown payload type for payload="+payload);
	}

	public void fireToClient(Stream stream, PartialStream payload, Runnable possiblyModifyState) {
		Memento currentState = stream.getCurrentState();
		Http2PayloadType payloadType = translate(payload);
		Http2Event event = new Http2Event(Http2SendRecieve.RECEIVE, payloadType);
		
		CompletableFuture<State> result = stateMachine.fireEvent(currentState, event);
		result.thenCompose( s -> {
			if(payload.isEndOfStream())
				return stateMachine.fireEvent(currentState, new Http2Event(Http2SendRecieve.RECEIVE, Http2PayloadType.END_STREAM_FLAG)); //validates state transition is ok
			return CompletableFuture.completedFuture(s);
		}).thenApply( s -> {
			//modifying the stream state should be done BEFORE firing to client as if the stream is closed
			//then this will prevent windowUpdateFrame with increment being sent to a closed stream
			if(possiblyModifyState != null)
				possiblyModifyState.run();
			
			localFlowControl.fireToClient(stream, payload);			
			
			return s;
		});

	}

	public boolean isInClosedState(Stream stream) {
		State currentState = stream.getCurrentState().getCurrentState();
		if(currentState == closed)
			return true;
		return false;
	}

}
