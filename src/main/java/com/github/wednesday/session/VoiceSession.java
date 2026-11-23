package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.LatencyReport;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.SessionLogContext;
import com.github.wednesday.common.Stage;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.stt.SpeechToText;
import com.github.wednesday.stt.SttStream;
import com.github.wednesday.stt.TranscriptEvent;
import com.github.wednesday.tts.TextToSpeech;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * One client's voice conversation, push-to-talk: {@link #startTalking()}, audio frames,
 * {@link #stopTalking()}; the final transcript goes to the model and the reply is spoken.
 * Typed text is answered the same way, skipping the listening part.
 *
 * <p>Built as an actor: every input, from the client or from a running stream, becomes a message
 * in one queue, and a single session thread handles them in order. Nothing else touches session
 * state, so there are no locks to order and no deadlock between the session and a stream that
 * is delivering a callback while the session cancels it. Each stream's messages carry a tag
 * naming that stream; a message whose stream is no longer the current one (cancelled, finished,
 * or replaced by a reset) is dropped.
 */
public final class VoiceSession implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(VoiceSession.class);

    /** First-audio latency target from the spec (p50 under 1.2 s). */
    public static final long TARGET_MILLIS = 1200;

    private final SessionId id;
    private final SpeechToText stt;
    private final AiEngine engine;
    private final TextToSpeech tts;
    private final ConversationContext context;
    private final Timeline timeline;
    private final SpokenReply.Settings replySettings;
    private final Consumer<SessionEvent> out;
    private final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    // Session-thread state.
    private final SessionStateMachine state;
    private final List<SessionEvent.TurnDone> finished = new ArrayList<>();
    private int turn;
    private boolean voiceTurn;
    private boolean heardFinal;
    private SttStream listening;
    private Object listeningTag;
    private StreamHandle reply;
    private Object replyTag;

    public VoiceSession(SessionId id, SpeechToText stt, AiEngine engine, TextToSpeech tts,
                        ConversationContext context, Timeline timeline, SpokenReply.Settings replySettings,
                        Consumer<SessionEvent> out) {
        this.id = Objects.requireNonNull(id);
        this.stt = Objects.requireNonNull(stt);
        this.engine = Objects.requireNonNull(engine);
        this.tts = Objects.requireNonNull(tts);
        this.context = Objects.requireNonNull(context);
        this.timeline = Objects.requireNonNull(timeline);
        this.replySettings = Objects.requireNonNull(replySettings);
        this.out = Objects.requireNonNull(out);
        this.state = new SessionStateMachine(s -> out.accept(new SessionEvent.StateChanged(s)));
        Thread.ofVirtual().name("voice-session-" + id.value()).start(this::loop);
    }

    public SessionId id() {
        return id;
    }

    // ---- client inputs: each only enqueues ----

    public void startTalking() {
        inbox.add(Command.START);
    }

    public void audio(AudioChunk frame) {
        inbox.add(Objects.requireNonNull(frame));
    }

    public void stopTalking() {
        inbox.add(Command.STOP);
    }

    public void userText(String text) {
        inbox.add(new Typed(text == null ? "" : text));
    }

    public void reset() {
        inbox.add(Command.RESET);
    }

    public void report() {
        inbox.add(Command.REPORT);
    }

    /** Stops every stream and ends the session thread; no events are sent afterwards. */
    @Override
    public void close() {
        inbox.add(Command.CLOSE);
    }

    /** Completes when the session thread has stopped and released its streams. */
    public CompletionStage<Void> closed() {
        return closed;
    }

    private enum Command { START, STOP, RESET, REPORT, CLOSE }

    private record Typed(String text) {
    }

    /** Something a stream reported, tagged with the stream it came from. */
    private record FromStream(Object tag, Object event) {
    }

    private record Done(Throwable error) {
        static final Done OK = new Done(null);
    }

    // ---- session thread ----

    private void loop() {
        try (var scope = SessionLogContext.open(id)) {
            while (true) {
                Object msg = inbox.take();
                if (msg == Command.CLOSE) {
                    stopStreams();
                    LOG.info("session closed");
                    return;
                }
                try {
                    handle(msg);
                } catch (RuntimeException e) {
                    LOG.errorf(e, "session message failed");
                    out.accept(new SessionEvent.Error("Algo ha fallado en la sesión."));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopStreams();
        } finally {
            closed.complete(null);
        }
    }

    private void handle(Object msg) {
        switch (msg) {
            case Command.START -> start();
            case Command.STOP -> stop();
            case Command.RESET -> resetConversation();
            case Command.REPORT -> sendReport();
            case AudioChunk frame -> {
                if (state.current() == SessionState.LISTENING && listening != null) {
                    listening.send(frame);
                }
            }
            case Typed(String text) -> typed(text);
            case FromStream(Object tag, Object event) -> {
                if (tag == listeningTag) {
                    fromListening(event);
                } else if (tag == replyTag) {
                    fromReply(event);
                }
            }
            default -> LOG.warnf("unexpected session message %s", msg.getClass().getSimpleName());
        }
    }

    private void start() {
        if (state.current() != SessionState.IDLE) {
            out.accept(new SessionEvent.Error("Ahora no puedo escucharte; espera a que termine."));
            return;
        }
        turn = timeline.beginTurn();
        voiceTurn = true;
        heardFinal = false;
        state.moveTo(SessionState.LISTENING);
        listeningTag = new Object();
        listening = stt.open(forward(listeningTag));
    }

    private void stop() {
        if (state.current() != SessionState.LISTENING || listening == null) {
            return;
        }
        timeline.mark(Stage.SPEECH_END);
        listening.endOfAudio();
    }

    private void typed(String text) {
        if (text.isBlank()) {
            out.accept(new SessionEvent.Error("El mensaje está vacío."));
            return;
        }
        if (state.current() != SessionState.IDLE) {
            out.accept(new SessionEvent.Error("Espera a que termine de responder."));
            return;
        }
        turn = timeline.beginTurn();
        voiceTurn = false;
        respond(text.strip());
    }

    private void fromListening(Object event) {
        switch (event) {
            case TranscriptEvent.Partial(String text) -> out.accept(new SessionEvent.Transcript(text, false));
            case TranscriptEvent.Final(String text) -> heardFinal(text);
            case Done(Throwable error) -> listeningEnded(error);
            default -> LOG.warnf("unexpected STT event %s", event.getClass().getSimpleName());
        }
    }

    private void fromReply(Object event) {
        switch (event) {
            case SpokenReply.Event.Text(String token) -> out.accept(new SessionEvent.Token(token));
            case SpokenReply.Event.Audio(AudioChunk chunk) -> {
                if (state.current() == SessionState.THINKING) {
                    state.moveTo(SessionState.SPEAKING);
                }
                out.accept(new SessionEvent.Audio(chunk));
            }
            case SpokenReply.Event.Segment s -> {
            }
            case SpokenReply.Event.SpeechFailed f ->
                    out.accept(new SessionEvent.Error("La voz ha fallado; sigo por texto."));
            case Done(Throwable error) -> replyEnded(error);
            default -> LOG.warnf("unexpected reply event %s", event.getClass().getSimpleName());
        }
    }

    private void heardFinal(String text) {
        if (state.current() != SessionState.LISTENING) {
            return;
        }
        heardFinal = true;
        timeline.mark(Stage.TRANSCRIPT_FINAL);
        out.accept(new SessionEvent.Transcript(text, true));
        LOG.debugf("final transcript: %s", text);
        stopListening();
        if (text.isBlank()) {
            out.accept(new SessionEvent.Error("No te he oído. Mantén pulsado mientras hablas."));
            state.moveTo(SessionState.IDLE);
            return;
        }
        respond(text.strip());
    }

    private void listeningEnded(Throwable error) {
        listening = null;
        listeningTag = null;
        if (error != null) {
            LOG.errorf("speech recognition failed: %s", error.getMessage());
            out.accept(new SessionEvent.Error("No he podido entenderte; inténtalo otra vez."));
        } else if (!heardFinal) {
            out.accept(new SessionEvent.Error("No te he oído. Mantén pulsado mientras hablas."));
        }
        state.moveTo(SessionState.IDLE);
    }

    private void respond(String text) {
        state.moveTo(SessionState.THINKING);
        timeline.mark(Stage.REQUEST_SENT);
        replyTag = new Object();
        reply = SpokenReply.start(engine, tts, text, timeline, replySettings, forward(replyTag));
    }

    private void replyEnded(Throwable error) {
        reply = null;
        replyTag = null;
        if (error != null) {
            LOG.errorf("reply failed: %s", error.toString());
            out.accept(new SessionEvent.Error("Algo ha fallado al responder."));
        } else {
            Stage origin = voiceTurn ? Stage.SPEECH_END : Stage.REQUEST_SENT;
            var done = new SessionEvent.TurnDone(turn, voiceTurn,
                    timeline.between(turn, origin, Stage.FIRST_TOKEN).orElse(-1L),
                    timeline.between(turn, origin, Stage.FIRST_AUDIO).orElse(-1L),
                    timeline.between(turn, origin, Stage.LAST_TOKEN).orElse(-1L));
            finished.add(done);
            out.accept(done);
        }
        state.moveTo(SessionState.IDLE);
    }

    private void resetConversation() {
        stopStreams();
        context.clear();
        out.accept(new SessionEvent.Reset());
        if (state.current() == SessionState.IDLE) {
            out.accept(new SessionEvent.StateChanged(SessionState.IDLE));
        } else {
            state.moveTo(SessionState.IDLE);
        }
    }

    private void sendReport() {
        LatencyReport r = timeline.report();
        out.accept(new SessionEvent.Report(List.copyOf(finished),
                r.percentile(Stage.FIRST_TOKEN, 50).orElse(null), r.percentile(Stage.FIRST_TOKEN, 95).orElse(null),
                r.percentile(Stage.FIRST_AUDIO, 50).orElse(null), r.percentile(Stage.FIRST_AUDIO, 95).orElse(null)));
    }

    private void stopListening() {
        if (listening != null) {
            listening.cancel();
            listening = null;
        }
        listeningTag = null;
    }

    private void stopStreams() {
        stopListening();
        if (reply != null) {
            reply.cancel();
            reply = null;
        }
        replyTag = null;
    }

    /** A listener that turns a stream's callbacks into session messages. Never blocks. */
    private <E> StreamListener<E> forward(Object tag) {
        return new StreamListener<>() {
            @Override
            public void onEvent(E event) {
                inbox.add(new FromStream(tag, event));
            }

            @Override
            public void onError(Throwable error) {
                inbox.add(new FromStream(tag, new Done(error)));
            }

            @Override
            public void onComplete() {
                inbox.add(new FromStream(tag, Done.OK));
            }
        };
    }
}
