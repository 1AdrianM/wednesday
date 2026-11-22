package com.github.wednesday.common;

/** Raw PCM layout: signed 16-bit little-endian samples at {@code sampleRate}, {@code channels} interleaved. */
public record PcmFormat(int sampleRate, int channels) {

    public PcmFormat {
        if (sampleRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("sampleRate and channels must be positive");
        }
    }

    /** Bytes per second of audio. */
    public int bytesPerSecond() {
        return sampleRate * channels * 2;
    }

    /** Duration in milliseconds of {@code bytes} of audio. */
    public long millis(long bytes) {
        return bytes * 1000 / bytesPerSecond();
    }
}
