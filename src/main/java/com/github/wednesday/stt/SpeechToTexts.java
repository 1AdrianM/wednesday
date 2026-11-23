package com.github.wednesday.stt;

import com.github.wednesday.stt.fake.FakeSpeechToText;
import com.github.wednesday.stt.provider.gemini.GeminiSpeechToText;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** Creates the configured {@link SpeechToText}; the only place outside the adapter that names a provider. */
public final class SpeechToTexts {

    private SpeechToTexts() {
    }

    /**
     * @param provider {@code gemini} (needs {@code GEMINI_API_KEY}) or {@code fake} (canned transcript)
     * @param settings where provider settings are read from
     */
    public static SpeechToText create(String provider, Map<String, String> settings) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "gemini" -> new GeminiSpeechToText(GeminiSpeechToText.Config.fromEnvironment(settings));
            case "fake" -> FakeSpeechToText.builder()
                    .transcript(settings.getOrDefault("FAKE_STT_TRANSCRIPT", "¿Qué hora es?"))
                    .finalDelay(Duration.ofMillis(150)).build();
            default -> throw new IllegalArgumentException("unknown STT provider: " + provider);
        };
    }
}
