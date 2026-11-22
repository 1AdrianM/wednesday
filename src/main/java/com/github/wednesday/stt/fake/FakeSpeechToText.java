package com.github.wednesday.stt.fake;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.stt.SpeechToText;
import com.github.wednesday.stt.SttStream;
import com.github.wednesday.stt.TranscriptEvent;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic {@link SpeechToText} for tests. Ignores audio content: as audio arrives it reveals
 * a canned transcript one word per {@code chunksPerWord} chunks as partials; after
 * {@link SttStream#endOfAudio()} and a configurable delay it emits the final transcript.
 */
public final class FakeSpeechToText implements SpeechToText {

    private final String transcript;
    private final int chunksPerWord;
    private final Duration finalDelay;
    private final AtomicLong audioBytesReceived = new AtomicLong();

    private FakeSpeechToText(Builder b) {
        this.transcript = b.transcript;
        this.chunksPerWord = b.chunksPerWord;
        this.finalDelay = b.finalDelay;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Total audio bytes received across all streams. */
    public long audioBytesReceived() {
        return audioBytesReceived.get();
    }

    @Override
    public SttStream open(StreamListener<TranscriptEvent> listener) {
        BlockingQueue<AudioChunk> inbox = new LinkedBlockingQueue<>();
        AudioChunk endMarker = AudioChunk.of(new byte[0]);
        List<String> words = transcript.isBlank() ? List.of() : Arrays.asList(transcript.trim().split("\\s+"));

        VirtualThreadStream<TranscriptEvent> stream = VirtualThreadStream.start("fake-stt", listener, emit -> {
            int chunks = 0;
            int revealed = 0;
            while (!emit.isCancelled()) {
                AudioChunk chunk = inbox.take();
                if (chunk == endMarker) {
                    break;
                }
                audioBytesReceived.addAndGet(chunk.size());
                chunks++;
                int shouldReveal = Math.min(words.size(), chunks / chunksPerWord);
                if (shouldReveal > revealed) {
                    revealed = shouldReveal;
                    emit.emit(new TranscriptEvent.Partial(String.join(" ", words.subList(0, revealed))));
                }
            }
            emit.sleep(finalDelay);
            emit.emit(new TranscriptEvent.Final(String.join(" ", words)));
        });

        return new SttStream() {
            @Override
            public void send(AudioChunk audio) {
                if (stream.isActive()) {
                    inbox.add(Objects.requireNonNull(audio));
                }
            }

            @Override
            public void endOfAudio() {
                inbox.add(endMarker);
            }

            @Override
            public void cancel() {
                stream.cancel();
            }

            @Override
            public boolean isActive() {
                return stream.isActive();
            }

            @Override
            public CompletionStage<Void> completion() {
                return stream.completion();
            }
        };
    }

    public static final class Builder {
        private String transcript = "hola wednesday";
        private int chunksPerWord = 10;
        private Duration finalDelay = Duration.ZERO;

        public Builder transcript(String transcript) {
            this.transcript = Objects.requireNonNull(transcript);
            return this;
        }

        public Builder chunksPerWord(int chunks) {
            if (chunks < 1) {
                throw new IllegalArgumentException("chunksPerWord must be >= 1");
            }
            this.chunksPerWord = chunks;
            return this;
        }

        public Builder finalDelay(Duration delay) {
            this.finalDelay = Objects.requireNonNull(delay);
            return this;
        }

        public FakeSpeechToText build() {
            return new FakeSpeechToText(this);
        }
    }
}
