package com.github.wednesday.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Test client for {@code /ws/voice}: collects JSON events in order and counts binary audio bytes. */
public final class VoiceClient implements WebSocket.Listener {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    private final List<JsonNode> seen = new CopyOnWriteArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final AtomicLong audioBytes = new AtomicLong();
    private WebSocket ws;

    public static VoiceClient connect(URI httpUri) {
        var client = new VoiceClient();
        URI wsUri = URI.create(httpUri.toString().replaceFirst("^http", "ws"));
        client.ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri, client).join();
        return client;
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            try {
                JsonNode n = JSON.readTree(text.toString());
                seen.add(n);
                inbox.add(n);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            text.setLength(0);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
        audioBytes.addAndGet(data.remaining());
        ws.request(1);
        return null;
    }

    /** Skips events until one of {@code type} arrives (5 s timeout). */
    public JsonNode next(String type) throws InterruptedException {
        while (true) {
            JsonNode n = inbox.poll(5, TimeUnit.SECONDS);
            assertNotNull(n, "timed out waiting for " + type + "; seen " + seen);
            if (n.path("type").asText().equals(type)) {
                return n;
            }
        }
    }

    /** Waits for {@code {"type":"state","state":<state>}}. */
    public void nextState(String state) throws InterruptedException {
        while (!next("state").path("state").asText().equals(state)) {
            // skip other states
        }
    }

    /** Every JSON event received so far. */
    public List<JsonNode> seen() {
        return List.copyOf(seen);
    }

    public long audioBytes() {
        return audioBytes.get();
    }

    public void send(String json) {
        ws.sendText(json, true).join();
    }

    public void sendAudio(byte[] frame) {
        ws.sendBinary(ByteBuffer.wrap(frame), true).join();
    }

    public void close() {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }
}
