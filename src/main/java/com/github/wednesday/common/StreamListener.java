package com.github.wednesday.common;

/**
 * Receives the events of one stream. Callbacks run on the stream's background thread and must
 * return quickly. A stream that is not cancelled ends with exactly one call to either
 * {@link #onComplete()} or {@link #onError(Throwable)}. After {@link StreamHandle#cancel()} returns,
 * no further callbacks are made.
 */
public interface StreamListener<E> {

    void onEvent(E event);

    void onError(Throwable error);

    void onComplete();
}
