package com.github.wednesday.common;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TimelineTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Timeline timeline = new Timeline(nanos::get);

    private void advanceMillis(long ms) {
        nanos.addAndGet(ms * 1_000_000);
    }

    @Test
    void recordsOffsetsFromFirstMarkOfEachTurn() {
        timeline.beginTurn();
        advanceMillis(5);
        timeline.mark(Stage.SPEECH_END);
        advanceMillis(300);
        timeline.mark(Stage.FIRST_TOKEN);
        advanceMillis(400);
        timeline.mark(Stage.FIRST_AUDIO);

        var turn = timeline.report().turns().getFirst();
        assertEquals(Map.of(Stage.SPEECH_END, 0L, Stage.FIRST_TOKEN, 300L, Stage.FIRST_AUDIO, 700L),
                turn.offsetsMillis());
        assertEquals(Optional.of(700L), timeline.between(1, Stage.SPEECH_END, Stage.FIRST_AUDIO));
    }

    @Test
    void onlyFirstMarkOfAStageCounts() {
        timeline.beginTurn();
        timeline.mark(Stage.REQUEST_SENT);
        advanceMillis(100);
        timeline.mark(Stage.FIRST_TOKEN);
        advanceMillis(100);
        timeline.mark(Stage.FIRST_TOKEN);

        assertEquals(100L, timeline.report().turns().getFirst().offsetsMillis().get(Stage.FIRST_TOKEN));
    }

    @Test
    void marksWithoutATurnAreIgnoredAndTurnsAreSeparate() {
        timeline.mark(Stage.SPEECH_END);
        assertTrue(timeline.report().turns().isEmpty());

        timeline.beginTurn();
        timeline.mark(Stage.SPEECH_END);
        advanceMillis(1000);
        assertEquals(2, timeline.beginTurn());
        timeline.mark(Stage.SPEECH_END);
        advanceMillis(50);
        timeline.mark(Stage.FIRST_AUDIO);

        var report = timeline.report();
        assertEquals(2, report.turns().size());
        assertNull(report.turns().get(0).offsetsMillis().get(Stage.FIRST_AUDIO));
        assertEquals(50L, report.turns().get(1).offsetsMillis().get(Stage.FIRST_AUDIO));
    }

    @Test
    void percentilesUseNearestRank() {
        long[] firstAudio = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
        for (long ms : firstAudio) {
            timeline.beginTurn();
            timeline.mark(Stage.SPEECH_END);
            advanceMillis(ms);
            timeline.mark(Stage.FIRST_AUDIO);
        }

        var report = timeline.report();
        assertEquals(Optional.of(500L), report.percentile(Stage.FIRST_AUDIO, 50));
        assertEquals(Optional.of(1000L), report.percentile(Stage.FIRST_AUDIO, 95));
        assertEquals(Optional.empty(), report.percentile(Stage.LAST_AUDIO, 50));
    }

    @Test
    void renderShowsTurnsStagesAndPercentiles() {
        timeline.beginTurn();
        timeline.mark(Stage.SPEECH_END);
        advanceMillis(250);
        timeline.mark(Stage.FIRST_AUDIO);
        timeline.beginTurn();
        timeline.mark(Stage.SPEECH_END);

        String text = timeline.report().render();

        assertTrue(text.contains("SPEECH_END"), text);
        assertTrue(text.contains("FIRST_AUDIO"), text);
        assertFalse(text.contains("FIRST_TOKEN"), "only stages that were marked: " + text);
        assertTrue(text.lines().anyMatch(l -> l.startsWith("1 ") && l.contains("250")), text);
        assertTrue(text.lines().anyMatch(l -> l.startsWith("2 ") && l.trim().endsWith("-")), text);
        assertTrue(text.lines().anyMatch(l -> l.startsWith("p50")), text);
        assertTrue(text.lines().anyMatch(l -> l.startsWith("p95")), text);
    }
}
