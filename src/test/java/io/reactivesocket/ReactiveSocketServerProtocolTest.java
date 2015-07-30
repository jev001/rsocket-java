/**
 * Copyright 2015 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivesocket;

import static org.junit.Assert.*;
import static rx.Observable.*;
import static rx.RxReactiveStreams.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.reactivestreams.Subscription;

import rx.Observable;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;
import rx.subjects.ReplaySubject;

public class ReactiveSocketServerProtocolTest {

    @Test
    public void testRequestResponseSuccess() {
        ReactiveSocketServerProtocol p = ReactiveSocketServerProtocol.create(RequestHandler.create(
                request -> toPublisher(just(request + " world")),
                null, null, null));

        TestConnection conn = establishConnection(p);
        ReplaySubject<Frame> cachedResponses = captureResponses(conn);

        // perform a request/response
        conn.toInput.onNext(Frame.from(1, FrameType.REQUEST_RESPONSE, "hello"));

        // TODO do we want to receive 2 frames, or just a single NEXT_COMPLETE?
        assertEquals(2, cachedResponses.getValues().length);// 1 onNext + 1 onCompleted
        List<Frame> frames = cachedResponses.take(2).toList().toBlocking().first();

        // assert
        Frame first = frames.get(0);
        assertEquals(1, first.getStreamId());
        assertEquals(FrameType.NEXT, first.getMessageType());
        assertEquals("hello world", first.getMessage());

        Frame second = frames.get(1);
        assertEquals(1, second.getStreamId());
        assertEquals(FrameType.COMPLETE, second.getMessageType());
        assertEquals("", second.getMessage());
    }

    @Test
    public void testRequestResponseError() {
        ReactiveSocketServerProtocol p = ReactiveSocketServerProtocol.create(RequestHandler.create(
                request -> toPublisher(Observable.<String> error(new Exception("Request Not Found"))),
                null, null, null));

        TestConnection conn = establishConnection(p);
        Observable<Frame> cachedResponses = captureResponses(conn);

        // perform a request/response
        conn.toInput.onNext(Frame.from(1, FrameType.REQUEST_RESPONSE, "hello"));

        // assert
        Frame first = cachedResponses.toBlocking().first();
        assertEquals(1, first.getStreamId());
        assertEquals(FrameType.ERROR, first.getMessageType());
        assertEquals("Request Not Found", first.getMessage());
    }

    @Test
    public void testRequestResponseCancel() {
        AtomicBoolean unsubscribed = new AtomicBoolean();
        Observable<String> delayed = never()
                .cast(String.class)
                .doOnUnsubscribe(() -> unsubscribed.set(true));

        ReactiveSocketServerProtocol p = ReactiveSocketServerProtocol.create(RequestHandler.create(
                request -> toPublisher(delayed),
                null, null, null));

        TestConnection conn = establishConnection(p);
        ReplaySubject<Frame> cachedResponses = captureResponses(conn);

        // perform a request/response
        conn.toInput.onNext(Frame.from(1, FrameType.REQUEST_RESPONSE, "hello"));
        // assert no response
        assertFalse(cachedResponses.hasAnyValue());
        // unsubscribe
        assertFalse(unsubscribed.get());
        conn.toInput.onNext(Frame.from(1, FrameType.CANCEL, ""));
        assertTrue(unsubscribed.get());
    }

    @Test
    public void testRequestStreamSuccess() {
        ReactiveSocketServerProtocol p = ReactiveSocketServerProtocol.create(RequestHandler.create(
                null,
                request -> toPublisher(range(Integer.parseInt(request), 10).map(i -> i + "!")),
                null, null));

        TestConnection conn = establishConnection(p);
        ReplaySubject<Frame> cachedResponses = captureResponses(conn);

        // perform a request/response
        conn.toInput.onNext(Frame.from(1, FrameType.REQUEST_STREAM, "10"));

        // assert
        assertEquals(11, cachedResponses.getValues().length);// 10 onNext + 1 onCompleted
        List<Frame> frames = cachedResponses.take(11).toList().toBlocking().first();

        // 10 onNext frames
        for (int i = 0; i < 10; i++) {
            assertEquals(1, frames.get(i).getStreamId());
            assertEquals(FrameType.NEXT, frames.get(i).getMessageType());
            assertEquals((i + 10) + "!", frames.get(i).getMessage());
        }

        // last message is a COMPLETE
        assertEquals(1, frames.get(10).getStreamId());
        assertEquals(FrameType.COMPLETE, frames.get(10).getMessageType());
        assertEquals("", frames.get(10).getMessage());
    }

    @Test
    public void testRequestStreamError() {
        ReactiveSocketServerProtocol p = ReactiveSocketServerProtocol.create(RequestHandler.create(
                null,
                request -> toPublisher(range(Integer.parseInt(request), 3)
                        .map(i -> i + "!")
                        .concatWith(error(new Exception("Error Occurred!")))),
                null, null));

        TestConnection conn = establishConnection(p);
        ReplaySubject<Frame> cachedResponses = captureResponses(conn);

        // perform a request/response
        conn.toInput.onNext(Frame.from(1, FrameType.REQUEST_STREAM, "0"));

        // assert
        assertEquals(4, cachedResponses.getValues().length);// 3 onNext + 1 onError
        List<Frame> frames = cachedResponses.take(4).toList().toBlocking().first();

        // 3 onNext frames
        for (int i = 0; i < 3; i++) {
            assertEquals(1, frames.get(i).getStreamId());
            assertEquals(FrameType.NEXT, frames.get(i).getMessageType());
            assertEquals(i + "!", frames.get(i).getMessage());
        }

        // last message is an ERROR
        assertEquals(1, frames.get(3).getStreamId());
        assertEquals(FrameType.ERROR, frames.get(3).getMessageType());
        assertEquals("Error Occurred!", frames.get(3).getMessage());
    }

    @Test
    public void testRequestStreamCancel() {
        TestScheduler ts = Schedulers.test();
        ReactiveSocketServerProtocol p = ReactiveSocketServerProtocol.create(RequestHandler.create(
                null,
                request -> toPublisher(interval(1000, TimeUnit.MILLISECONDS, ts).map(i -> i + "!")),
                null, null));

        TestConnection conn = establishConnection(p);
        ReplaySubject<Frame> cachedResponses = captureResponses(conn);

        // perform a request/response
        conn.toInput.onNext(Frame.from(1, FrameType.REQUEST_STREAM, "/aRequest"));

        // no time has passed, so no values
        assertEquals(0, cachedResponses.getValues().length);
        ts.advanceTimeBy(1000, TimeUnit.MILLISECONDS);
        assertEquals(1, cachedResponses.getValues().length);
        ts.advanceTimeBy(2000, TimeUnit.MILLISECONDS);
        assertEquals(3, cachedResponses.getValues().length);
        // dispose
        conn.toInput.onNext(Frame.from(1, FrameType.CANCEL, ""));
        // still only 1 message
        assertEquals(3, cachedResponses.getValues().length);
        // advance again, nothing should happen
        ts.advanceTimeBy(1000, TimeUnit.MILLISECONDS);
        // should still only have 3 message, no ERROR or COMPLETED
        assertEquals(3, cachedResponses.getValues().length);

        List<Frame> frames = cachedResponses.take(3).toList().toBlocking().first();

        // 3 onNext frames
        for (int i = 0; i < 3; i++) {
            assertEquals(1, frames.get(i).getStreamId());
            assertEquals(FrameType.NEXT, frames.get(i).getMessageType());
            assertEquals(i + "!", frames.get(i).getMessage());
        }
    }

    @Test
    public void testMultiplexedStreams() {
        TestScheduler ts = Schedulers.test();
        ReactiveSocketServerProtocol p = ReactiveSocketServerProtocol.create(RequestHandler.create(
                null,
                request -> toPublisher(interval(1000, TimeUnit.MILLISECONDS, ts).map(i -> i + "_" + request)),
                null, null));

        TestConnection conn = establishConnection(p);
        ReplaySubject<Frame> cachedResponses = captureResponses(conn);

        // perform a request/response
        conn.toInput.onNext(Frame.from(1, FrameType.REQUEST_STREAM, "requestA"));

        // no time has passed, so no values
        assertEquals(0, cachedResponses.getValues().length);
        ts.advanceTimeBy(1000, TimeUnit.MILLISECONDS);
        // we should have 1 message from A
        assertEquals(1, cachedResponses.getValues().length);
        // now request another stream
        conn.toInput.onNext(Frame.from(2, FrameType.REQUEST_STREAM, "requestB"));
        // advance some more
        ts.advanceTimeBy(2000, TimeUnit.MILLISECONDS);
        // should have 3 from A and 2 from B
        assertEquals(5, cachedResponses.getValues().length);
        // dispose A, but leave B
        conn.toInput.onNext(Frame.from(1, FrameType.CANCEL, ""));
        // still same 5 frames
        assertEquals(5, cachedResponses.getValues().length);
        // advance again, should get 2 from B
        ts.advanceTimeBy(2000, TimeUnit.MILLISECONDS);
        assertEquals(7, cachedResponses.getValues().length);

        List<Frame> frames = cachedResponses.take(7).toList().toBlocking().first();

        // A frames (positions 0, 1, 3) incrementing 0, 1, 2
        assertEquals(1, frames.get(0).getStreamId());
        assertEquals("0_requestA", frames.get(0).getMessage());
        assertEquals(1, frames.get(1).getStreamId());
        assertEquals("1_requestA", frames.get(1).getMessage());
        assertEquals(1, frames.get(3).getStreamId());
        assertEquals("2_requestA", frames.get(3).getMessage());

        // B frames (positions 2, 4, 5, 6) incrementing 0, 1, 2, 3
        assertEquals(2, frames.get(2).getStreamId());
        assertEquals("0_requestB", frames.get(2).getMessage());
        assertEquals(2, frames.get(4).getStreamId());
        assertEquals("1_requestB", frames.get(4).getMessage());
        assertEquals(2, frames.get(5).getStreamId());
        assertEquals("2_requestB", frames.get(5).getMessage());
        assertEquals(2, frames.get(6).getStreamId());
        assertEquals("3_requestB", frames.get(6).getMessage());
    }

    /* **********************************************************************************************/

    private ReplaySubject<Frame> captureResponses(TestConnection conn) {
        // capture all responses to client
        ReplaySubject<Frame> rs = ReplaySubject.create();
        conn.writes.subscribe(rs);
        return rs;
    }

    private TestConnection establishConnection(ReactiveSocketServerProtocol p) {
        TestConnection conn = new TestConnection();
        p.acceptConnection(conn).subscribe(PROTOCOL_SUBSCRIBER);
        return conn;
    }

    private org.reactivestreams.Subscriber<Void> PROTOCOL_SUBSCRIBER = new org.reactivestreams.Subscriber<Void>() {

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Void t) {

        }

        @Override
        public void onError(Throwable t) {
            t.printStackTrace();
        }

        @Override
        public void onComplete() {

        }

    };

}