package com.github.wednesday.gateway;

import com.github.wednesday.testing.LiveThreads;
import com.github.wednesday.testing.VoiceClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** P3-R7: disconnecting in any state leaves no session, thread or provider stream behind. */
@QuarkusTest
class VoiceSocketCleanupTest {

    static final Set<String> SESSION_THREADS = Set.of(
            "voice-session-", "fake-stt", "fake-llm", "fake-tts", "ai-engine", "spoken-reply");
    static final Predicate<String> OURS = name -> SESSION_THREADS.stream().anyMatch(name::startsWith);

    @TestHTTPResource("/ws/voice")
    URI uri;

    @Inject
    VoiceSocket socket;

    @Test
    void liveThreadsAreVisibleToTheCheck() throws Exception {
        var client = VoiceClient.connect(uri);
        client.next("ready");
        assertTrue(LiveThreads.names().stream().anyMatch(n -> n.startsWith("voice-session-")),
                "the dump must include virtual threads, or the leak check proves nothing");
        client.close();
    }

    @Test
    void repeatedDisconnectsInEveryStateLeakNothing() throws Exception {
        assertEquals(List.of(), LiveThreads.awaitNone(OURS, 3000), "start clean");
        int before = socket.openSessions();
        for (int i = 0; i < 12; i++) {
            var client = VoiceClient.connect(uri);
            client.next("ready");
            client.nextState("idle");
            switch (i % 3) {
                case 0 -> { } // idle
                case 1 -> { // listening: an STT stream is open
                    client.send("{\"type\":\"start\"}");
                    client.nextState("listening");
                    client.sendAudio(new byte[640]);
                }
                case 2 -> { // mid-reply: model, voice and reply streams are running
                    client.send("{\"type\":\"user_text\",\"text\":\"cuéntame algo largo\"}");
                    client.nextState("thinking");
                }
                default -> throw new IllegalStateException();
            }
            client.close();
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (socket.openSessions() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(before, socket.openSessions(), "every session removed");
        assertEquals(List.of(), LiveThreads.awaitNone(OURS, 3000), "no session or provider thread survives");
    }
}
