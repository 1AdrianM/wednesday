package com.github.wednesday.ai.fake;

import com.github.wednesday.ai.LanguageModel;
import com.github.wednesday.ai.ModelEvent;
import com.github.wednesday.ai.ModelRequest;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic {@link LanguageModel} for tests: streams a canned reply word by word with
 * configurable delays, optional tool calls first, and optional failure.
 */
public final class FakeLanguageModel implements LanguageModel {

    private static final Pattern WORD_WITH_SPACE = Pattern.compile("\\S+\\s*|\\s+");

    private final Function<ModelRequest, String> reply;
    private final List<ModelEvent.ToolCall> toolCalls;
    private final Duration firstTokenDelay;
    private final Duration tokenDelay;
    private final int failAfterTokens;
    private final RuntimeException failure;
    private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

    private FakeLanguageModel(Builder b) {
        this.reply = b.reply;
        this.toolCalls = List.copyOf(b.toolCalls);
        this.firstTokenDelay = b.firstTokenDelay;
        this.tokenDelay = b.tokenDelay;
        this.failAfterTokens = b.failAfterTokens;
        this.failure = b.failure;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Splits text into word tokens, keeping trailing whitespace on each token. */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = WORD_WITH_SPACE.matcher(text);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    @Override
    public StreamHandle generate(ModelRequest request, StreamListener<ModelEvent> listener) {
        requests.add(request);
        List<String> tokens = tokenize(reply.apply(request));
        return VirtualThreadStream.start("fake-llm", listener, emit -> {
            emit.sleep(firstTokenDelay);
            for (ModelEvent.ToolCall call : toolCalls) {
                emit.emit(call);
            }
            for (int i = 0; i < tokens.size() && !emit.isCancelled(); i++) {
                if (failure != null && i == failAfterTokens) {
                    throw failure;
                }
                if (i > 0) {
                    emit.sleep(tokenDelay);
                }
                emit.emit(new ModelEvent.Token(tokens.get(i)));
            }
            if (failure != null && failAfterTokens >= tokens.size()) {
                throw failure;
            }
        });
    }

    /** Requests received so far, oldest first. */
    public List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    public static final class Builder {
        private Function<ModelRequest, String> reply = r -> "Hola, soy Wednesday.";
        private final List<ModelEvent.ToolCall> toolCalls = new ArrayList<>();
        private Duration firstTokenDelay = Duration.ZERO;
        private Duration tokenDelay = Duration.ZERO;
        private int failAfterTokens = -1;
        private RuntimeException failure;

        public Builder reply(String text) {
            Objects.requireNonNull(text, "text");
            this.reply = r -> text;
            return this;
        }

        public Builder reply(Function<ModelRequest, String> reply) {
            this.reply = Objects.requireNonNull(reply, "reply");
            return this;
        }

        public Builder toolCall(String id, String name, String argumentsJson) {
            toolCalls.add(new ModelEvent.ToolCall(id, name, argumentsJson));
            return this;
        }

        public Builder firstTokenDelay(Duration delay) {
            this.firstTokenDelay = Objects.requireNonNull(delay);
            return this;
        }

        public Builder tokenDelay(Duration delay) {
            this.tokenDelay = Objects.requireNonNull(delay);
            return this;
        }

        /** Fails with {@code error} after emitting {@code tokens} tokens (0 = before any token). */
        public Builder failAfter(int tokens, RuntimeException error) {
            this.failAfterTokens = tokens;
            this.failure = Objects.requireNonNull(error);
            return this;
        }

        public FakeLanguageModel build() {
            return new FakeLanguageModel(this);
        }
    }
}
