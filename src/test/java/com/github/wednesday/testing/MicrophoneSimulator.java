package com.github.wednesday.testing;

import com.github.wednesday.common.AudioChunk;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plays WAV fixtures as if they came from a microphone: 16 kHz, 16-bit mono PCM split into 20 ms
 * frames (640 bytes), optionally paced in real time.
 */
public final class MicrophoneSimulator {

    public static final int SAMPLE_RATE = 16_000;
    public static final int FRAME_MILLIS = 20;
    public static final int FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MILLIS / 1000;

    public static final String SPANISH = "/audio/es-que-hora-es.wav";
    public static final String ENGLISH = "/audio/en-what-time-is-it.wav";

    private MicrophoneSimulator() {
    }

    /** Reads a WAV from the classpath and returns its PCM bytes; rejects any other format. */
    public static byte[] loadPcm(String resource) {
        InputStream raw = MicrophoneSimulator.class.getResourceAsStream(resource);
        if (raw == null) {
            throw new IllegalArgumentException("fixture not found: " + resource);
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw))) {
            requireExpectedFormat(in.getFormat(), resource);
            return in.readAllBytes();
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("not a WAV file: " + resource, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void requireExpectedFormat(AudioFormat f, String name) {
        boolean ok = f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && f.getSampleRate() == SAMPLE_RATE
                && f.getSampleSizeInBits() == 16
                && f.getChannels() == 1
                && !f.isBigEndian();
        if (!ok) {
            throw new IllegalArgumentException(name + " must be 16 kHz 16-bit mono PCM LE, was " + f);
        }
    }

    /** Splits PCM into 20 ms frames; the last frame is zero-padded. */
    public static List<AudioChunk> frames(byte[] pcm) {
        List<AudioChunk> frames = new ArrayList<>();
        for (int offset = 0; offset < pcm.length; offset += FRAME_BYTES) {
            int len = Math.min(FRAME_BYTES, pcm.length - offset);
            frames.add(len == FRAME_BYTES
                    ? AudioChunk.of(pcm, offset, len)
                    : AudioChunk.of(Arrays.copyOf(Arrays.copyOfRange(pcm, offset, offset + len), FRAME_BYTES)));
        }
        return frames;
    }

    /**
     * Sends every frame of a fixture to {@code sink} on the calling thread.
     *
     * @param speed 1.0 = real time, 2.0 = twice as fast, 0 = no pacing (as fast as possible)
     */
    public static void stream(String resource, Consumer<AudioChunk> sink, double speed) {
        List<AudioChunk> frames = frames(loadPcm(resource));
        long start = System.nanoTime();
        long frameNanos = speed > 0 ? (long) (FRAME_MILLIS * 1_000_000L / speed) : 0;
        for (int i = 0; i < frames.size(); i++) {
            long due = start + i * frameNanos;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            sink.accept(frames.get(i));
        }
    }
}
