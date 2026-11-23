package com.github.wednesday.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.AudioFormats;
import com.github.wednesday.session.SessionEvent;
import com.github.wednesday.session.VoiceSession;
import com.github.wednesday.session.VoiceSessions;
import io.quarkus.websockets.next.OnBinaryMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.buffer.Buffer;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice transport.
 * <ul>
 *   <li>Client to server: binary messages are microphone frames (exactly one 20 ms frame of
 *       16 kHz 16-bit mono PCM each, see ADR 0002); text messages are JSON controls
 *       {@code start}, {@code stop}, {@code user_text}, {@code reset}, {@code report}.</li>
 *   <li>Server to client: binary messages are Wednesday's voice (PCM at the rate announced in
 *       {@code ready}); text messages are JSON events, one per {@link SessionEvent}.</li>
 * </ul>
 * Translation and format checks only: no prompts, no decisions.
 */
@WebSocket(path = "/ws/voice")
public class VoiceSocket {

    private static final Logger LOG = Logger.getLogger(VoiceSocket.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final VoiceSessions sessions;
    private final Map<String, VoiceSession> byConnection = new ConcurrentHashMap<>();

    public VoiceSocket(VoiceSessions sessions) {
        this.sessions = sessions;
    }

    @OnOpen
    @RunOnVirtualThread
    public void open(WebSocketConnection connection) {
        sessions.open(event -> send(connection, event), session -> byConnection.put(connection.id(), session));
    }

    @OnBinaryMessage
    public void audio(WebSocketConnection connection, Buffer frame) {
        VoiceSession session = byConnection.get(connection.id());
        if (session == null) {
            return;
        }
        byte[] bytes = frame.getBytes();
        String problem = InputFrames.problem(bytes);
        if (problem != null) {
            send(connection, new SessionEvent.Error(problem));
            return;
        }
        session.audio(AudioChunk.of(bytes));
    }

    @OnTextMessage
    @RunOnVirtualThread
    public void control(WebSocketConnection connection, String raw) {
        VoiceSession session = byConnection.get(connection.id());
        if (session == null) {
            send(connection, new SessionEvent.Error("No hay sesión activa."));
            return;
        }
        JsonNode msg;
        try {
            msg = JSON.readTree(raw);
        } catch (Exception e) {
            send(connection, new SessionEvent.Error("Mensaje no válido."));
            return;
        }
        switch (msg.path("type").asText("")) {
            case "start" -> session.startTalking();
            case "stop" -> session.stopTalking();
            case "user_text" -> session.userText(msg.path("text").asText(""));
            case "reset" -> session.reset();
            case "report" -> session.report();
            default -> send(connection, new SessionEvent.Error("Tipo de mensaje desconocido."));
        }
    }

    @OnClose
    public void close(WebSocketConnection connection) {
        VoiceSession session = byConnection.remove(connection.id());
        if (session != null) {
            session.close();
        }
    }

    /** Number of live sessions (for tests). */
    int openSessions() {
        return byConnection.size();
    }

    private void send(WebSocketConnection connection, SessionEvent event) {
        if (!connection.isOpen()) {
            return;
        }
        try {
            if (event instanceof SessionEvent.Audio(AudioChunk chunk)) {
                connection.sendBinaryAndAwait(Buffer.buffer(chunk.bytes()));
            } else {
                connection.sendTextAndAwait(toJson(event));
            }
        } catch (RuntimeException e) {
            LOG.debugf("dropping event for closed connection: %s", e.getMessage());
        }
    }

    static String toJson(SessionEvent event) {
        ObjectNode n = JSON.createObjectNode();
        switch (event) {
            case SessionEvent.Ready r -> {
                n.put("type", "ready").put("sessionId", r.sessionId()).put("model", r.model())
                        .put("contextTurns", r.contextTurns()).put("targetMs", r.targetMillis());
                n.putObject("input").put("sampleRate", AudioFormats.INPUT.sampleRate())
                        .put("channels", AudioFormats.INPUT.channels()).put("frameMs", AudioFormats.INPUT_FRAME_MILLIS);
                n.putObject("output").put("sampleRate", r.outputSampleRate()).put("channels", 1);
            }
            case SessionEvent.StateChanged s -> n.put("type", "state").put("state", s.state().name().toLowerCase());
            case SessionEvent.Transcript t -> n.put("type", "transcript").put("text", t.text()).put("final", t.isFinal());
            case SessionEvent.Token t -> n.put("type", "token").put("text", t.text());
            case SessionEvent.Audio a -> throw new IllegalArgumentException("audio is sent as binary");
            case SessionEvent.TurnDone d -> turn(n.put("type", "turn_done"), d);
            case SessionEvent.Report r -> {
                n.put("type", "report");
                var rows = n.putArray("turns");
                r.turns().forEach(d -> turn(rows.addObject(), d));
                putIfPresent(n, "p50FirstTokenMs", r.p50FirstTokenMillis());
                putIfPresent(n, "p95FirstTokenMs", r.p95FirstTokenMillis());
                putIfPresent(n, "p50FirstAudioMs", r.p50FirstAudioMillis());
                putIfPresent(n, "p95FirstAudioMs", r.p95FirstAudioMillis());
            }
            case SessionEvent.Reset r -> n.put("type", "reset");
            case SessionEvent.Error e -> n.put("type", "error").put("message", e.message());
        }
        return n.toString();
    }

    private static void putIfPresent(ObjectNode n, String field, Long value) {
        if (value != null) {
            n.put(field, value);
        }
    }

    private static ObjectNode turn(ObjectNode n, SessionEvent.TurnDone d) {
        return n.put("turn", d.turn()).put("voice", d.voice()).put("firstTokenMs", d.firstTokenMillis())
                .put("firstAudioMs", d.firstAudioMillis()).put("totalMs", d.totalMillis());
    }
}
