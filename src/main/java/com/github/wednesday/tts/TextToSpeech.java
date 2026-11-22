package com.github.wednesday.tts;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;

/** Streaming speech synthesis: audio chunks arrive as soon as they are produced. */
public interface TextToSpeech {

    StreamHandle synthesize(String text, StreamListener<AudioChunk> listener);
}
