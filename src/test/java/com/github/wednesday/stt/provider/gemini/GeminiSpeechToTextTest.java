package com.github.wednesday.stt.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.stt.SttStream;
import com.github.wednesday.stt.TranscriptEvent;
import com.github.wednesday.testing.RecordingListener;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the adapter against a local WebSocket server that speaks the Live API message shapes. */
class GeminiSpeechToTextTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final ObjectMapper JSON = new ObjectMapper();

    private Vertx vertx;
    private HttpServer server;
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final CountDownLatch closedByClient = new CountDownLatch(1);
    /** How the fake server answers each client message. */
    private volatile BiConsumer<ServerWebSocket, JsonNode> script = GeminiSpeechToTextTest::happyPath;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().webSocketHandler(ws -> {
            query.set(ws.query());
            ws.textMessageHandler(text -> {
                JsonNode msg = read(text);
                received.add(msg);
                script.accept(ws, msg);
            });
            ws.closeHandler(v -> closedByClient.countDown());
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private GeminiSpeechToText stt(Duration finalTimeout) {
        URI url = URI.create("ws://127.0.0.1:" + server.actualPort() + "/ws/live");
        return new GeminiSpeechToText(new GeminiSpeechToText.Config(url, "secret-key", "stt-test", List.of(), finalTimeout));
    }

    private static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Message sequence observed from the real API (2026-11-22): binary JSON frames, generationComplete, no turnComplete. */
    static void happyPath(ServerWebSocket ws, JsonNode msg) {
        if (msg.has("setup")) {
            ws.writeBinaryMessage(Buffer.buffer("{ \"setupComplete\": {} }"));
        } else if (msg.path("realtimeInput").has("activityStart")) {
            ws.writeBinaryMessage(Buffer.buffer("{\"serverContent\":{},\"voiceActivity\":{\"type\":\"ACTIVITY_START\",\"audioOffset\":\"0s\"}}"));
        } else if (msg.path("realtimeInput").has("audio")) {
            ws.writeBinaryMessage(Buffer.buffer("{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"qué ho\"}}}"));
        } else if (msg.path("realtimeInput").has("activityEnd")) {
            ws.writeBinaryMessage(Buffer.buffer("{\"serverContent\":{\"inputTranscription\":{\"text\":\"¿Qué hora\"}}}"));
            ws.writeBinaryMessage(Buffer.buffer("{\"serverContent\":{\"inputTranscription\":{\"text\":\" es?\"}}}"));
            ws.writeBinaryMessage(Buffer.buffer("{\"serverContent\":{\"generationComplete\":true}}"));
            ws.writeBinaryMessage(Buffer.buffer("{\"serverContent\":{},\"voiceActivity\":{\"type\":\"ACTIVITY_END\",\"audioOffset\":\"0.24s\"}}"));
        }
    }

    private static void sendFrames(SttStream stream, int frames) {
        for (int i = 0; i < frames; i++) {
            byte[] frame = new byte[640];
            frame[0] = (byte) i;
            stream.send(AudioChunk.of(frame));
        }
    }

    @Test
    void transcribesPushToTalkTurn() {
        var listener = new RecordingListener<TranscriptEvent>();
        SttStream stream = stt(Duration.ofSeconds(30)).open(listener);
        sendFrames(stream, 12);
        stream.endOfAudio();

        assertTrue(listener.awaitTermination(WAIT), "generationComplete ends the turn without waiting for the timeout");
        assertEquals(1, listener.completions());
        List<TranscriptEvent> events = listener.events();
        assertInstanceOf(TranscriptEvent.Partial.class, events.getFirst());
        assertEquals(new TranscriptEvent.Final("¿Qué hora es?"), events.getLast());
        assertEquals(1, events.stream().filter(e -> e instanceof TranscriptEvent.Final).count());
    }

    @Test
    void sendsSetupWithManualActivityAndTheKeyInTheQuery() {
        var listener = new RecordingListener<TranscriptEvent>();
        SttStream stream = stt(WAIT).open(listener);
        sendFrames(stream, 12);
        stream.endOfAudio();
        assertTrue(listener.awaitTermination(WAIT));

        assertEquals("key=secret-key", query.get());
        JsonNode setup = received.getFirst().path("setup");
        assertEquals("models/stt-test", setup.path("model").asText());
        assertEquals("TEXT", setup.path("generationConfig").path("responseModalities").get(0).asText());
        assertTrue(setup.path("inputAudioTranscription").isObject());
        assertTrue(setup.path("realtimeInputConfig").path("automaticActivityDetection").path("disabled").asBoolean());
        assertTrue(received.get(1).path("realtimeInput").has("activityStart"), "activityStart right after setup");
        assertTrue(received.getLast().path("realtimeInput").has("activityEnd"), "activityEnd closes the turn");
    }

    @Test
    void forwardsAllAudioInOrderBatchedTo100ms() throws Exception {
        var listener = new RecordingListener<TranscriptEvent>();
        SttStream stream = stt(WAIT).open(listener);
        sendFrames(stream, 12);
        stream.endOfAudio();
        assertTrue(listener.awaitTermination(WAIT));

        List<byte[]> chunks = new ArrayList<>();
        for (JsonNode m : received) {
            JsonNode audio = m.path("realtimeInput").path("audio");
            if (audio.isObject()) {
                assertEquals("audio/pcm;rate=16000", audio.path("mimeType").asText());
                chunks.add(Base64.getDecoder().decode(audio.path("data").asText()));
            }
        }
        assertEquals(List.of(3200, 3200, 1280), chunks.stream().map(c -> c.length).toList(),
                "five 20 ms frames per message; the remainder is flushed at end of audio");
        var all = new java.io.ByteArrayOutputStream();
        for (byte[] c : chunks) {
            all.write(c);
        }
        byte[] bytes = all.toByteArray();
        for (int i = 0; i < 12; i++) {
            assertEquals((byte) i, bytes[i * 640], "frame " + i + " out of order");
        }
    }

    @Test
    void acceptsTextFramesAndTurnCompleteToo() {
        script = (ws, msg) -> {
            if (msg.has("setup")) {
                ws.writeTextMessage("{\"setupComplete\":{}}");
            } else if (msg.path("realtimeInput").has("activityEnd")) {
                ws.writeTextMessage("{\"serverContent\":{\"inputTranscription\":{\"text\":\"hola\"}}}");
                ws.writeTextMessage("{\"serverContent\":{\"turnComplete\":true}}");
            }
        };
        var listener = new RecordingListener<TranscriptEvent>();
        SttStream stream = stt(WAIT).open(listener);
        sendFrames(stream, 5);
        stream.endOfAudio();
        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(new TranscriptEvent.Final("hola"), listener.events().getLast());
    }

    @Test
    void finishesWithWhatItHeardWhenTurnCompleteNeverArrives() {
        script = (ws, msg) -> {
            if (msg.has("setup")) {
                ws.writeTextMessage("{\"setupComplete\":{}}");
            } else if (msg.path("realtimeInput").has("activityEnd")) {
                ws.writeTextMessage("{\"serverContent\":{\"inputTranscription\":{\"text\":\"hola\"}}}");
            }
        };
        var listener = new RecordingListener<TranscriptEvent>();
        SttStream stream = stt(Duration.ofMillis(300)).open(listener);
        sendFrames(stream, 5);
        stream.endOfAudio();
        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(1, listener.completions());
        assertEquals(new TranscriptEvent.Final("hola"), listener.events().getLast());
    }

    @Test
    void serverClosingMidTurnIsAnErrorWithoutTheKey() {
        script = (ws, msg) -> {
            if (msg.has("setup")) {
                ws.close((short) 1008, "API key not valid: secret-key");
            }
        };
        var listener = new RecordingListener<TranscriptEvent>();
        SttStream stream = stt(WAIT).open(listener);
        sendFrames(stream, 5);
        stream.endOfAudio();
        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(1, listener.errors());
        String message = listener.error().getMessage();
        assertTrue(message.contains("1008"), message);
        assertFalse(message.contains("secret-key"), "the API key must never appear in errors: " + message);
    }

    @Test
    void cancelStopsCallbacksAndClosesTheSocket() throws Exception {
        script = (ws, msg) -> {
            if (msg.has("setup")) {
                ws.writeTextMessage("{\"setupComplete\":{}}");
            }
        };
        var listener = new RecordingListener<TranscriptEvent>();
        SttStream stream = stt(WAIT).open(listener);
        sendFrames(stream, 5);
        stream.cancel();
        assertTrue(closedByClient.await(5, TimeUnit.SECONDS), "socket closed after cancel");
        assertFalse(stream.isActive());
        Thread.sleep(100);
        assertEquals(0, listener.completions() + listener.errors(), "no terminal callback after cancel");
    }
}
