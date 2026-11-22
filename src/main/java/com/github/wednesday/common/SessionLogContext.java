package com.github.wednesday.common;

import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Puts the session id into the logging MDC (key {@value #MDC_KEY}) for the current thread.
 * The console log format prints it on every line. Use with try-with-resources.
 */
public final class SessionLogContext {

    public static final String MDC_KEY = "sessionId";

    private SessionLogContext() {
    }

    /** Scope that restores the previous MDC value when closed. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Scope open(SessionId id) {
        Object previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id.value());
        return () -> {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        };
    }

    /** Snapshot of the current thread's MDC, to hand over to another thread. */
    static Map<String, Object> capture() {
        Map<String, Object> map = MDC.getMap();
        return map == null ? Map.of() : new HashMap<>(map);
    }

    /** Installs a snapshot taken with {@link #capture()} on the current thread. */
    static void restore(Map<String, Object> snapshot) {
        MDC.clear();
        snapshot.forEach(MDC::put);
    }
}
