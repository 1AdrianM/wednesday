package com.github.wednesday.tts;

import com.github.wednesday.tts.fake.FakeTextToSpeech;
import com.github.wednesday.tts.provider.gemini.GeminiTextToSpeech;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** Picks a {@link TextToSpeech} implementation by name, so callers never touch provider types. */
public final class TextToSpeechs {

    private TextToSpeechs() {
    }

    /**
     * @param provider {@code gemini} (needs {@code GEMINI_API_KEY}) or {@code fake} (silence)
     * @param settings where provider settings are read from
     */
    public static TextToSpeech create(String provider, Map<String, String> settings) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "gemini" -> new GeminiTextToSpeech(GeminiTextToSpeech.Config.fromEnvironment(settings));
            case "fake" -> FakeTextToSpeech.builder()
                    .firstChunkDelay(Duration.ofMillis(200)).chunkDelay(Duration.ofMillis(20))
                    .chunksPerText(10).chunkBytes(4800).build();
            default -> throw new IllegalArgumentException("unknown TTS provider: " + provider);
        };
    }
}
