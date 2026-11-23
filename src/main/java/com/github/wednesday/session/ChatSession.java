package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.common.LatencyReport;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.SessionLogContext;
import com.github.wednesday.common.Stage;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One client's text conversation: owns its context, engine and timeline, runs one reply at a
 * time, and reports everything as {@link SessionEvent}s.
 */
public final class ChatSession implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ChatSession.class);

    /** First-response latency target from the spec (p50 under 1.2 s). */
    public static final long TARGET_MILLIS = 1200;

    private final SessionId id;
    private final AiEngine engine;
    private final ConversationContext context;
    private final Timeline timeline;
    private final Consumer<SessionEvent> out;
    private final List<SessionEvent.TurnDone> finished = new ArrayList<>();
    private final SessionStateMachine state;
    private StreamHandle inFlight;
    private boolean closed;

    ChatSession(SessionId id, AiEngine engine, ConversationContext context, Timeline timeline,
                Consumer<SessionEvent> out) {
        this.id = Objects.requireNonNull(id);
        this.engine = Objects.requireNonNull(engine);
        this.context = Objects.requireNonNull(context);
        this.timeline = Objects.requireNonNull(timeline);
        this.out = Objects.requireNonNull(out);
        this.state = new SessionStateMachine(s -> out.accept(new SessionEvent.StateChanged(s)));
    }

    public SessionId id() {
        return id;
    }

    /** Starts a reply to {@code text}; rejected while another reply is still running. */
    public synchronized void userText(String text) {
        if (closed) {
            return;
        }
        if (text == null || text.isBlank()) {
            out.accept(new SessionEvent.Error("El mensaje está vacío."));
            return;
        }
        if (inFlight != null && inFlight.isActive()) {
            out.accept(new SessionEvent.Error("Espera a que termine de responder."));
            return;
        }
        try (var scope = SessionLogContext.open(id)) {
            int turn = timeline.beginTurn();
            timeline.mark(Stage.REQUEST_SENT);
            state.moveTo(SessionState.THINKING);
            inFlight = engine.respond(text.strip(), new StreamListener<>() {
                @Override
                public void onEvent(String token) {
                    timeline.mark(Stage.FIRST_TOKEN);
                    out.accept(new SessionEvent.Token(token));
                }

                @Override
                public void onError(Throwable error) {
                    LOG.errorf("reply failed: %s", error.toString());
                    out.accept(new SessionEvent.Error("Algo ha fallado al responder."));
                    state.moveTo(SessionState.IDLE);
                }

                @Override
                public void onComplete() {
                    timeline.mark(Stage.LAST_TOKEN);
                    var done = new SessionEvent.TurnDone(turn, false,
                            timeline.between(turn, Stage.REQUEST_SENT, Stage.FIRST_TOKEN).orElse(-1L), -1L,
                            timeline.between(turn, Stage.REQUEST_SENT, Stage.LAST_TOKEN).orElse(-1L));
                    synchronized (ChatSession.this) {
                        finished.add(done);
                    }
                    out.accept(done);
                    state.moveTo(SessionState.IDLE);
                }
            });
        }
    }

    /** Forgets the conversation (latency history is kept). Cancels a running reply. */
    public synchronized void reset() {
        cancelInFlight();
        context.clear();
        out.accept(new SessionEvent.Reset());
        if (state.current() == SessionState.IDLE) {
            out.accept(new SessionEvent.StateChanged(SessionState.IDLE));
        } else {
            state.moveTo(SessionState.IDLE);
        }
    }

    /** Sends the latency rows of every finished turn. */
    public synchronized void report() {
        LatencyReport report = timeline.report();
        out.accept(new SessionEvent.Report(List.copyOf(finished),
                report.percentile(Stage.FIRST_TOKEN, 50).orElse(null),
                report.percentile(Stage.FIRST_TOKEN, 95).orElse(null), null, null));
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancelInFlight();
    }

    private void cancelInFlight() {
        if (inFlight != null) {
            inFlight.cancel();
            inFlight = null;
        }
    }
}
