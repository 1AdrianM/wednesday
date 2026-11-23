package com.github.wednesday.session;

import org.jboss.logging.Logger;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static com.github.wednesday.session.SessionState.IDLE;
import static com.github.wednesday.session.SessionState.LISTENING;
import static com.github.wednesday.session.SessionState.SPEAKING;
import static com.github.wednesday.session.SessionState.THINKING;

/**
 * The session's state with an explicit table of allowed transitions. An illegal move is rejected,
 * logged, and leaves the state unchanged. Thread-safe; the listener runs inside the lock, so it
 * sees changes in order and must return quickly.
 */
public final class SessionStateMachine {

    private static final Logger LOG = Logger.getLogger(SessionStateMachine.class);

    private static final Map<SessionState, Set<SessionState>> ALLOWED = new EnumMap<>(SessionState.class);

    static {
        ALLOWED.put(IDLE, EnumSet.of(LISTENING, THINKING));
        ALLOWED.put(LISTENING, EnumSet.of(IDLE, THINKING));
        ALLOWED.put(THINKING, EnumSet.of(IDLE, SPEAKING));
        ALLOWED.put(SPEAKING, EnumSet.of(IDLE));
    }

    private final Consumer<SessionState> onChange;
    private SessionState current;

    public SessionStateMachine(Consumer<SessionState> onChange) {
        this(IDLE, onChange);
    }

    SessionStateMachine(SessionState initial, Consumer<SessionState> onChange) {
        this.current = Objects.requireNonNull(initial);
        this.onChange = Objects.requireNonNull(onChange);
    }

    public static boolean allowed(SessionState from, SessionState to) {
        return ALLOWED.get(from).contains(to);
    }

    /** Moves to {@code to} if the table allows it; returns whether the move happened. */
    public synchronized boolean moveTo(SessionState to) {
        if (!allowed(current, to)) {
            LOG.warnf("illegal state transition %s -> %s rejected", current, to);
            return false;
        }
        current = to;
        onChange.accept(to);
        return true;
    }

    public synchronized SessionState current() {
        return current;
    }
}
