package com.github.wednesday.ai;

import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.ai.provider.gemini.GeminiLanguageModel;

import java.time.Duration;
import java.util.Locale;

/** Picks a {@link LanguageModel} implementation by name, so callers never touch provider types. */
public final class LanguageModels {

    private LanguageModels() {
    }

    /**
     * @param provider {@code gemini} (needs {@code GEMINI_API_KEY}) or {@code fake} (offline echo)
     */
    public static LanguageModel create(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "gemini" -> GeminiLanguageModel.fromEnvironment();
            case "fake" -> FakeLanguageModel.builder()
                    .reply(r -> "Modo de prueba. Me has dicho: " + r.messages().getLast().text()
                            + ". Llevamos " + (r.messages().size() / 2 + 1) + " turnos.")
                    .firstTokenDelay(Duration.ofMillis(300))
                    .tokenDelay(Duration.ofMillis(40))
                    .build();
            default -> throw new IllegalArgumentException("unknown model provider: " + provider);
        };
    }
}
