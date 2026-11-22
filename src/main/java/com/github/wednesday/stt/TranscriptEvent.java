package com.github.wednesday.stt;

/** Transcript events produced while audio is being recognized. */
public sealed interface TranscriptEvent {

    String text();

    /** Best guess so far for the current utterance; may change. */
    record Partial(String text) implements TranscriptEvent {
    }

    /** Settled text for the utterance; will not change. */
    record Final(String text) implements TranscriptEvent {
    }
}
