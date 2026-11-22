package com.github.wednesday.ai;

import com.github.wednesday.common.ModelRequest;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.context.ConversationContext;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Turns a user message into a streamed reply: builds the model input from the
 * {@link ConversationContext}, streams text tokens to the caller, and records the exchange in the
 * context once the reply is complete.
 */
public final class AiEngine {

    private static final Logger LOG = Logger.getLogger(AiEngine.class);

    private final LanguageModel model;
    private final ConversationContext context;

    public AiEngine(LanguageModel model, ConversationContext context) {
        this.model = Objects.requireNonNull(model, "model");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Streams the reply's text tokens to {@code tokens}. */
    public StreamHandle respond(String userText, StreamListener<String> tokens) {
        Objects.requireNonNull(userText, "userText");
        ModelRequest request = context.buildRequest(userText);
        LOG.debugf("user: %s", userText);

        return VirtualThreadStream.start("ai-engine", tokens, emit -> {
            BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
            StreamHandle generation = model.generate(request, new QueueingListener(signals));
            StringBuilder reply = new StringBuilder();
            try {
                while (true) {
                    Signal signal = signals.take();
                    if (signal instanceof Signal.Event(ModelEvent.Token(String text))) {
                        reply.append(text);
                        emit.emit(text);
                    } else if (signal instanceof Signal.Event(ModelEvent.ToolCall toolCall)) {
                        LOG.infof("ignoring tool call %s: tools arrive in Phase 5", toolCall.name());
                    } else if (signal instanceof Signal.Failed(Throwable error)) {
                        throw error instanceof Exception e ? e : new RuntimeException(error);
                    } else {
                        break;
                    }
                }
            } finally {
                generation.cancel();
            }
            context.addTurn(userText, reply.toString());
            LOG.debugf("assistant: %s", reply);
        });
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
