package com.github.wednesday.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wednesday.testing.VoiceClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/** The voice transport end to end with fake providers (test profile). */
@QuarkusTest
class VoiceSocketTest {

    @TestHTTPResource("/ws/voice")
    URI uri;

    @Test
    void readyAnnouncesBothAudioFormats() throws Exception {
        var client = VoiceClient.connect(uri);
        JsonNode ready = client.next("ready");
        assertEquals(16000, ready.path("input").path("sampleRate").asInt());
        assertEquals(1, ready.path("input").path("channels").asInt());
        assertEquals(20, ready.path("input").path("frameMs").asInt());
        assertEquals(24000, ready.path("output").path("sampleRate").asInt());
        assertEquals(1, ready.path("output").path("channels").asInt());
        client.nextState("idle");
        client.close();
    }

    @Test
    void voiceTurnOverTheSocket() throws Exception {
        var client = VoiceClient.connect(uri);
        client.next("ready");
        client.nextState("idle");

        client.send("{\"type\":\"start\"}");
        client.nextState("listening");
        for (int i = 0; i < 25; i++) {
            client.sendAudio(new byte[640]);
        }
        client.send("{\"type\":\"stop\"}");

        JsonNode transcript;
        do {
            transcript = client.next("transcript");
        } while (!transcript.path("final").asBoolean());
        assertEquals("¿Qué hora es?", transcript.path("text").asText());
        client.nextState("thinking");
        client.nextState("speaking");
        JsonNode done = client.next("turn_done");
        assertTrue(done.path("voice").asBoolean());
        assertTrue(done.path("firstAudioMs").asLong() >= 0);
        client.nextState("idle");
        assertTrue(client.audioBytes() > 0, "reply audio arrives as binary frames");
        client.close();
    }

    @Test
    void wrongSizedFramesAreRejected() throws Exception {
        var client = VoiceClient.connect(uri);
        client.next("ready");
        client.sendAudio(new byte[1000]);
        assertTrue(client.next("error").path("message").asText().contains("640"));
        client.close();
    }

    @Test
    void unknownControlMessagesAreRejected() throws Exception {
        var client = VoiceClient.connect(uri);
        client.next("ready");
        client.send("{\"type\":\"dance\"}");
        assertEquals("Tipo de mensaje desconocido.", client.next("error").path("message").asText());
        client.send("not json");
        assertEquals("Mensaje no válido.", client.next("error").path("message").asText());
        client.close();
    }
}
