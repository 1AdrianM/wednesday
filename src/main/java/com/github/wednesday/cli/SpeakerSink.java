package com.github.wednesday.cli;

import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.PcmFormat;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Plays PCM on the default output device. A dedicated writer thread feeds the sound line, so
 * {@link #play} never blocks the stream that produces audio.
 */
final class SpeakerSink implements AudioSink {

    private final SourceDataLine line;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile boolean closed;

    private SpeakerSink(SourceDataLine line) {
        this.line = line;
        this.writer = Thread.ofPlatform().daemon().name("speaker").start(this::writeLoop);
    }

    /** Opens the default output for {@code format}, or empty when there is no usable device. */
    static Optional<SpeakerSink> open(PcmFormat format) {
        AudioFormat af = new AudioFormat(format.sampleRate(), 16, format.channels(), true, false);
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(af);
            line.open(af, format.bytesPerSecond() / 5);
            line.start();
            return Optional.of(new SpeakerSink(line));
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            return Optional.empty();
        }
    }

    private void writeLoop() {
        try {
            while (!closed) {
                Object item = queue.take();
                if (item instanceof byte[] pcm) {
                    line.write(pcm, 0, pcm.length);
                } else if (item instanceof CountDownLatch marker) {
                    line.drain();
                    marker.countDown();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void play(AudioChunk chunk) {
        if (!closed) {
            queue.add(chunk.bytes());
        }
    }

    @Override
    public void drain() {
        CountDownLatch marker = new CountDownLatch(1);
        queue.add(marker);
        try {
            marker.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        writer.interrupt();
        line.stop();
        line.close();
    }
}
