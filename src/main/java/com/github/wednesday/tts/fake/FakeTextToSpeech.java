package com.github.wednesday.tts.fake;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.PcmFormat;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.tts.TextToSpeech;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic {@link TextToSpeech} for tests: emits a fixed number of silent audio chunks per
 * text, with configurable delays, and records the texts it was asked to speak.
 */
public final class FakeTextToSpeech implements TextToSpeech {

    /** Same layout as the real Gemini voice. */
    public static final PcmFormat FORMAT = new PcmFormat(24_000, 1);

    private final Duration firstChunkDelay;
    private final Duration chunkDelay;
    private final int chunksPerText;
    private final int chunkBytes;
    private final RuntimeException failure;
    private final List<String> texts = new CopyOnWriteArrayList<>();

    private FakeTextToSpeech(Builder b) {
        this.firstChunkDelay = b.firstChunkDelay;
        this.chunkDelay = b.chunkDelay;
        this.chunksPerText = b.chunksPerText;
        this.chunkBytes = b.chunkBytes;
        this.failure = b.failure;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public StreamHandle synthesize(String text, StreamListener<AudioChunk> listener) {
        texts.add(text);
        AudioChunk silence = AudioChunk.of(new byte[chunkBytes]);
        return VirtualThreadStream.start("fake-tts", listener, emit -> {
            emit.sleep(firstChunkDelay);
            if (failure != null) {
                throw failure;
            }
            for (int i = 0; i < chunksPerText && !emit.isCancelled(); i++) {
                if (i > 0) {
                    emit.sleep(chunkDelay);
                }
                emit.emit(silence);
            }
        });
    }

    @Override
    public PcmFormat format() {
        return FORMAT;
    }

    /** Texts received so far, oldest first. */
    public List<String> texts() {
        return List.copyOf(texts);
    }

    public static final class Builder {
        private Duration firstChunkDelay = Duration.ZERO;
        private Duration chunkDelay = Duration.ZERO;
        private int chunksPerText = 3;
        private int chunkBytes = 640;
        private RuntimeException failure;

        public Builder firstChunkDelay(Duration delay) {
            this.firstChunkDelay = Objects.requireNonNull(delay);
            return this;
        }

        public Builder chunkDelay(Duration delay) {
            this.chunkDelay = Objects.requireNonNull(delay);
            return this;
        }

        public Builder chunksPerText(int chunks) {
            this.chunksPerText = chunks;
            return this;
        }

        public Builder chunkBytes(int bytes) {
            this.chunkBytes = bytes;
            return this;
        }

        /** Fails every synthesis with {@code error} after the first-chunk delay. */
        public Builder failWith(RuntimeException error) {
            this.failure = Objects.requireNonNull(error);
            return this;
        }

        public FakeTextToSpeech build() {
            return new FakeTextToSpeech(this);
        }
    }
}
