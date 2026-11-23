package com.github.wednesday.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wednesday.session.ChatSession;
import com.github.wednesday.session.ChatSessions;
import com.github.wednesday.session.SessionEvent;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text chat transport. Client sends {@code {"type":"user_text","text":"..."}}, {@code {"type":"reset"}}
 * or {@code {"type":"report"}}; server sends one JSON object per {@link SessionEvent}.
 * Translation only: no prompts, no decisions.
 */
@WebSocket(path = "/ws/chat")
public class ChatSocket {

    private static final Logger LOG = Logger.getLogger(ChatSocket.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatSessions sessions;
    private final Map<String, ChatSession> byConnection = new ConcurrentHashMap<>();

    public ChatSocket(ChatSessions sessions) {
        this.sessions = sessions;
    }

    @OnOpen
    @RunOnVirtualThread
    public void open(WebSocketConnection connection) {
        ChatSession session = sessions.open(event -> send(connection, event));
        if (session != null) {
            byConnection.put(connection.id(), session);
        }
    }

    @OnTextMessage
    @RunOnVirtualThread
    public void message(WebSocketConnection connection, String raw) {
        ChatSession session = byConnection.get(connection.id());
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
            case "user_text" -> session.userText(msg.path("text").asText(""));
            case "reset" -> session.reset();
            case "report" -> session.report();
            default -> send(connection, new SessionEvent.Error("Tipo de mensaje desconocido."));
        }
    }

    @OnClose
    public void close(WebSocketConnection connection) {
        ChatSession session = byConnection.remove(connection.id());
        if (session != null) {
            session.close();
        }
    }

    /** Number of live sessions (for tests and health). */
    int openSessions() {
        return byConnection.size();
    }

    private void send(WebSocketConnection connection, SessionEvent event) {
        if (!connection.isOpen()) {
            return;
        }
        String json = toJson(event);
        if (json == null) {
            return;
        }
        try {
            connection.sendTextAndAwait(json);
        } catch (RuntimeException e) {
            LOG.debugf("dropping event for closed connection: %s", e.getMessage());
        }
    }

    static String toJson(SessionEvent event) {
        ObjectNode n = JSON.createObjectNode();
        switch (event) {
            case SessionEvent.Ready r -> n.put("type", "ready").put("sessionId", r.sessionId())
                    .put("model", r.model()).put("contextTurns", r.contextTurns()).put("targetMs", r.targetMillis());
            case SessionEvent.StateChanged s -> n.put("type", "state").put("state", s.state().name().toLowerCase());
            case SessionEvent.Token t -> n.put("type", "token").put("text", t.text());
            case SessionEvent.TurnDone d -> turn(n.put("type", "turn_done"), d);
            case SessionEvent.Report r -> {
                n.put("type", "report");
                var rows = n.putArray("turns");
                r.turns().forEach(d -> turn(rows.addObject(), d));
                if (r.p50FirstTokenMillis() != null) {
                    n.put("p50FirstTokenMs", r.p50FirstTokenMillis());
                }
                if (r.p95FirstTokenMillis() != null) {
                    n.put("p95FirstTokenMs", r.p95FirstTokenMillis());
                }
            }
            case SessionEvent.Transcript t -> n.put("type", "transcript").put("text", t.text()).put("final", t.isFinal());
            case SessionEvent.Audio a -> {
                return null; // text-only transport
            }
            case SessionEvent.Reset r -> n.put("type", "reset");
            case SessionEvent.Error e -> n.put("type", "error").put("message", e.message());
        }
        return n.toString();
    }

    private static ObjectNode turn(ObjectNode n, SessionEvent.TurnDone d) {
        return n.put("turn", d.turn()).put("firstTokenMs", d.firstTokenMillis()).put("totalMs", d.totalMillis());
    }
}
