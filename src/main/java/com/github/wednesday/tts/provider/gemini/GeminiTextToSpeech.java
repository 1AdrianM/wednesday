package com.github.wednesday.tts.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.PcmFormat;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.tts.TextToSpeech;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link TextToSpeech} backed by Gemini TTS through the Interactions API with {@code stream: true}.
 * The server sends SSE events; each {@code step.delta} carries base64 PCM (audio/l16, 24 kHz, mono).
 * The API key travels in the {@code x-goog-api-key} header and is never logged.
 */
public final class GeminiTextToSpeech implements TextToSpeech {

    public static final PcmFormat FORMAT = new PcmFormat(24_000, 1);

    /** @param style optional delivery hint sent as speech metadata (e.g. "cálido y natural"); null for none */
    public record Config(URI baseUrl, String apiKey, String model, String voice, String style) {

        public static final URI DEFAULT_BASE_URL = URI.create("https://generativelanguage.googleapis.com/v1beta/");
        public static final String DEFAULT_MODEL = "gemini-3.8-flash-lite-tts";
        public static final String DEFAULT_VOICE = "Kore";

        public Config {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(voice, "voice");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Gemini API key missing: set GEMINI_API_KEY");
            }
        }

        /** Reads {@code GEMINI_API_KEY} (required), {@code GEMINI_TTS_MODEL}, {@code GEMINI_TTS_VOICE}, {@code GEMINI_TTS_STYLE}. */
        public static Config fromEnvironment(Map<String, String> env) {
            return new Config(DEFAULT_BASE_URL, env.get("GEMINI_API_KEY"),
                    env.getOrDefault("GEMINI_TTS_MODEL", DEFAULT_MODEL),
                    env.getOrDefault("GEMINI_TTS_VOICE", DEFAULT_VOICE),
                    env.get("GEMINI_TTS_STYLE"));
        }

        @Override
        public String toString() {
            return "Config[baseUrl=" + baseUrl + ", model=" + model + ", voice=" + voice + ", style=" + style + "]";
        }
    }

    /** Provider error with a message that is safe to log. */
    public static final class GeminiTtsException extends RuntimeException {
        private final int status;

        GeminiTtsException(int status, String message) {
            super("Gemini TTS error " + status + ": " + message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Config config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GeminiTextToSpeech(Config config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    GeminiTextToSpeech(Config config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public PcmFormat format() {
        return FORMAT;
    }

    @Override
    public StreamHandle synthesize(String text, StreamListener<AudioChunk> listener) {
        HttpRequest request = HttpRequest.newBuilder(config.baseUrl().resolve("interactions"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(text)))
                .build();

        return VirtualThreadStream.start("gemini-tts", listener, emit -> {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() != 200) {
                    throw new GeminiTtsException(response.statusCode(),
                            errorMessage(json, lines.collect(Collectors.joining("\n"))));
                }
                Iterator<String> it = lines.iterator();
                while (!emit.isCancelled() && it.hasNext()) {
                    String line = it.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        return;
                    }
                    JsonNode event = json.readTree(data);
                    if (event.has("error")) {
                        JsonNode error = event.get("error");
                        throw new GeminiTtsException(error.path("code").asInt(500), error.path("message").asText("stream error"));
                    }
                    JsonNode delta = event.path("delta");
                    if ("step.delta".equals(event.path("event_type").asText()) && "audio".equals(delta.path("type").asText())) {
                        byte[] pcm = Base64.getDecoder().decode(delta.path("data").asText());
                        if (pcm.length > 0) {
                            emit.emit(AudioChunk.of(pcm));
                        }
                    }
                }
            }
        });
    }

    String requestBody(String text) {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        ObjectNode part = root.putArray("input").addObject().put("type", "user_input")
                .putArray("content").addObject().put("type", "text").put("text", text);
        if (config.style() != null && !config.style().isBlank()) {
            part.putArray("annotations").addObject().put("type", "speech_metadata").put("style", config.style());
        }
        root.putObject("response_format").put("type", "audio");
        root.putObject("generation_config").putArray("speech_config").addObject().put("voice", config.voice());
        return root.toString();
    }

    private static String errorMessage(ObjectMapper json, String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            return (error.path("status").asText("") + " " + error.path("message").asText("")).trim();
        } catch (Exception e) {
            return "unreadable error body";
        }
    }
}
