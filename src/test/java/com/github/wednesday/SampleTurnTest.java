package com.github.wednesday;

import com.github.wednesday.common.ChatMessage;
import com.github.wednesday.ai.ModelEvent;
import com.github.wednesday.common.ModelRequest;
import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.LatencyReport;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.SessionLogContext;
import com.github.wednesday.common.Stage;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.stt.TranscriptEvent;
import com.github.wednesday.stt.fake.FakeSpeechToText;
import com.github.wednesday.testing.MicrophoneSimulator;
import com.github.wednesday.testing.RecordingListener;
import com.github.wednesday.tts.fake.FakeTextToSpeech;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 acceptance: one scripted voice turn per fixture through the fake providers, wired by
 * hand (the real pipeline arrives in Phases 2-3), printing the latency report.
 */
class SampleTurnTest {

    private static final Logger LOG = Logger.getLogger(SampleTurnTest.class);
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final FakeSpeechToText stt = FakeSpeechToText.builder()
            .transcript("hola wednesday qué hora es").finalDelay(Duration.ofMillis(150)).build();
    private final FakeLanguageModel llm = FakeLanguageModel.builder()
            .reply("Son las diez y cuarto de la mañana.")
            .firstTokenDelay(Duration.ofMillis(300)).tokenDelay(Duration.ofMillis(20)).build();
    private final FakeTextToSpeech tts = FakeTextToSpeech.builder()
            .firstChunkDelay(Duration.ofMillis(120)).chunksPerText(5).build();

    @Test
    void scriptedTurnsWithFakesProduceALatencyReport() {
        Timeline timeline = Timeline.system();
        SessionId session = SessionId.random();

        try (var scope = SessionLogContext.open(session)) {
            for (String fixture : List.of(MicrophoneSimulator.SPANISH, MicrophoneSimulator.ENGLISH,
                    MicrophoneSimulator.SPANISH)) {
                runTurn(timeline, fixture);
            }
        }

        LatencyReport report = timeline.report();
        System.out.println(report.render());

        assertEquals(3, report.turns().size());
        for (var turn : report.turns()) {
            var o = turn.offsetsMillis();
            assertTrue(o.get(Stage.SPEECH_END) <= o.get(Stage.TRANSCRIPT_FINAL));
            assertTrue(o.get(Stage.TRANSCRIPT_FINAL) <= o.get(Stage.FIRST_TOKEN));
            assertTrue(o.get(Stage.FIRST_TOKEN) <= o.get(Stage.FIRST_AUDIO));
            assertTrue(o.get(Stage.FIRST_AUDIO) >= 150 + 300 + 120, "fake delays add up: " + o);
        }
        assertTrue(report.percentile(Stage.FIRST_AUDIO, 50).isPresent());
    }

    private void runTurn(Timeline timeline, String fixture) {
        int turn = timeline.beginTurn();

        var transcripts = new RecordingListener<TranscriptEvent>(e -> {
            if (e instanceof TranscriptEvent.Final) {
                timeline.mark(Stage.TRANSCRIPT_FINAL);
            }
        });
        var sttStream = stt.open(transcripts);
        MicrophoneSimulator.stream(fixture, sttStream::send, 0);
        timeline.mark(Stage.SPEECH_END);
        sttStream.endOfAudio();
        assertTrue(transcripts.awaitTermination(WAIT));
        String userText = transcripts.events().getLast().text();
        LOG.debugf("turn %d user: %s", turn, userText);

        timeline.mark(Stage.REQUEST_SENT);
        var tokens = new RecordingListener<ModelEvent>(e -> timeline.mark(Stage.FIRST_TOKEN));
        llm.generate(new ModelRequest("Eres Wednesday.", List.of(ChatMessage.user(userText))), tokens);
        assertTrue(tokens.awaitTermination(WAIT));
        timeline.mark(Stage.LAST_TOKEN);
        String reply = tokens.events().stream()
                .filter(ModelEvent.Token.class::isInstance).map(e -> ((ModelEvent.Token) e).text())
                .collect(Collectors.joining());

        var audio = new RecordingListener<AudioChunk>(c -> timeline.mark(Stage.FIRST_AUDIO));
        tts.synthesize(reply, audio);
        assertTrue(audio.awaitTermination(WAIT));
        timeline.mark(Stage.LAST_AUDIO);
        LOG.infof("turn %d done: %d audio chunks", turn, audio.events().size());
    }
}
