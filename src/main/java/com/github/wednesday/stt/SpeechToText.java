package com.github.wednesday.stt;

import com.github.wednesday.common.StreamListener;

/** Streaming speech recognition with partial and final transcripts. */
public interface SpeechToText {

    SttStream open(StreamListener<TranscriptEvent> listener);
}
