package com.github.wednesday.ai.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wednesday.ai.ModelEvent;
import com.github.wednesday.common.ChatMessage;
import com.github.wednesday.common.ModelRequest;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the adapter against a local HTTP server that imitates the Gemini streaming API. */
class GeminiLanguageModelTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final ModelRequest REQUEST = new ModelRequest("Eres Wednesday.",
            List.of(ChatMessage.user("hola"), ChatMessage.assistant("¡Hola!"), ChatMessage.user("¿qué tal?")));

    private HttpServer server;
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GeminiLanguageModel model(String thinkingLevel) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/");
        return new GeminiLanguageModel(new GeminiLanguageModel.Config(base, "secret-key", "gemini-test", thinkingLevel));
    }

    private static String sse(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String c : chunks) {
            sb.append("data: ").append(c).append("\r\n\r\n");
        }
        return sb.toString();
    }

    private static String textChunk(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
    }

    @Test
    void streamsTextTokensFromServerSentEvents() {
        responseBody = sse(textChunk("Muy "), textChunk("bien, "),
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"gracias.\"}]},\"finishReason\":\"STOP\"}]}");
        var listener = new RecordingListener<ModelEvent>();

        model("MINIMAL").generate(REQUEST, listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(0, listener.errors(), () -> String.valueOf(listener.error()));
        assertEquals(List.of(new ModelEvent.Token("Muy "), new ModelEvent.Token("bien, "),
                new ModelEvent.Token("gracias.")), listener.events());
    }

    @Test
    void sendsKeyInHeaderAndConversationInGeminiShape() throws Exception {
        responseBody = sse(textChunk("ok"));
        var listener = new RecordingListener<ModelEvent>();

        model("MINIMAL").generate(REQUEST, listener);
        assertTrue(listener.awaitTermination(WAIT));

        assertEquals("/v1beta/models/gemini-test:streamGenerateContent?alt=sse", requestPath.get());
        assertEquals("secret-key", apiKeyHeader.get());
        assertFalse(requestPath.get().contains("secret-key"), "key never in the URL");

        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertEquals("Eres Wednesday.", body.at("/systemInstruction/parts/0/text").asText());
        assertEquals("user", body.at("/contents/0/role").asText());
        assertEquals("model", body.at("/contents/1/role").asText());
        assertEquals("¿qué tal?", body.at("/contents/2/parts/0/text").asText());
        assertEquals("MINIMAL", body.at("/generationConfig/thinkingConfig/thinkingLevel").asText());
    }

    @Test
    void omitsThinkingConfigWhenDisabled() throws Exception {
        responseBody = sse(textChunk("ok"));
        var listener = new RecordingListener<ModelEvent>();

        model(null).generate(REQUEST, listener);
        assertTrue(listener.awaitTermination(WAIT));

        assertTrue(new ObjectMapper().readTree(requestBody.get()).path("generationConfig").isMissingNode());
    }

    @Test
    void skipsThoughtPartsAndMapsFunctionCalls() {
        responseBody = sse(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"pensando...\",\"thought\":true}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"current_time\",\"args\":{\"tz\":\"Europe/Madrid\"}}}]}}]}",
                textChunk("Listo."));
        var listener = new RecordingListener<ModelEvent>();

        model("MINIMAL").generate(REQUEST, listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(List.of(
                new ModelEvent.ToolCall("call-1", "current_time", "{\"tz\":\"Europe/Madrid\"}"),
                new ModelEvent.Token("Listo.")), listener.events());
    }

    @Test
    void httpErrorBecomesOnErrorWithoutLeakingTheKey() {
        status = 429;
        responseBody = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        var listener = new RecordingListener<ModelEvent>();

        model("MINIMAL").generate(REQUEST, listener);

        assertTrue(listener.awaitTermination(WAIT));
        var error = assertInstanceOf(GeminiLanguageModel.GeminiException.class, listener.error());
        assertEquals(429, error.status());
        assertTrue(error.getMessage().contains("RESOURCE_EXHAUSTED"));
        assertFalse(error.getMessage().contains("secret-key"));
    }

    @Test
    void configFromEnvironmentRequiresKeyAndHidesIt() {
        assertThrows(IllegalArgumentException.class,
                () -> GeminiLanguageModel.Config.fromEnvironment(Map.of()));

        var config = GeminiLanguageModel.Config.fromEnvironment(
                Map.of("GEMINI_API_KEY", "k-123", "GEMINI_THINKING_LEVEL", "none"));
        assertEquals("gemini-3.5-flash-lite", config.model());
        assertNull(config.thinkingLevel());
        assertFalse(config.toString().contains("k-123"));
    }
}
