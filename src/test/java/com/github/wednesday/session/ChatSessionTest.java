package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatSessionTest {

    private final List<SessionEvent> events = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch turnDone = new CountDownLatch(1);
    private final ConversationContext context = new ConversationContext("p", 5);

    private ChatSession session(FakeLanguageModel model) {
        return new ChatSession(SessionId.random(), new AiEngine(model, context), context, Timeline.system(), e -> {
            events.add(e);
            if (e instanceof SessionEvent.TurnDone) {
                turnDone.countDown();
            }
        });
    }

    @Test
    void replyStreamsBetweenThinkingAndIdleThenReportsLatency() throws Exception {
        var session = session(FakeLanguageModel.builder().reply("Hola, Ana.").build());

        session.userText("hola");
        assertTrue(turnDone.await(3, TimeUnit.SECONDS));
        Thread.sleep(20);

        assertEquals(new SessionEvent.StateChanged(SessionEvent.State.THINKING), events.getFirst());
        assertEquals(List.of(new SessionEvent.Token("Hola, "), new SessionEvent.Token("Ana.")),
                events.stream().filter(SessionEvent.Token.class::isInstance).toList());
        var done = (SessionEvent.TurnDone) events.stream().filter(SessionEvent.TurnDone.class::isInstance).findFirst().orElseThrow();
        assertEquals(1, done.turn());
        assertTrue(done.firstTokenMillis() >= 0 && done.totalMillis() >= done.firstTokenMillis());
        assertEquals(new SessionEvent.StateChanged(SessionEvent.State.IDLE), events.getLast());
    }

    @Test
    void rejectsANewMessageWhileReplying() throws Exception {
        var session = session(FakeLanguageModel.builder().reply("uno dos tres").tokenDelay(Duration.ofMillis(100)).build());

        session.userText("primero");
        session.userText("segundo");
        assertTrue(turnDone.await(3, TimeUnit.SECONDS));

        assertTrue(events.contains(new SessionEvent.Error("Espera a que termine de responder.")));
        assertEquals(1, context.turns().size());
    }

    @Test
    void reportListsFinishedTurnsWithPercentiles() throws Exception {
        var session = session(FakeLanguageModel.builder().reply("Vale.").build());
        session.userText("uno");
        assertTrue(turnDone.await(3, TimeUnit.SECONDS));
        turnDone = new CountDownLatch(1);
        session.userText("dos");
        assertTrue(turnDone.await(3, TimeUnit.SECONDS));

        session.report();

        var report = (SessionEvent.Report) events.getLast();
        assertEquals(2, report.turns().size());
        assertNotNull(report.p50FirstTokenMillis());
    }

    @Test
    void resetClearsContextAndCancelsTheReply() throws Exception {
        var session = session(FakeLanguageModel.builder().reply("a b c d e f g").tokenDelay(Duration.ofMillis(80)).build());

        session.userText("cuenta");
        Thread.sleep(50);
        session.reset();
        Thread.sleep(200);

        assertTrue(events.contains(new SessionEvent.Reset()));
        assertTrue(context.turns().isEmpty());
        assertTrue(events.stream().noneMatch(SessionEvent.TurnDone.class::isInstance));
    }

    @Test
    void emptyMessageIsAnError() {
        session(FakeLanguageModel.builder().build()).userText("   ");
        assertEquals(List.of(new SessionEvent.Error("El mensaje está vacío.")), events);
    }
}
