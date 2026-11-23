package com.github.wednesday;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wednesday.testing.MicrophoneSimulator;
import com.github.wednesday.testing.VoiceClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-R8: a recorded utterance streamed through the voice WebSocket like a microphone would,
 * with fake providers, comes back as a spoken reply.
 */
@QuarkusTest
class VoiceTurnIntegrationTest {

    @TestHTTPResource("/ws/voice")
    URI uri;

    @ParameterizedTest
    @ValueSource(strings = {MicrophoneSimulator.SPANISH, MicrophoneSimulator.ENGLISH})
    void fixtureUtteranceGetsASpokenReply(String fixture) throws Exception {
        var client = VoiceClient.connect(uri);
        client.next("ready");
        client.nextState("idle");

        client.send("{\"type\":\"start\"}");
        client.nextState("listening");
        MicrophoneSimulator.stream(fixture, chunk -> client.sendAudio(chunk.bytes()), 4.0);
        client.send("{\"type\":\"stop\"}");

        client.nextState("thinking");
        client.nextState("speaking");
        JsonNode done = client.next("turn_done");
        client.nextState("idle");

        List<String> types = client.seen().stream().map(n -> n.path("type").asText()).toList();
        assertFalse(types.contains("error"), "every fixture frame passes the Gateway's format check: " + client.seen());
        assertTrue(client.seen().stream().anyMatch(n -> n.path("type").asText().equals("transcript")
                && n.path("final").asBoolean()), "a final transcript was sent");
        assertTrue(client.audioBytes() > 0, "a response audio stream arrived");
        assertEquals(0, client.audioBytes() % 2, "whole 16-bit samples");
        assertTrue(done.path("voice").asBoolean());
        assertTrue(done.path("firstAudioMs").asLong() >= 0, "first audio measured from end of speech");
        client.close();
    }
}
