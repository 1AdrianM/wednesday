package com.github.wednesday.common;

import java.util.concurrent.CompletionStage;

/** Control handle for a running stream. */
public interface StreamHandle {

    /**
     * Stops the stream as soon as possible. Idempotent. Once this returns, the listener receives no
     * further callbacks.
     */
    void cancel();

    /** True until the stream completes, fails or is cancelled. */
    boolean isActive();

    /**
     * Completes normally when the stream completes or is cancelled, exceptionally when it fails.
     */
    CompletionStage<Void> completion();
}
