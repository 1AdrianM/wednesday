package com.github.wednesday.ai;

import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.ModelRequest;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.testing.RecordingListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** P1-R5: timeouts, a single retry, and the spoken error path. */
class AiEngineFailureTest {

    static final Duration WAIT = Duration.ofSeconds(3);
    static final AiEngine.Settings FAST = new AiEngine.Settings(Duration.ofMillis(100), Duration.ofMillis(400), 2);
    static final RuntimeException DOWN = new IllegalStateException("provider down");

    /** Each call goes to the next model in the list (the last one repeats). */
    static final class SequenceModel implements LanguageModel {
        private final List<LanguageModel> models;
        private final AtomicInteger calls = new AtomicInteger();

        SequenceModel(LanguageModel... models) {
            this.models = List.of(models);
        }

        @Override
        public StreamHandle generate(ModelRequest request, StreamListener<ModelEvent> listener) {
            int i = Math.min(calls.getAndIncrement(), models.size() - 1);
            return models.get(i).generate(request, listener);
        }

        int calls() {
            return calls.get();
        }
    }

    private final ConversationContext context = new ConversationContext("p", 5);

    private String run(LanguageModel model, RecordingListener<String> tokens) {
        new AiEngine(model, context, FAST).respond("hola", tokens);
        assertTrue(tokens.awaitTermination(WAIT));
        assertEquals(1, tokens.completions(), "the engine always completes normally");
        return String.join("", tokens.events());
    }

    @Test
    void retriesOnceWhenTheFirstCallFailsBeforeAnyText() {
        var model = new SequenceModel(
                FakeLanguageModel.builder().reply("x").failAfter(0, DOWN).build(),
                FakeLanguageModel.builder().reply("Hola de nuevo.").build());

        assertEquals("Hola de nuevo.", run(model, new RecordingListener<>()));
        assertEquals(2, model.calls());
        assertEquals("Hola de nuevo.", context.turns().getFirst().assistant());
    }

    @Test
    void speaksAnApologyWhenEveryAttemptFails() {
        var model = new SequenceModel(FakeLanguageModel.builder().reply("x").failAfter(0, DOWN).build());

        assertEquals(AiEngine.FAILURE_MESSAGE, run(model, new RecordingListener<>()));
        assertEquals(2, model.calls(), "one retry only");
        assertTrue(context.turns().isEmpty(), "failed exchanges are not recorded");
    }

    @Test
    void firstTokenTimeoutTriggersTheRetry() {
        var model = new SequenceModel(
                FakeLanguageModel.builder().reply("tarde").firstTokenDelay(Duration.ofSeconds(2)).build(),
                FakeLanguageModel.builder().reply("A tiempo.").build());

        long start = System.nanoTime();
        String reply = run(model, new RecordingListener<>());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("A tiempo.", reply);
        assertTrue(elapsedMs < 1000, "gave up on the slow call after ~100 ms, took " + elapsedMs);
    }

    @Test
    void failureAfterPartialTextIsNotRetriedAndSaysItWasCutOff() {
        var model = new SequenceModel(FakeLanguageModel.builder().reply("Son las diez y").failAfter(2, DOWN).build());

        assertEquals("Son las Perdona, se me ha cortado la respuesta.", run(model, new RecordingListener<>()));
        assertEquals(1, model.calls());
        assertTrue(context.turns().isEmpty());
    }

    @Test
    void totalTimeoutCutsOffASlowReply() {
        var model = new SequenceModel(FakeLanguageModel.builder().reply("uno dos tres cuatro cinco seis siete")
                .tokenDelay(Duration.ofMillis(150)).build());

        String reply = run(model, new RecordingListener<>());

        assertTrue(reply.startsWith("uno "), reply);
        assertTrue(reply.endsWith(" Perdona, se me ha cortado la respuesta."), reply);
        assertFalse(reply.contains("  "), reply);
        assertEquals(1, model.calls());
    }
}
