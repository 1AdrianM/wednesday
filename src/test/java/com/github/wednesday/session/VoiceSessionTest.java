package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.stt.SpeechToText;
import com.github.wednesday.stt.SttStream;
import com.github.wednesday.stt.TranscriptEvent;
import com.github.wednesday.stt.fake.FakeSpeechToText;
import com.github.wednesday.tts.TextToSpeech;
import com.github.wednesday.tts.fake.FakeTextToSpeech;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.github.wednesday.session.SessionState.*;
import static org.junit.jupiter.api.Assertions.*;

class VoiceSessionTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final String REPLY = "Son las diez. Que tengas buen día.";

    private final List<SessionEvent> events = new CopyOnWriteArrayList<>();
    private VoiceSession session;

    private VoiceSession open(SpeechToText stt, FakeLanguageModel llm, TextToSpeech tts) {
        var context = new ConversationContext("p", 5);
        session = new VoiceSession(SessionId.random(), stt, new AiEngine(llm, context), tts, context,
                Timeline.system(), SpokenReply.Settings.DEFAULT, events::add);
        return session;
    }

    private VoiceSession openDefault() {
        return open(FakeSpeechToText.builder().transcript("¿Qué hora es?").chunksPerWord(2).build(),
                FakeLanguageModel.builder().reply(REPLY).build(),
                FakeTextToSpeech.builder().chunksPerText(2).build());
    }

    @AfterEach
    void close() {
        if (session != null) {
            session.close();
        }
    }

    private static void speak(VoiceSession s, int frames) {
        for (int i = 0; i < frames; i++) {
            s.audio(AudioChunk.of(new byte[640]));
        }
    }

    private void await(Predicate<SessionEvent> condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (events.stream().anyMatch(condition)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for " + what + "; got " + events);
    }

    private List<SessionState> states() {
        return events.stream().filter(e -> e instanceof SessionEvent.StateChanged)
                .map(e -> ((SessionEvent.StateChanged) e).state()).toList();
    }

    private static Predicate<SessionEvent> state(SessionState s) {
        return e -> e.equals(new SessionEvent.StateChanged(s));
    }

    @Test
    void voiceTurnGoesThroughEveryState() throws Exception {
        VoiceSession s = openDefault();
        s.startTalking();
        speak(s, 8);
        s.stopTalking();
        await(e -> e instanceof SessionEvent.TurnDone, "turn done");
        await(state(IDLE), "back to idle");

        assertEquals(List.of(LISTENING, THINKING, SPEAKING, IDLE), states());
        assertTrue(events.contains(new SessionEvent.Transcript("¿Qué", false)), "partials while speaking");
        assertTrue(events.contains(new SessionEvent.Transcript("¿Qué hora es?", true)));
        int finalAt = events.indexOf(new SessionEvent.Transcript("¿Qué hora es?", true));
        int thinkingAt = events.indexOf(new SessionEvent.StateChanged(THINKING));
        assertTrue(finalAt < thinkingAt, "thinking starts after the final transcript");
        assertTrue(events.stream().anyMatch(e -> e instanceof SessionEvent.Audio), "reply audio is sent");
        assertEquals(REPLY, events.stream().filter(e -> e instanceof SessionEvent.Token)
                .map(e -> ((SessionEvent.Token) e).text()).reduce("", String::concat));
        var done = (SessionEvent.TurnDone) events.stream().filter(e -> e instanceof SessionEvent.TurnDone).findFirst().orElseThrow();
        assertTrue(done.firstAudioMillis() >= 0, "first audio measured from end of speech");
        assertTrue(done.voice());
    }

    @Test
    void nothingHeardGoesBackToIdleWithoutThinking() throws Exception {
        VoiceSession s = open(FakeSpeechToText.builder().transcript("").build(),
                FakeLanguageModel.builder().reply(REPLY).build(), FakeTextToSpeech.builder().build());
        s.startTalking();
        speak(s, 3);
        s.stopTalking();
        await(state(IDLE), "idle");
        assertEquals(List.of(LISTENING, IDLE), states());
        assertTrue(events.stream().anyMatch(e -> e instanceof SessionEvent.Error));
    }

    @Test
    void sttFailureIsReportedAndTheSessionRecovers() throws Exception {
        SpeechToText broken = listener -> failingStream(listener);
        VoiceSession s = open(broken, FakeLanguageModel.builder().reply(REPLY).build(), FakeTextToSpeech.builder().build());
        s.startTalking();
        await(state(IDLE), "idle after STT failure");
        assertEquals(List.of(LISTENING, IDLE), states());
        assertTrue(events.stream().anyMatch(e -> e instanceof SessionEvent.Error));
        s.userText("hola");
        await(e -> e instanceof SessionEvent.TurnDone, "a later turn still works");
    }

    @Test
    void typedTextIsAnsweredWithVoice() throws Exception {
        VoiceSession s = openDefault();
        s.userText("¿Qué hora es?");
        await(e -> e instanceof SessionEvent.TurnDone, "turn done");
        await(state(IDLE), "idle");
        assertEquals(List.of(THINKING, SPEAKING, IDLE), states());
        var done = (SessionEvent.TurnDone) events.stream().filter(e -> e instanceof SessionEvent.TurnDone).findFirst().orElseThrow();
        assertFalse(done.voice());
    }

    @Test
    void cannotStartTalkingWhileSpeaking() throws Exception {
        VoiceSession s = open(FakeSpeechToText.builder().build(), FakeLanguageModel.builder().reply(REPLY).build(),
                FakeTextToSpeech.builder().chunksPerText(20).chunkDelay(Duration.ofMillis(50)).build());
        s.userText("hola");
        await(state(SPEAKING), "speaking");
        s.startTalking();
        await(e -> e instanceof SessionEvent.Error, "rejection");
        assertFalse(states().contains(LISTENING), "barge-in is Phase 4");
    }

    @Test
    void audioOutsideListeningIsIgnored() throws Exception {
        var stt = FakeSpeechToText.builder().build();
        VoiceSession s = open(stt, FakeLanguageModel.builder().reply(REPLY).build(), FakeTextToSpeech.builder().build());
        speak(s, 5);
        s.report();
        await(e -> e instanceof SessionEvent.Report, "report");
        assertEquals(0, stt.audioBytesReceived());
    }

    @Test
    void resetWhileSpeakingStopsTheReply() throws Exception {
        VoiceSession s = open(FakeSpeechToText.builder().build(), FakeLanguageModel.builder().reply(REPLY).build(),
                FakeTextToSpeech.builder().chunksPerText(50).chunkDelay(Duration.ofMillis(20)).build());
        s.userText("hola");
        await(state(SPEAKING), "speaking");
        s.reset();
        await(e -> e instanceof SessionEvent.Reset, "reset");
        await(state(IDLE), "idle");
        Thread.sleep(200);
        int resetAt = events.indexOf(new SessionEvent.Reset());
        assertTrue(events.subList(resetAt, events.size()).stream()
                .noneMatch(e -> e instanceof SessionEvent.Audio || e instanceof SessionEvent.TurnDone),
                "nothing from the old reply after reset: " + events.subList(resetAt, events.size()));
    }

    @Test
    void closeStopsEverythingAndSilencesTheSession() throws Exception {
        VoiceSession s = open(FakeSpeechToText.builder().build(), FakeLanguageModel.builder().reply(REPLY).build(),
                FakeTextToSpeech.builder().chunksPerText(50).chunkDelay(Duration.ofMillis(20)).build());
        s.userText("hola");
        await(state(SPEAKING), "speaking");
        s.close();
        s.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int count = events.size();
        Thread.sleep(200);
        assertEquals(count, events.size(), "no events after close");
    }

    private static SttStream failingStream(StreamListener<TranscriptEvent> listener) {
        var stream = VirtualThreadStream.<TranscriptEvent>start("broken-stt", listener, emit -> {
            throw new IllegalStateException("stt down");
        });
        return new SttStream() {
            public void send(AudioChunk audio) { }
            public void endOfAudio() { }
            public void cancel() { stream.cancel(); }
            public boolean isActive() { return stream.isActive(); }
            public CompletionStage<Void> completion() { return stream.completion(); }
        };
    }
}
