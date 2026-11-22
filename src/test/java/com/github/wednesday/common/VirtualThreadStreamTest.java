package com.github.wednesday.common;

import com.github.wednesday.testing.RecordingListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VirtualThreadStreamTest {

    static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void deliversEventsInOrderThenCompletesOnce() throws Exception {
        var listener = new RecordingListener<String>();
        var stream = VirtualThreadStream.<String>start("t", listener, emit -> {
            emit.emit("a");
            emit.emit("b");
            emit.emit("c");
        });

        assertTrue(listener.awaitTermination(WAIT));
        stream.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b", "c"), listener.events());
        assertEquals(1, listener.completions());
        assertEquals(0, listener.errors());
        assertFalse(stream.isActive());
    }

    @Test
    void failureEndsWithOnErrorOnlyAndFailsCompletion() {
        var listener = new RecordingListener<String>();
        var boom = new IllegalStateException("boom");
        var stream = VirtualThreadStream.<String>start("t", listener, emit -> {
            emit.emit("a");
            throw boom;
        });

        assertTrue(listener.awaitTermination(WAIT));
        assertSame(boom, listener.error());
        assertEquals(0, listener.completions());
        assertTrue(stream.completion().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void cancelStopsAllCallbacksIncludingTerminal() throws Exception {
        var firstEvent = new CountDownLatch(1);
        var listener = new RecordingListener<Integer>(e -> firstEvent.countDown());
        var stream = VirtualThreadStream.<Integer>start("t", listener, emit -> {
            for (int i = 0; ; i++) {
                emit.emit(i);
                emit.sleep(Duration.ofMillis(5));
                if (emit.isCancelled()) {
                    return;
                }
            }
        });

        assertTrue(firstEvent.await(2, TimeUnit.SECONDS));
        stream.cancel();
        int seenAtCancel = listener.events().size();
        Thread.sleep(50);

        assertEquals(seenAtCancel, listener.events().size(), "no events after cancel");
        assertEquals(0, listener.completions());
        assertEquals(0, listener.errors());
        assertFalse(stream.isActive());
        stream.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void cancelInterruptsABlockedProducer() throws Exception {
        var producerExited = new CountDownLatch(1);
        var listener = new RecordingListener<String>();
        var stream = VirtualThreadStream.<String>start("t", listener, emit -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } finally {
                producerExited.countDown();
            }
        });

        stream.cancel();
        assertTrue(producerExited.await(1, TimeUnit.SECONDS), "producer thread was interrupted");
        assertEquals(0, listener.errors());
    }

    @Test
    void cancelIsIdempotentAndHarmlessAfterCompletion() {
        var listener = new RecordingListener<String>();
        var stream = VirtualThreadStream.<String>start("t", listener, emit -> emit.emit("x"));
        assertTrue(listener.awaitTermination(WAIT));

        stream.cancel();
        stream.cancel();
        assertEquals(1, listener.completions());
    }

    @Test
    void audioChunkIsImmutableAndNeverPrintsContent() {
        byte[] raw = {1, 2, 3};
        AudioChunk chunk = AudioChunk.of(raw);
        raw[0] = 9;
        chunk.bytes()[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, chunk.bytes());
        assertEquals("AudioChunk[3 bytes]", chunk.toString());
        assertEquals(AudioChunk.of(new byte[] {2, 3}), AudioChunk.of(new byte[] {1, 2, 3}, 1, 2));
    }
}
