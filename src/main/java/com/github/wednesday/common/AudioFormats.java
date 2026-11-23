package com.github.wednesday.common;

/**
 * Audio format decisions (ADR 0002). Microphone audio entering the system is 16 kHz, 16-bit
 * little-endian mono PCM in 20 ms frames; the Gateway rejects anything else.
 */
public final class AudioFormats {

    /** Microphone audio from the client. */
    public static final PcmFormat INPUT = new PcmFormat(16_000, 1);

    /** Duration of one input frame. */
    public static final int INPUT_FRAME_MILLIS = 20;

    /** Size of one input frame: 16 000 samples/s x 2 bytes x 0,020 s. */
    public static final int INPUT_FRAME_BYTES = INPUT.bytesPerSecond() * INPUT_FRAME_MILLIS / 1000;

    private AudioFormats() {
    }
}
