package com.github.wednesday.ai;

import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.ai.provider.gemini.GeminiLanguageModel;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** Picks a {@link LanguageModel} implementation by name, so callers never touch provider types. */
public final class LanguageModels {

    private LanguageModels() {
    }

    /** Same as {@link #create(String, Map)} with the process environment. */
    public static LanguageModel create(String provider) {
        return create(provider, System.getenv());
    }

    /**
     * @param provider {@code gemini} (needs {@code GEMINI_API_KEY}) or {@code fake} (offline echo)
     * @param settings where provider settings such as {@code GEMINI_API_KEY} are read from
     */
    public static LanguageModel create(String provider, Map<String, String> settings) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "gemini" -> new GeminiLanguageModel(GeminiLanguageModel.Config.fromEnvironment(settings));
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
