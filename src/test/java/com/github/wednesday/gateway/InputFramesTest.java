package com.github.wednesday.gateway;

import com.github.wednesday.common.AudioFormats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InputFramesTest {

    @Test
    void decidedFormatIs16kHzMono16BitIn20msFrames() {
        assertEquals(16_000, AudioFormats.INPUT.sampleRate());
        assertEquals(1, AudioFormats.INPUT.channels());
        assertEquals(20, AudioFormats.INPUT_FRAME_MILLIS);
        assertEquals(640, AudioFormats.INPUT_FRAME_BYTES);
    }

    @Test
    void acceptsExactlyOneFrame() {
        assertNull(InputFrames.problem(new byte[640]));
    }

    @Test
    void rejectsAnythingElse() {
        assertNotNull(InputFrames.problem(new byte[0]));
        assertNotNull(InputFrames.problem(new byte[639]));
        assertNotNull(InputFrames.problem(new byte[641]));
        assertNotNull(InputFrames.problem(new byte[1280]));
        assertNotNull(InputFrames.problem(null));
    }
}
