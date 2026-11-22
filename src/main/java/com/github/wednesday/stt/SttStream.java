package com.github.wednesday.stt;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.StreamHandle;

/** An open recognition stream: push audio in, receive {@link TranscriptEvent}s on the listener. */
public interface SttStream extends StreamHandle {

    /** Sends the next block of audio, in capture order. */
    void send(AudioChunk audio);

    /** Signals that no more audio will be sent; the stream emits any final transcript and completes. */
    void endOfAudio();
}
