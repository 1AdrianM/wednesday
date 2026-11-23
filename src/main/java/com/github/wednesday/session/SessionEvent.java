package com.github.wednesday.session;

import com.github.wednesday.common.AudioChunk;

import java.util.List;

/** What a session tells its client. The gateway serializes these; it adds no meaning. */
public sealed interface SessionEvent {

    /**
     * Session opened. {@code targetMillis} is the first-response latency target;
     * {@code outputSampleRate} is the rate of {@link Audio} chunks (0 for a text-only session).
     */
    record Ready(String sessionId, String model, int contextTurns, long targetMillis, int outputSampleRate)
            implements SessionEvent {
    }

    /** The session moved to {@code state}. */
    record StateChanged(SessionState state) implements SessionEvent {
    }

    /** What the user is saying: partial guesses while listening, then one final text. */
    record Transcript(String text, boolean isFinal) implements SessionEvent {
    }

    /** A piece of Wednesday's reply, in order. */
    record Token(String text) implements SessionEvent {
    }

    /** Wednesday's voice: 16-bit mono PCM at the rate announced in {@link Ready}. */
    record Audio(AudioChunk chunk) implements SessionEvent {
    }

    /**
     * A reply finished. Latencies are measured from the end of the user's speech for a voice turn
     * ({@code voice}), or from the moment a typed message arrived; -1 when the stage never happened.
     */
    record TurnDone(int turn, boolean voice, long firstTokenMillis, long firstAudioMillis, long totalMillis)
            implements SessionEvent {
    }

    /** Latency rows for every turn so far, plus p50/p95 of first-token and first-audio time. */
    record Report(List<TurnDone> turns, Long p50FirstTokenMillis, Long p95FirstTokenMillis,
                  Long p50FirstAudioMillis, Long p95FirstAudioMillis) implements SessionEvent {
    }

    /** Conversation context was cleared. */
    record Reset() implements SessionEvent {
    }

    /** Something the user should know, phrased for them. */
    record Error(String message) implements SessionEvent {
    }
}
