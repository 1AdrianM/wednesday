package com.github.wednesday.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** End to end over a real WebSocket with the fake model (test profile). */
@QuarkusTest
class ChatSocketTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/ws/chat")
    URI uri;

    @Inject
    ChatSocket socket;

    private static final class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    inbox.add(JSON.readTree(partial.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        JsonNode next(String type) throws InterruptedException {
            while (true) {
                JsonNode n = inbox.poll(5, TimeUnit.SECONDS);
                assertNotNull(n, "timed out waiting for " + type);
                if (n.path("type").asText().equals(type)) {
                    return n;
                }
            }
        }
    }

    private WebSocket connect(Client client) {
        URI ws = URI.create(uri.toString().replaceFirst("^http", "ws"));
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(ws, client).join();
    }

    @Test
    void chatTurnStreamsTokensAndLatency() throws Exception {
        var client = new Client();
        WebSocket ws = connect(client);

        JsonNode ready = client.next("ready");
        assertEquals("fake", ready.path("model").asText());
        assertEquals(1200, ready.path("targetMs").asLong());
        assertEquals("idle", client.next("state").path("state").asText());

        ws.sendText("{\"type\":\"user_text\",\"text\":\"hola\"}", true).join();
        assertEquals("thinking", client.next("state").path("state").asText());

        List<String> tokens = new CopyOnWriteArrayList<>();
        JsonNode n;
        while (!(n = client.inbox.poll(5, TimeUnit.SECONDS)).path("type").asText().equals("turn_done")) {
            if (n.path("type").asText().equals("token")) {
                tokens.add(n.path("text").asText());
            }
        }
        assertTrue(String.join("", tokens).contains("Me has dicho: hola"), tokens.toString());
        assertEquals(1, n.path("turn").asInt());
        assertTrue(n.path("firstTokenMs").asLong() >= 0);

        ws.sendText("{\"type\":\"report\"}", true).join();
        assertEquals(1, client.next("report").path("turns").size());

        ws.sendText("{\"type\":\"nope\"}", true).join();
        assertEquals("Tipo de mensaje desconocido.", client.next("error").path("message").asText());

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    @Test
    void sessionsAreCleanedUpOnDisconnect() throws Exception {
        int before = socket.openSessions();
        for (int i = 0; i < 5; i++) {
            var client = new Client();
            WebSocket ws = connect(client);
            client.next("ready");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (socket.openSessions() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(before, socket.openSessions());
    }
}
