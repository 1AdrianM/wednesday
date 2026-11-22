package com.github.wednesday.stt.fake;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.stt.TranscriptEvent;
import com.github.wednesday.testing.RecordingListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FakeSpeechToTextTest {

    static final Duration WAIT = Duration.ofSeconds(2);
    static final AudioChunk FRAME = AudioChunk.of(new byte[640]);

    @Test
    void revealsPartialsAsAudioArrivesThenFinal() {
        var stt = FakeSpeechToText.builder().transcript("qué hora es").chunksPerWord(2).build();
        var listener = new RecordingListener<TranscriptEvent>();

        var stream = stt.open(listener);
        for (int i = 0; i < 6; i++) {
            stream.send(FRAME);
        }
        stream.endOfAudio();

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(List.of(
                new TranscriptEvent.Partial("qué"),
                new TranscriptEvent.Partial("qué hora"),
                new TranscriptEvent.Partial("qué hora es"),
                new TranscriptEvent.Final("qué hora es")), listener.events());
        assertEquals(6L * 640, stt.audioBytesReceived());
    }

    @Test
    void finalArrivesAfterConfiguredDelay() {
        var stt = FakeSpeechToText.builder().finalDelay(Duration.ofMillis(150)).build();
        var listener = new RecordingListener<TranscriptEvent>();

        var stream = stt.open(listener);
        long start = System.nanoTime();
        stream.endOfAudio();
        assertTrue(listener.awaitTermination(WAIT));

        assertTrue((System.nanoTime() - start) / 1_000_000 >= 150);
        assertInstanceOf(TranscriptEvent.Final.class, listener.events().getLast());
    }

    @Test
    void cancelEndsStreamWithoutFinal() throws Exception {
        var stt = FakeSpeechToText.builder().build();
        var listener = new RecordingListener<TranscriptEvent>();

        var stream = stt.open(listener);
        stream.send(FRAME);
        stream.cancel();
        stream.send(FRAME);
        Thread.sleep(50);

        assertFalse(stream.isActive());
        assertTrue(listener.events().stream().noneMatch(e -> e instanceof TranscriptEvent.Final));
        assertEquals(0, listener.completions());
    }
}
