package com.github.wednesday.gateway;

import com.github.wednesday.common.AudioFormats;

/** Enforces the input audio format at the edge: every binary message is exactly one 20 ms frame. */
final class InputFrames {

    private InputFrames() {
    }

    /** {@code null} if {@code frame} is a valid input frame, otherwise a short reason. */
    static String problem(byte[] frame) {
        if (frame == null || frame.length != AudioFormats.INPUT_FRAME_BYTES) {
            return "Trama de audio no válida: se esperan " + AudioFormats.INPUT_FRAME_BYTES
                    + " bytes (20 ms de PCM 16 kHz, 16 bits, mono).";
        }
        return null;
    }
}
