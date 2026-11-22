package com.github.wednesday.ai;

import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.ChatMessage;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.testing.RecordingListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiEngineTest {

    static final Duration WAIT = Duration.ofSeconds(3);

    @Test
    void streamsTokensAndRecordsBothTurns() {
        var model = FakeLanguageModel.builder().reply("Son las diez.").build();
        var context = new ConversationContext("Eres Wednesday.", 10);
        var engine = new AiEngine(model, context);
        var tokens = new RecordingListener<String>();

        engine.respond("¿Qué hora es?", tokens);

        assertTrue(tokens.awaitTermination(WAIT));
        assertEquals("Son las diez.", String.join("", tokens.events()));
        assertEquals(List.of(new ConversationContext.Turn("¿Qué hora es?", "Son las diez.")), context.turns());
    }

    @Test
    void sendsHistoryAndSystemPromptToTheModel() {
        var model = FakeLanguageModel.builder().reply("Vale.").build();
        var context = new ConversationContext("Eres Wednesday.", 10);
        var engine = new AiEngine(model, context);

        var first = new RecordingListener<String>();
        engine.respond("Me llamo Ana.", first);
        assertTrue(first.awaitTermination(WAIT));
        var second = new RecordingListener<String>();
        engine.respond("¿Cómo me llamo?", second);
        assertTrue(second.awaitTermination(WAIT));

        var request = model.requests().getLast();
        assertEquals("Eres Wednesday.", request.systemPrompt());
        assertEquals(List.of(ChatMessage.user("Me llamo Ana."), ChatMessage.assistant("Vale."),
                ChatMessage.user("¿Cómo me llamo?")), request.messages());
    }

    @Test
    void toolCallsAreIgnoredForNow() {
        var model = FakeLanguageModel.builder().reply("Hecho.").toolCall("1", "x", "{}").build();
        var engine = new AiEngine(model, new ConversationContext("p", 5));
        var tokens = new RecordingListener<String>();

        engine.respond("haz algo", tokens);

        assertTrue(tokens.awaitTermination(WAIT));
        assertEquals(List.of("Hecho."), tokens.events());
    }

    @Test
    void cancellingTheResponseCancelsTheModelAndRecordsNothing() throws Exception {
        var model = FakeLanguageModel.builder().reply("uno dos tres cuatro cinco seis")
                .tokenDelay(Duration.ofMillis(50)).build();
        var context = new ConversationContext("p", 5);
        var engine = new AiEngine(model, context);
        var tokens = new RecordingListener<String>();

        var handle = engine.respond("cuenta", tokens);
        Thread.sleep(80);
        handle.cancel();
        Thread.sleep(150);

        assertTrue(tokens.events().size() < 6);
        assertTrue(context.turns().isEmpty());
    }
}
