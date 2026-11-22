package com.github.wednesday.ai.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wednesday.ai.LanguageModel;
import com.github.wednesday.ai.ModelEvent;
import com.github.wednesday.common.ChatMessage;
import com.github.wednesday.common.ModelRequest;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link LanguageModel} backed by the Gemini API ({@code streamGenerateContent} with server-sent
 * events). Plain JDK HTTP client; the API key travels in the {@code x-goog-api-key} header, never
 * in the URL, and is never logged.
 */
public final class GeminiLanguageModel implements LanguageModel {

    private static final Logger LOG = Logger.getLogger(GeminiLanguageModel.class);

    /**
     * @param thinkingLevel Gemini 3+ thinking level (e.g. {@code MINIMAL}); {@code null} omits thinkingConfig
     */
    public record Config(URI baseUrl, String apiKey, String model, String thinkingLevel) {

        public static final URI DEFAULT_BASE_URL = URI.create("https://generativelanguage.googleapis.com/v1beta/");
        public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
        public static final String DEFAULT_THINKING_LEVEL = "MINIMAL";

        public Config {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(model, "model");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Gemini API key missing: set GEMINI_API_KEY");
            }
        }

        /**
         * Reads {@code GEMINI_API_KEY} (required), {@code GEMINI_MODEL} and
         * {@code GEMINI_THINKING_LEVEL} ({@code none} to omit thinkingConfig).
         */
        public static Config fromEnvironment(Map<String, String> env) {
            String level = env.getOrDefault("GEMINI_THINKING_LEVEL", DEFAULT_THINKING_LEVEL);
            return new Config(DEFAULT_BASE_URL, env.get("GEMINI_API_KEY"),
                    env.getOrDefault("GEMINI_MODEL", DEFAULT_MODEL),
                    "none".equalsIgnoreCase(level) ? null : level);
        }

        @Override
        public String toString() {
            return "Config[baseUrl=" + baseUrl + ", model=" + model + ", thinkingLevel=" + thinkingLevel + "]";
        }
    }

    /** Provider error with a message that is safe to log (no key, no prompt text). */
    public static final class GeminiException extends RuntimeException {
        private final int status;

        GeminiException(int status, String message) {
            super("Gemini error " + status + ": " + message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Config config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GeminiLanguageModel(Config config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    GeminiLanguageModel(Config config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
    }

    public static GeminiLanguageModel fromEnvironment() {
        return new GeminiLanguageModel(Config.fromEnvironment(System.getenv()));
    }

    @Override
    public StreamHandle generate(ModelRequest request, StreamListener<ModelEvent> listener) {
        HttpRequest httpRequest = HttpRequest.newBuilder(
                        config.baseUrl().resolve("models/" + config.model() + ":streamGenerateContent?alt=sse"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request)))
                .build();

        return VirtualThreadStream.start("gemini", listener, emit -> {
            HttpResponse<Stream<String>> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() != 200) {
                    throw new GeminiException(response.statusCode(),
                            errorMessage(lines.collect(Collectors.joining("\n"))));
                }
                AtomicInteger callCounter = new AtomicInteger();
                Iterator<String> it = lines.iterator();
                while (!emit.isCancelled() && it.hasNext()) {
                    String line = it.next();
                    if (line.startsWith("data:")) {
                        handleChunk(json.readTree(line.substring(5).trim()), emit, callCounter);
                    }
                }
            }
        });
    }

    String requestBody(ModelRequest request) {
        ObjectNode root = json.createObjectNode();
        if (!request.systemPrompt().isBlank()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
        }
        ArrayNode contents = root.putArray("contents");
        for (ChatMessage m : request.messages()) {
            ObjectNode content = contents.addObject();
            content.put("role", m.role() == ChatMessage.Role.USER ? "user" : "model");
            content.putArray("parts").addObject().put("text", m.text());
        }
        if (config.thinkingLevel() != null) {
            root.putObject("generationConfig").putObject("thinkingConfig")
                    .put("thinkingLevel", config.thinkingLevel());
        }
        return root.toString();
    }

    private void handleChunk(JsonNode chunk, VirtualThreadStream.Emitter<ModelEvent> emit, AtomicInteger calls) {
        if (chunk.has("error")) {
            JsonNode error = chunk.get("error");
            throw new GeminiException(error.path("code").asInt(500), error.path("message").asText("stream error"));
        }
        JsonNode candidate = chunk.path("candidates").path(0);
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            if (part.hasNonNull("text")) {
                String text = part.get("text").asText();
                if (!text.isEmpty()) {
                    emit.emit(new ModelEvent.Token(text));
                }
            } else if (part.has("functionCall")) {
                JsonNode call = part.get("functionCall");
                String id = call.hasNonNull("id") ? call.get("id").asText() : "call-" + calls.incrementAndGet();
                emit.emit(new ModelEvent.ToolCall(id, call.path("name").asText(),
                        call.has("args") ? call.get("args").toString() : "{}"));
            }
        }
        String finish = candidate.path("finishReason").asText("");
        if (!finish.isEmpty() && !finish.equals("STOP") && !finish.equals("MAX_TOKENS")) {
            LOG.warnf("Gemini stopped early: finishReason=%s", finish);
        }
    }

    private String errorMessage(String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            String message = error.path("message").asText("");
            String status = error.path("status").asText("");
            return (status + " " + message).trim();
        } catch (Exception e) {
            return "unreadable error body";
        }
    }
}
