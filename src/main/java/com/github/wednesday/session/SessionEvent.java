package com.github.wednesday.session;

import java.util.List;

/** What a chat session tells its client. The gateway serializes these; it adds no meaning. */
public sealed interface SessionEvent {

    /** Session opened. {@code targetMillis} is the first-response latency target. */
    record Ready(String sessionId, String model, int contextTurns, long targetMillis) implements SessionEvent {
    }

    /** Coarse session state; the full state machine arrives in Phase 3. */
    record StateChanged(State state) implements SessionEvent {
    }

    /** A piece of Wednesday's reply, in order. */
    record Token(String text) implements SessionEvent {
    }

    /** A reply finished. Latencies are measured from the moment the message was received. */
    record TurnDone(int turn, long firstTokenMillis, long totalMillis) implements SessionEvent {
    }

    /** Latency rows for every turn so far, plus p50/p95 of first-token time. */
    record Report(List<TurnDone> turns, Long p50FirstTokenMillis, Long p95FirstTokenMillis) implements SessionEvent {
    }

    /** Conversation context was cleared. */
    record Reset() implements SessionEvent {
    }

    /** Something the user should know, phrased for them. */
    record Error(String message) implements SessionEvent {
    }

    enum State { IDLE, THINKING }
}
