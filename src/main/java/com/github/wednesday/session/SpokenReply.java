package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.Stage;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.tts.SentenceChunker;
import com.github.wednesday.tts.TextToSpeech;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One spoken reply: model tokens flow through a {@link SentenceChunker} into {@link TextToSpeech}
 * while the model is still writing.
 *
 * <p>Two threads cooperate. The engine's thread receives tokens, forwards them as text and pushes
 * completed sentences into a queue. This stream's own thread takes sentences from the queue and
 * speaks them one segment at a time, in order. The first sentence is spoken alone so audio starts
 * as early as possible; after that, every sentence that queued up while the previous segment was
 * being spoken is merged into the next segment (up to a size limit), which costs no extra waiting
 * and keeps TTS calls per reply low. If the voice fails, the text keeps flowing and the failure is
 * reported once.
 */
public final class SpokenReply {

    private static final Logger LOG = Logger.getLogger(SpokenReply.class);

    /** What a spoken reply reports, in order per kind. */
    public sealed interface Event {
        /** A piece of reply text from the model. */
        record Text(String token) implements Event {
        }

        /** A segment about to be spoken. */
        record Segment(String text) implements Event {
        }

        /** Audio for the current segment. */
        record Audio(AudioChunk chunk) implements Event {
        }

        /** The voice failed; the rest of the reply is text only. */
        record SpeechFailed(String reason) implements Event {
        }
    }

    /**
     * @param firstSegmentMaxChars longest first segment (also the chunker's forced-cut limit)
     * @param segmentMaxChars      longest merged segment after the first
     */
    public record Settings(int firstSegmentMaxChars, int segmentMaxChars) {
        public static final Settings DEFAULT = new Settings(160, 400);
    }

    private SpokenReply() {
    }

    public static StreamHandle start(AiEngine engine, TextToSpeech tts, String userText, Timeline timeline,
                                     Settings settings, StreamListener<Event> listener) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(tts);
        Objects.requireNonNull(timeline);
        Objects.requireNonNull(settings);
        return VirtualThreadStream.start("spoken-reply", listener,
                emit -> new Run(engine, tts, userText, timeline, settings, emit).run());
    }

    private record Failure(Throwable error) {
    }

    private static final Object END = new Object();

    private static final class Run {
        private final AiEngine engine;
        private final TextToSpeech tts;
        private final String userText;
        private final Timeline timeline;
        private final Settings settings;
        private final VirtualThreadStream.Emitter<Event> emit;
        private final BlockingQueue<Object> sentences = new LinkedBlockingQueue<>();
        private volatile StreamHandle speaking;
        private boolean spokeAnything;

        Run(AiEngine engine, TextToSpeech tts, String userText, Timeline timeline, Settings settings,
            VirtualThreadStream.Emitter<Event> emit) {
            this.engine = engine;
            this.tts = tts;
            this.userText = userText;
            this.timeline = timeline;
            this.settings = settings;
            this.emit = emit;
        }

        void run() throws Exception {
            SentenceChunker chunker = new SentenceChunker(settings.firstSegmentMaxChars());
            StreamHandle reply = engine.respond(userText, new StreamListener<>() {
                @Override
                public void onEvent(String token) {
                    timeline.mark(Stage.FIRST_TOKEN);
                    emit.emit(new Event.Text(token));
                    List<String> done = chunker.push(token);
                    sentences.addAll(done);
                }

                @Override
                public void onError(Throwable error) {
                    sentences.add(new Failure(error));
                }

                @Override
                public void onComplete() {
                    timeline.mark(Stage.LAST_TOKEN);
                    chunker.flush().ifPresent(sentences::add);
                    sentences.add(END);
                }
            });
            try {
                speakUntilEnd();
            } finally {
                reply.cancel();
                StreamHandle s = speaking;
                if (s != null) {
                    s.cancel();
                }
            }
        }

        private void speakUntilEnd() throws Exception {
            boolean voiceWorks = true;
            boolean first = true;
            while (true) {
                Object item = sentences.take();
                if (item == END) {
                    break;
                }
                if (item instanceof Failure(Throwable error)) {
                    throw error instanceof Exception e ? e : new RuntimeException(error);
                }
                StringBuilder segment = new StringBuilder((String) item);
                if (first) {
                    timeline.mark(Stage.FIRST_SEGMENT);
                    first = false;
                } else {
                    while (sentences.peek() instanceof String next
                            && segment.length() + 1 + next.length() <= settings.segmentMaxChars()) {
                        sentences.poll();
                        segment.append(' ').append(next);
                    }
                }
                emit.emit(new Event.Segment(segment.toString()));
                if (voiceWorks) {
                    voiceWorks = speak(segment.toString());
                }
            }
            if (spokeAnything) {
                timeline.mark(Stage.LAST_AUDIO);
            }
        }

        /** Speaks one segment and waits for its audio to finish; false if the voice failed. */
        private boolean speak(String text) throws InterruptedException {
            CompletableFuture<Void> done = new CompletableFuture<>();
            speaking = tts.synthesize(text, new StreamListener<>() {
                @Override
                public void onEvent(AudioChunk chunk) {
                    timeline.mark(Stage.FIRST_AUDIO);
                    spokeAnything = true;
                    emit.emit(new Event.Audio(chunk));
                }

                @Override
                public void onError(Throwable error) {
                    done.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    done.complete(null);
                }
            });
            try {
                done.get();
                return true;
            } catch (ExecutionException e) {
                String reason = e.getCause() == null ? e.toString() : e.getCause().getMessage();
                LOG.warnf("voice failed, continuing with text only: %s", reason);
                emit.emit(new Event.SpeechFailed(reason));
                return false;
            }
        }
    }
}
