package com.github.wednesday.cli;

import com.github.wednesday.common.AudioChunk;

/** Where the CLI sends Wednesday's voice. */
interface AudioSink extends AutoCloseable {

    /** Queues a chunk for playback; returns immediately. */
    void play(AudioChunk chunk);

    /** Blocks until everything queued so far has been heard. */
    void drain();

    @Override
    void close();
}
