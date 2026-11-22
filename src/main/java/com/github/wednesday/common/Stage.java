package com.github.wednesday.common;

/** Points in a turn that the {@link Timeline} records, in their usual order. */
public enum Stage {
    SPEECH_END,
    TRANSCRIPT_FINAL,
    REQUEST_SENT,
    FIRST_TOKEN,
    FIRST_SEGMENT,
    FIRST_AUDIO,
    LAST_TOKEN,
    LAST_AUDIO
}
