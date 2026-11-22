package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.Stage;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.testing.RecordingListener;
import com.github.wednesday.tts.fake.FakeTextToSpeech;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SpokenReplyTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final String REPLY = "Son las diez. Hace sol en Madrid. Mañana lloverá. Lleva paraguas.";

    private final Timeline timeline = Timeline.system();

    private RecordingListener<SpokenReply.Event> run(FakeLanguageModel model, FakeTextToSpeech tts) {
        var engine = new AiEngine(model, new ConversationContext("p", 5));
        var listener = new RecordingListener<SpokenReply.Event>();
        timeline.beginTurn();
        timeline.mark(Stage.REQUEST_SENT);
        SpokenReply.start(engine, tts, "¿qué tiempo hace?", timeline, SpokenReply.Settings.DEFAULT, listener);
        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(1, listener.completions());
        return listener;
    }

    private static <T> List<T> only(RecordingListener<SpokenReply.Event> l, Class<T> type) {
        return l.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void streamsTextAndSpeaksEverySentenceInOrder() {
        var tts = FakeTextToSpeech.builder().chunksPerText(2).build();
        var events = run(FakeLanguageModel.builder().reply(REPLY).tokenDelay(Duration.ofMillis(30)).build(), tts);

        assertEquals(REPLY, only(events, SpokenReply.Event.Text.class).stream()
                .map(SpokenReply.Event.Text::token).collect(Collectors.joining()));
        String spoken = String.join(" ", tts.texts());
        assertEquals(REPLY, spoken, "every word is spoken exactly once, in order");
        assertEquals(tts.texts(), only(events, SpokenReply.Event.Segment.class).stream()
                .map(SpokenReply.Event.Segment::text).toList());
        assertEquals(2 * tts.texts().size(), only(events, SpokenReply.Event.Audio.class).size());
    }

    @Test
    void firstSentenceGoesAloneThenQueuedSentencesAreBatched() {
        var slowVoice = FakeTextToSpeech.builder().firstChunkDelay(Duration.ofMillis(300)).chunksPerText(1).build();
        run(FakeLanguageModel.builder().reply(REPLY).build(), slowVoice);

        assertEquals(List.of("Son las diez.", "Hace sol en Madrid. Mañana lloverá. Lleva paraguas."), slowVoice.texts());
    }

    @Test
    void voiceFailureKeepsTheTextAndIsReportedOnce() {
        var broken = FakeTextToSpeech.builder().failWith(new IllegalStateException("tts down")).build();
        var events = run(FakeLanguageModel.builder().reply(REPLY).tokenDelay(Duration.ofMillis(20)).build(), broken);

        assertEquals(1, only(events, SpokenReply.Event.SpeechFailed.class).size());
        assertEquals(1, broken.texts().size(), "stops calling a failing voice");
        assertEquals(REPLY, only(events, SpokenReply.Event.Text.class).stream()
                .map(SpokenReply.Event.Text::token).collect(Collectors.joining()));
    }

    @Test
    void marksSegmentAndAudioStagesOnTheTimeline() {
        run(FakeLanguageModel.builder().reply(REPLY).build(), FakeTextToSpeech.builder().build());

        var o = timeline.report().turns().getFirst().offsetsMillis();
        assertTrue(o.containsKey(Stage.FIRST_TOKEN));
        assertTrue(o.get(Stage.FIRST_SEGMENT) <= o.get(Stage.FIRST_AUDIO));
        assertTrue(o.get(Stage.FIRST_AUDIO) <= o.get(Stage.LAST_AUDIO));
    }

    /** P2-R6: the voice starts on the first sentence while the model is still writing the rest. */
    @Test
    void firstSegmentAudioStartsBeforeTheModelFinishes() {
        var slowModel = FakeLanguageModel.builder().reply(REPLY).tokenDelay(Duration.ofMillis(60)).build();
        var events = run(slowModel, FakeTextToSpeech.builder().firstChunkDelay(Duration.ofMillis(20)).build());

        long firstAudio = timeline.between(1, Stage.REQUEST_SENT, Stage.FIRST_AUDIO).orElseThrow();
        long lastToken = timeline.between(1, Stage.REQUEST_SENT, Stage.LAST_TOKEN).orElseThrow();
        assertTrue(firstAudio + 300 < lastToken,
                "first audio at " + firstAudio + " ms should be well before the last token at " + lastToken + " ms");

        int firstAudioIndex = events.events().indexOf(only(events, SpokenReply.Event.Audio.class).getFirst());
        int lastTextIndex = events.events().lastIndexOf(only(events, SpokenReply.Event.Text.class).getLast());
        assertTrue(firstAudioIndex < lastTextIndex, "audio events interleave with text still arriving");
    }

    @Test
    void cancelStopsTextAndVoice() throws Exception {
        var tts = FakeTextToSpeech.builder().chunksPerText(50).chunkDelay(Duration.ofMillis(20)).build();
        var engine = new AiEngine(FakeLanguageModel.builder().reply(REPLY).tokenDelay(Duration.ofMillis(40)).build(),
                new ConversationContext("p", 5));
        var listener = new RecordingListener<SpokenReply.Event>();
        timeline.beginTurn();

        var handle = SpokenReply.start(engine, tts, "x", timeline, SpokenReply.Settings.DEFAULT, listener);
        Thread.sleep(300);
        handle.cancel();
        int seen = listener.events().size();
        Thread.sleep(300);

        assertEquals(seen, listener.events().size(), "nothing after cancel");
        assertEquals(0, listener.completions());
    }
}
