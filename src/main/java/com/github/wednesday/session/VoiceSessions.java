package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.LanguageModel;
import com.github.wednesday.ai.LanguageModels;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.SessionLogContext;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.context.SystemPrompt;
import com.github.wednesday.stt.SpeechToText;
import com.github.wednesday.stt.SpeechToTexts;
import com.github.wednesday.tts.TextToSpeech;
import com.github.wednesday.tts.TextToSpeechs;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Creates voice sessions wired to the configured STT, language model and TTS providers. */
@ApplicationScoped
public class VoiceSessions {

    private static final Logger LOG = Logger.getLogger(VoiceSessions.class);
    private static final List<String> SETTINGS = List.of(
            "GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_THINKING_LEVEL",
            "GEMINI_STT_MODEL", "GEMINI_STT_LANGUAGES",
            "GEMINI_TTS_MODEL", "GEMINI_TTS_VOICE", "GEMINI_TTS_STYLE");

    private final String llmProvider;
    private final String sttProvider;
    private final String ttsProvider;
    private final int contextTurns;
    private final String systemPrompt;
    private Providers providers;

    private record Providers(SpeechToText stt, LanguageModel llm, TextToSpeech tts) {
    }

    public VoiceSessions(@ConfigProperty(name = "wednesday.llm.provider", defaultValue = "gemini") String llmProvider,
                         @ConfigProperty(name = "wednesday.stt.provider", defaultValue = "gemini") String sttProvider,
                         @ConfigProperty(name = "wednesday.tts.provider", defaultValue = "gemini") String ttsProvider,
                         @ConfigProperty(name = "wednesday.context.turns", defaultValue = "20") int contextTurns) {
        this.llmProvider = llmProvider;
        this.sttProvider = sttProvider;
        this.ttsProvider = ttsProvider;
        this.contextTurns = contextTurns;
        this.systemPrompt = SystemPrompt.defaultPrompt();
    }

    /**
     * Opens a session that reports to {@code out}. {@code register} receives the session before
     * the client is told it is ready, so the client's first message always finds it. If a provider
     * cannot be created (for example a missing API key) the client gets an
     * {@link SessionEvent.Error} and no session.
     */
    public VoiceSession open(Consumer<SessionEvent> out, Consumer<VoiceSession> register) {
        SessionId id = SessionId.random();
        try (var scope = SessionLogContext.open(id)) {
            Providers p;
            try {
                p = providers();
            } catch (RuntimeException e) {
                LOG.errorf("cannot create providers: %s", e.getMessage());
                out.accept(new SessionEvent.Error("No puedo conectar con los proveedores: " + e.getMessage()));
                return null;
            }
            var context = new ConversationContext(systemPrompt, contextTurns);
            var session = new VoiceSession(id, p.stt(), new AiEngine(p.llm(), context), p.tts(), context,
                    Timeline.system(), SpokenReply.Settings.DEFAULT, out);
            register.accept(session);
            out.accept(new SessionEvent.Ready(id.value(), llmProvider, contextTurns, VoiceSession.TARGET_MILLIS,
                    p.tts().format().sampleRate()));
            out.accept(new SessionEvent.StateChanged(SessionState.IDLE));
            LOG.infof("voice session opened (stt=%s, llm=%s, tts=%s)", sttProvider, llmProvider, ttsProvider);
            return session;
        }
    }

    /** Provider settings from Quarkus config: environment variables, a local .env file, or properties. */
    private static Map<String, String> providerSettings() {
        Config config = ConfigProvider.getConfig();
        Map<String, String> settings = new HashMap<>();
        for (String name : SETTINGS) {
            config.getOptionalValue(name, String.class).ifPresent(v -> settings.put(name, v));
        }
        return settings;
    }

    private synchronized Providers providers() {
        if (providers == null) {
            Map<String, String> settings = providerSettings();
            providers = new Providers(SpeechToTexts.create(sttProvider, settings),
                    LanguageModels.create(llmProvider, settings), TextToSpeechs.create(ttsProvider, settings));
        }
        return providers;
    }
}
