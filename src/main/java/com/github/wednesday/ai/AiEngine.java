package com.github.wednesday.ai;

import com.github.wednesday.common.ModelRequest;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.context.ConversationContext;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns a user message into a streamed reply: builds the model input from the
 * {@link ConversationContext}, streams text tokens to the caller, and records the exchange in the
 * context once the reply is complete.
 *
 * <p>Failure handling: each model call has a first-token timeout and a total timeout. A call that
 * fails before producing any text is retried once. If every attempt fails, the caller receives a
 * short spoken apology instead of an error; if the reply breaks off midway, a short "cut off"
 * sentence is appended. Failed exchanges are not recorded in the context.
 */
public final class AiEngine {

    private static final Logger LOG = Logger.getLogger(AiEngine.class);

    public static final String FAILURE_MESSAGE =
            "Lo siento, ahora mismo no puedo responder. Inténtalo otra vez en un momento.";
    public static final String CUT_OFF_MESSAGE = " Perdona, se me ha cortado la respuesta.";

    /** Timeouts and retry budget for model calls. */
    public record Settings(Duration firstTokenTimeout, Duration totalTimeout, int maxAttempts) {
        public static final Settings DEFAULT = new Settings(Duration.ofSeconds(8), Duration.ofSeconds(30), 2);

        public Settings {
            Objects.requireNonNull(firstTokenTimeout, "firstTokenTimeout");
            Objects.requireNonNull(totalTimeout, "totalTimeout");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
        }
    }

    private final LanguageModel model;
    private final ConversationContext context;
    private final Settings settings;

    public AiEngine(LanguageModel model, ConversationContext context) {
        this(model, context, Settings.DEFAULT);
    }

    public AiEngine(LanguageModel model, ConversationContext context, Settings settings) {
        this.model = Objects.requireNonNull(model, "model");
        this.context = Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Streams the reply's text tokens to {@code tokens}; always completes unless cancelled. */
    public StreamHandle respond(String userText, StreamListener<String> tokens) {
        Objects.requireNonNull(userText, "userText");
        ModelRequest request = context.buildRequest(userText);
        LOG.debugf("user: %s", userText);

        return VirtualThreadStream.start("ai-engine", tokens, emit -> {
            StringBuilder reply = new StringBuilder();
            Throwable lastError = null;
            for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
                lastError = attempt(request, emit, reply);
                if (lastError == null) {
                    context.addTurn(userText, reply.toString());
                    LOG.debugf("assistant: %s", reply);
                    return;
                }
                if (!reply.isEmpty()) {
                    break;
                }
                LOG.warnf("model attempt %d/%d failed: %s", attempt, settings.maxAttempts(), describe(lastError));
            }
            LOG.errorf("model call failed, sending spoken apology: %s", describe(lastError));
            if (reply.isEmpty()) {
                emit.emit(FAILURE_MESSAGE);
            } else {
                boolean endsWithSpace = Character.isWhitespace(reply.charAt(reply.length() - 1));
                emit.emit(endsWithSpace ? CUT_OFF_MESSAGE.strip() : CUT_OFF_MESSAGE);
            }
        });
    }

    /** Runs one model call; returns null on success or the reason it failed. */
    private Throwable attempt(ModelRequest request, VirtualThreadStream.Emitter<String> emit, StringBuilder reply)
            throws InterruptedException {
        BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        StreamHandle generation = model.generate(request, new QueueingListener(signals));
        long deadline = System.nanoTime() + settings.totalTimeout().toNanos();
        boolean sawText = false;
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                long wait = sawText ? remaining : Math.min(remaining, settings.firstTokenTimeout().toNanos());
                Signal signal = wait > 0 ? signals.poll(wait, TimeUnit.NANOSECONDS) : null;
                if (signal == null) {
                    return new TimeoutException(sawText
                            ? "reply not finished within " + settings.totalTimeout()
                            : "no first token within " + settings.firstTokenTimeout());
                }
                switch (signal) {
                    case Signal.Event(ModelEvent.Token(String text)) -> {
                        sawText = true;
                        reply.append(text);
                        emit.emit(text);
                    }
                    case Signal.Event(ModelEvent.ToolCall toolCall) ->
                            LOG.infof("ignoring tool call %s: tools arrive in Phase 5", toolCall.name());
                    case Signal.Failed(Throwable error) -> {
                        return error;
                    }
                    case Signal.Completed() -> {
                        return null;
                    }
                }
            }
        } finally {
            generation.cancel();
        }
    }

    private static String describe(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** What the model stream reports, funnelled into a queue the engine thread reads. */
    sealed interface Signal {
        record Event(ModelEvent event) implements Signal {
        }

        record Failed(Throwable error) implements Signal {
        }

        record Completed() implements Signal {
        }
    }

    private record QueueingListener(BlockingQueue<Signal> queue) implements StreamListener<ModelEvent> {
        @Override
        public void onEvent(ModelEvent event) {
            queue.add(new Signal.Event(event));
        }

        @Override
        public void onError(Throwable error) {
            queue.add(new Signal.Failed(error));
        }

        @Override
        public void onComplete() {
            queue.add(new Signal.Completed());
        }
    }
}
