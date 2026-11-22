package com.github.wednesday.testing;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.stt.TranscriptEvent;
import com.github.wednesday.stt.fake.FakeSpeechToText;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MicrophoneSimulatorTest {

    @ParameterizedTest
    @ValueSource(strings = {MicrophoneSimulator.SPANISH, MicrophoneSimulator.ENGLISH})
    void fixturesAreShortSpeechIn20msFrames(String fixture) {
        byte[] pcm = MicrophoneSimulator.loadPcm(fixture);
        double seconds = pcm.length / (MicrophoneSimulator.SAMPLE_RATE * 2.0);
        assertTrue(seconds > 1 && seconds < 5, "duration " + seconds);
        assertTrue(rms(pcm) > 500, "fixture contains audible speech");

        List<AudioChunk> frames = MicrophoneSimulator.frames(pcm);
        assertEquals((pcm.length + 639) / 640, frames.size());
        assertTrue(frames.stream().allMatch(f -> f.size() == 640));
    }

    @Test
    void rejectsWrongFormat() {
        var eightKhz = new AudioFormat(8000, 16, 1, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> MicrophoneSimulator.requireExpectedFormat(eightKhz, "x.wav"));
        var stereo = new AudioFormat(16000, 16, 2, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> MicrophoneSimulator.requireExpectedFormat(stereo, "x.wav"));
    }

    @Test
    void pacesFramesInRealTimeScaledBySpeed() {
        List<AudioChunk> received = new ArrayList<>();
        int frameCount = MicrophoneSimulator.frames(MicrophoneSimulator.loadPcm(MicrophoneSimulator.SPANISH)).size();
        double speed = 4.0;

        long start = System.nanoTime();
        MicrophoneSimulator.stream(MicrophoneSimulator.SPANISH, received::add, speed);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long expectedMs = (long) ((frameCount - 1) * 20 / speed);
        assertEquals(frameCount, received.size());
        assertTrue(elapsedMs >= expectedMs, "elapsed " + elapsedMs + " < " + expectedMs);
    }

    @Test
    void streamsIntoSpeechToTextLikeAMicrophone() {
        var stt = FakeSpeechToText.builder().transcript("hola wednesday qué hora es").build();
        var listener = new RecordingListener<TranscriptEvent>();
        var stream = stt.open(listener);

        MicrophoneSimulator.stream(MicrophoneSimulator.SPANISH, stream::send, 0);
        stream.endOfAudio();

        assertTrue(listener.awaitTermination(Duration.ofSeconds(2)));
        assertEquals(new TranscriptEvent.Final("hola wednesday qué hora es"), listener.events().getLast());
        int frames = MicrophoneSimulator.frames(MicrophoneSimulator.loadPcm(MicrophoneSimulator.SPANISH)).size();
        assertEquals((long) frames * MicrophoneSimulator.FRAME_BYTES, stt.audioBytesReceived());
    }

    private static double rms(byte[] pcm) {
        double sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            sum += (double) s * s;
        }
        return Math.sqrt(sum / n);
    }
}
