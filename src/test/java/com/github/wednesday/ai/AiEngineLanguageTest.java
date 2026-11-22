package com.github.wednesday.ai;

import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.ChatMessage;
import com.github.wednesday.common.ModelRequest;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.context.SystemPrompt;
import com.github.wednesday.testing.RecordingListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** P1-R7: the language rule travels with every request when the user switches language. */
class AiEngineLanguageTest {

    private static final Pattern ENGLISH = Pattern.compile("\\b(the|what|is|are|you|please|time)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Stand-in for a model that obeys the prompt: answers in the language of the last user message. */
    private static String obeyLanguageRule(ModelRequest r) {
        assertTrue(r.systemPrompt().toLowerCase(Locale.ROOT).contains("idioma del último mensaje"),
                "language rule must be in the system prompt of every request");
        String last = r.messages().getLast().text();
        return ENGLISH.matcher(last).find() ? "It is ten o'clock." : "Son las diez.";
    }

    private final FakeLanguageModel model = FakeLanguageModel.builder().reply(AiEngineLanguageTest::obeyLanguageRule).build();
    private final ConversationContext context = new ConversationContext(SystemPrompt.defaultPrompt(), 10);
    private final AiEngine engine = new AiEngine(model, context);

    private String say(String text) {
        var tokens = new RecordingListener<String>();
        engine.respond(text, tokens);
        assertTrue(tokens.awaitTermination(Duration.ofSeconds(3)));
        return String.join("", tokens.events());
    }

    @Test
    void switchesToEnglishAndBackWhileKeepingHistory() {
        assertEquals("Son las diez.", say("¿Qué hora es?"));
        assertEquals("It is ten o'clock.", say("What time is it?"));
        assertEquals("Son las diez.", say("Vale, ¿y en Madrid?"));

        ModelRequest last = model.requests().getLast();
        assertEquals(List.of(
                ChatMessage.user("¿Qué hora es?"), ChatMessage.assistant("Son las diez."),
                ChatMessage.user("What time is it?"), ChatMessage.assistant("It is ten o'clock."),
                ChatMessage.user("Vale, ¿y en Madrid?")), last.messages());
        assertEquals(3, model.requests().size());
        model.requests().forEach(r -> assertEquals(context.systemPrompt(), r.systemPrompt()));
    }

    @Test
    void englishMessagesArePassedThroughUnchanged() {
        say("Please tell me the time");
        assertEquals("Please tell me the time", model.requests().getLast().messages().getLast().text());
    }
}
