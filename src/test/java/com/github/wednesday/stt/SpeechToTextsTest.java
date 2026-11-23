package com.github.wednesday.stt;

import com.github.wednesday.stt.fake.FakeSpeechToText;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpeechToTextsTest {

    @Test
    void createsTheFake() {
        assertInstanceOf(FakeSpeechToText.class, SpeechToTexts.create("fake", Map.of()));
    }

    @Test
    void geminiNeedsAKey() {
        var e = assertThrows(IllegalArgumentException.class, () -> SpeechToTexts.create("gemini", Map.of()));
        assertTrue(e.getMessage().contains("GEMINI_API_KEY"));
    }

    @Test
    void geminiWithKeyIsCreatedWithoutConnecting() {
        assertNotNull(SpeechToTexts.create("gemini", Map.of("GEMINI_API_KEY", "k")));
    }

    @Test
    void unknownProviderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SpeechToTexts.create("nope", Map.of()));
    }
}
