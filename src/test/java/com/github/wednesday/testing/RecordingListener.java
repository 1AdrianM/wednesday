package com.github.wednesday.testing;

import com.github.wednesday.common.StreamListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Test listener that records every callback and lets tests wait for the end of a stream. */
public final class RecordingListener<E> implements StreamListener<E> {

    private final List<E> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger completions = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final Consumer<E> onEach;

    public RecordingListener() {
        this(e -> { });
    }

    /** {@code onEach} runs for every event after it is recorded. */
    public RecordingListener(Consumer<E> onEach) {
        this.onEach = onEach;
    }

    @Override
    public void onEvent(E event) {
        events.add(event);
        onEach.accept(event);
    }

    @Override
    public void onError(Throwable t) {
        error.set(t);
        errors.incrementAndGet();
        terminated.countDown();
    }

    @Override
    public void onComplete() {
        completions.incrementAndGet();
        terminated.countDown();
    }

    /** Waits for onComplete/onError; returns false on timeout. */
    public boolean awaitTermination(Duration timeout) {
        try {
            return terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public List<E> events() {
        return new ArrayList<>(events);
    }

    public int completions() {
        return completions.get();
    }

    public int errors() {
        return errors.get();
    }

    public Throwable error() {
        return error.get();
    }
}
