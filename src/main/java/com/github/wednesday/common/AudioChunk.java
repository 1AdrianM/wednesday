package com.github.wednesday.common;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable block of raw audio bytes. The audio format (sample rate, encoding, frame size)
 * is fixed at the Gateway (see Phase 3); this type only carries bytes.
 * {@link #toString()} never prints audio content.
 */
public final class AudioChunk {

    private final byte[] data;

    private AudioChunk(byte[] data) {
        this.data = data;
    }

    public static AudioChunk of(byte[] data) {
        return new AudioChunk(Objects.requireNonNull(data, "data").clone());
    }

    public static AudioChunk of(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        return new AudioChunk(Arrays.copyOfRange(data, offset, offset + length));
    }

    /** A copy of the audio bytes. */
    public byte[] bytes() {
        return data.clone();
    }

    public int size() {
        return data.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AudioChunk other && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "AudioChunk[" + data.length + " bytes]";
    }
}
