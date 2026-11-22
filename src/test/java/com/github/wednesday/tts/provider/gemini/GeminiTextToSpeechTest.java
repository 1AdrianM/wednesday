package com.github.wednesday.tts.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.PcmFormat;
import com.github.wednesday.testing.RecordingListener;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the adapter against a local server replaying the event shapes seen from the real API. */
class GeminiTextToSpeechTest {

    static final Duration WAIT = Duration.ofSeconds(5);

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().toString());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiTextToSpeech tts() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/");
        return new GeminiTextToSpeech(new GeminiTextToSpeech.Config(base, "secret-key", "tts-test", "Kore", null));
    }

    private static String event(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    private static String delta(byte[] pcm) {
        return event("step.delta", "{\"index\":0,\"delta\":{\"mime_type\":\"audio/l16\",\"data\":\""
                + Base64.getEncoder().encodeToString(pcm) + "\",\"channels\":1,\"sample_rate\":24000,\"type\":\"audio\"},\"event_type\":\"step.delta\"}");
    }

    private static String realisticStream(byte[]... chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append(event("interaction.created", "{\"interaction\":{\"id\":\"x\"},\"event_type\":\"interaction.created\"}"));
        sb.append(event("step.start", "{\"index\":0,\"step\":{\"type\":\"model_output\"},\"event_type\":\"step.start\"}"));
        for (byte[] c : chunks) {
            sb.append(delta(c));
        }
        sb.append(event("step.stop", "{\"index\":0,\"event_type\":\"step.stop\"}"));
        sb.append(event("interaction.completed", "{\"interaction\":{\"id\":\"x\",\"status\":\"completed\"},\"event_type\":\"interaction.completed\"}"));
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    @Test
    void streamsDecodedPcmChunksInOrder() {
        response = realisticStream(new byte[] {1, 2, 3, 4}, new byte[] {5, 6});
        var listener = new RecordingListener<AudioChunk>();

        tts().synthesize("Hola, Adri.", listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(0, listener.errors(), () -> String.valueOf(listener.error()));
        assertEquals(List.of(AudioChunk.of(new byte[] {1, 2, 3, 4}), AudioChunk.of(new byte[] {5, 6})), listener.events());
    }

    @Test
    void sendsInteractionsRequestWithVoiceAndKeyHeader() throws Exception {
        response = realisticStream(new byte[] {0, 0});
        var listener = new RecordingListener<AudioChunk>();

        tts().synthesize("Son las diez.", listener);
        assertTrue(listener.awaitTermination(WAIT));

        assertEquals("/v1beta/interactions", path.get());
        assertEquals("secret-key", key.get());
        JsonNode json = new ObjectMapper().readTree(body.get());
        assertEquals("tts-test", json.path("model").asText());
        assertTrue(json.path("stream").asBoolean());
        assertEquals("Son las diez.", json.at("/input/0/content/0/text").asText());
        assertEquals("audio", json.at("/response_format/type").asText());
        assertEquals("Kore", json.at("/generation_config/speech_config/0/voice").asText());
    }

    @Test
    void reportsItsOutputFormat() {
        assertEquals(new PcmFormat(24_000, 1), tts().format());
    }

    @Test
    void httpErrorBecomesOnErrorWithoutTheKey() {
        status = 429;
        response = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        var listener = new RecordingListener<AudioChunk>();

        tts().synthesize("x", listener);

        assertTrue(listener.awaitTermination(WAIT));
        var error = assertInstanceOf(GeminiTextToSpeech.GeminiTtsException.class, listener.error());
        assertEquals(429, error.status());
        assertTrue(error.getMessage().contains("RESOURCE_EXHAUSTED"));
        assertFalse(error.getMessage().contains("secret-key"));
    }

    @Test
    void errorEventInsideTheStreamFails() {
        response = event("error", "{\"error\":{\"code\":500,\"message\":\"internal\"},\"event_type\":\"error\"}");
        var listener = new RecordingListener<AudioChunk>();

        tts().synthesize("x", listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertInstanceOf(GeminiTextToSpeech.GeminiTtsException.class, listener.error());
    }

    @Test
    void configFromEnvironmentHasDefaultsAndHidesKey() {
        assertThrows(IllegalArgumentException.class, () -> GeminiTextToSpeech.Config.fromEnvironment(Map.of()));
        var config = GeminiTextToSpeech.Config.fromEnvironment(Map.of("GEMINI_API_KEY", "k-123"));
        assertEquals("gemini-3.8-flash-lite-tts", config.model());
        assertEquals("Kore", config.voice());
        assertFalse(config.toString().contains("k-123"));
    }
}
