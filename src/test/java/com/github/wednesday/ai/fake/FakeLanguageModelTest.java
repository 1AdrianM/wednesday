package com.github.wednesday.ai.fake;

import com.github.wednesday.ai.ChatMessage;
import com.github.wednesday.ai.ModelEvent;
import com.github.wednesday.ai.ModelRequest;
import com.github.wednesday.testing.RecordingListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FakeLanguageModelTest {

    static final ModelRequest REQUEST = new ModelRequest("sys", List.of(ChatMessage.user("hola")));
    static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void streamsCannedReplyAsWordTokens() {
        var model = FakeLanguageModel.builder().reply("Hola, ¿qué tal?").build();
        var listener = new RecordingListener<ModelEvent>();

        model.generate(REQUEST, listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(List.of(new ModelEvent.Token("Hola, "), new ModelEvent.Token("¿qué "),
                new ModelEvent.Token("tal?")), listener.events());
        assertEquals(List.of(REQUEST), model.requests());
    }

    @Test
    void replyCanDependOnTheRequest() {
        var model = FakeLanguageModel.builder()
                .reply(r -> "eco " + r.messages().getLast().text()).build();
        var listener = new RecordingListener<ModelEvent>();

        model.generate(REQUEST, listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(new ModelEvent.Token("hola"), listener.events().getLast());
    }

    @Test
    void honoursFirstTokenAndPerTokenDelays() {
        var model = FakeLanguageModel.builder().reply("a b c")
                .firstTokenDelay(Duration.ofMillis(100)).tokenDelay(Duration.ofMillis(50)).build();
        var listener = new RecordingListener<ModelEvent>();

        long start = System.nanoTime();
        model.generate(REQUEST, listener);
        assertTrue(listener.awaitTermination(WAIT));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 200, "100 ms first + 2 x 50 ms, was " + elapsedMs);
    }

    @Test
    void emitsToolCallsBeforeText() {
        var model = FakeLanguageModel.builder().reply("listo")
                .toolCall("1", "current_time", "{}").build();
        var listener = new RecordingListener<ModelEvent>();

        model.generate(REQUEST, listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(new ModelEvent.ToolCall("1", "current_time", "{}"), listener.events().getFirst());
    }

    @Test
    void canFailMidStream() {
        var boom = new IllegalStateException("provider down");
        var model = FakeLanguageModel.builder().reply("uno dos tres").failAfter(1, boom).build();
        var listener = new RecordingListener<ModelEvent>();

        model.generate(REQUEST, listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(1, listener.events().size());
        assertSame(boom, listener.error());
    }

    @Test
    void cancelStopsTokens() throws Exception {
        var model = FakeLanguageModel.builder().reply("a b c d e f g h")
                .tokenDelay(Duration.ofMillis(30)).build();
        var listener = new RecordingListener<ModelEvent>();

        var handle = model.generate(REQUEST, listener);
        Thread.sleep(50);
        handle.cancel();
        int seen = listener.events().size();
        Thread.sleep(100);

        assertEquals(seen, listener.events().size());
        assertTrue(seen < 8);
        assertEquals(0, listener.completions());
    }
}
