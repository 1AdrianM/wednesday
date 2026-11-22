package com.github.wednesday.tts.fake;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.testing.RecordingListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FakeTextToSpeechTest {

    static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void emitsConfiguredChunksAndRecordsText() {
        var tts = FakeTextToSpeech.builder().chunksPerText(4).chunkBytes(320).build();
        var listener = new RecordingListener<AudioChunk>();

        tts.synthesize("Hola.", listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertEquals(4, listener.events().size());
        assertTrue(listener.events().stream().allMatch(c -> c.size() == 320));
        assertEquals(List.of("Hola."), tts.texts());
    }

    @Test
    void honoursFirstChunkDelay() {
        var tts = FakeTextToSpeech.builder().firstChunkDelay(Duration.ofMillis(120)).chunksPerText(1).build();
        var listener = new RecordingListener<AudioChunk>();

        long start = System.nanoTime();
        tts.synthesize("x", listener);
        assertTrue(listener.awaitTermination(WAIT));

        assertTrue((System.nanoTime() - start) / 1_000_000 >= 120);
    }

    @Test
    void canFail() {
        var boom = new IllegalStateException("tts down");
        var tts = FakeTextToSpeech.builder().failWith(boom).build();
        var listener = new RecordingListener<AudioChunk>();

        tts.synthesize("x", listener);

        assertTrue(listener.awaitTermination(WAIT));
        assertSame(boom, listener.error());
        assertTrue(listener.events().isEmpty());
    }
}
