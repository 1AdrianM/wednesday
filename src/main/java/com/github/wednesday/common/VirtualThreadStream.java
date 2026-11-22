package com.github.wednesday.common;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs a stream producer on its own virtual thread and enforces the {@link StreamListener}
 * contract: ordered delivery, exactly one terminal callback, and silence after {@link #cancel()}.
 */
public final class VirtualThreadStream<E> implements StreamHandle {

    /** Producer body. Emit events through the emitter; return normally to complete, throw to fail. */
    @FunctionalInterface
    public interface Body<E> {
        void run(Emitter<E> emitter) throws Exception;
    }

    /** Given to the producer body. */
    public interface Emitter<E> {
        /** Delivers an event unless the stream was cancelled. */
        void emit(E event);

        boolean isCancelled();

        /** Sleeps unless cancelled; returns early (with the interrupt flag set) when cancelled. */
        void sleep(Duration duration);
    }

    private final StreamListener<E> listener;
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private volatile boolean finished;
    private volatile Thread thread;

    private VirtualThreadStream(StreamListener<E> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public static <E> VirtualThreadStream<E> start(String name, StreamListener<E> listener, Body<E> body) {
        Objects.requireNonNull(body, "body");
        VirtualThreadStream<E> stream = new VirtualThreadStream<>(listener);
        Map<String, Object> logContext = SessionLogContext.capture();
        stream.thread = Thread.ofVirtual().name(name).unstarted(() -> {
            SessionLogContext.restore(logContext);
            stream.runBody(body);
        });
        stream.thread.start();
        return stream;
    }

    private void runBody(Body<E> body) {
        Emitter<E> emitter = new Emitter<>() {
            @Override
            public void emit(E event) {
                deliver(() -> listener.onEvent(event));
            }

            @Override
            public boolean isCancelled() {
                return finished;
            }

            @Override
            public void sleep(Duration duration) {
                if (finished) {
                    return;
                }
                try {
                    Thread.sleep(duration);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            body.run(emitter);
            finish(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(finished ? null : e);
        } catch (Exception | Error e) {
            finish(e);
        }
    }

    private void deliver(Runnable callback) {
        deliveryLock.lock();
        try {
            if (!finished) {
                callback.run();
            }
        } finally {
            deliveryLock.unlock();
        }
    }

    private void finish(Throwable error) {
        deliveryLock.lock();
        try {
            if (finished) {
                return;
            }
            finished = true;
            if (error == null) {
                listener.onComplete();
            } else {
                listener.onError(error);
            }
        } finally {
            deliveryLock.unlock();
            if (error == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(error);
            }
        }
    }

    @Override
    public void cancel() {
        deliveryLock.lock();
        try {
            if (finished) {
                return;
            }
            finished = true;
        } finally {
            deliveryLock.unlock();
        }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
        completion.complete(null);
    }

    @Override
    public boolean isActive() {
        return !finished;
    }

    @Override
    public CompletionStage<Void> completion() {
        return completion;
    }
}
