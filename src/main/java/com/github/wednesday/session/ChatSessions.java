package com.github.wednesday.session;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.LanguageModel;
import com.github.wednesday.ai.LanguageModels;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.SessionLogContext;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.context.SystemPrompt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Creates chat sessions wired to the configured language model. */
@ApplicationScoped
public class ChatSessions {

    private static final Logger LOG = Logger.getLogger(ChatSessions.class);

    private final String provider;
    private final int contextTurns;
    private final String systemPrompt;
    private volatile LanguageModel model;

    public ChatSessions(@ConfigProperty(name = "wednesday.llm.provider", defaultValue = "gemini") String provider,
                        @ConfigProperty(name = "wednesday.context.turns", defaultValue = "20") int contextTurns) {
        this.provider = provider;
        this.contextTurns = contextTurns;
        this.systemPrompt = SystemPrompt.defaultPrompt();
    }

    /**
     * Opens a session that reports to {@code out}. If the model cannot be created (for example a
     * missing API key) the client gets an {@link SessionEvent.Error} and no session.
     */
    public ChatSession open(Consumer<SessionEvent> out) {
        SessionId id = SessionId.random();
        try (var scope = SessionLogContext.open(id)) {
            LanguageModel llm;
            try {
                llm = model();
            } catch (RuntimeException e) {
                LOG.errorf("cannot create language model '%s': %s", provider, e.getMessage());
                out.accept(new SessionEvent.Error("No puedo conectar con el modelo: " + e.getMessage()));
                return null;
            }
            var context = new ConversationContext(systemPrompt, contextTurns);
            var session = new ChatSession(id, new AiEngine(llm, context), context, Timeline.system(), out);
            LOG.infof("session opened (model=%s)", provider);
            out.accept(new SessionEvent.Ready(id.value(), provider, contextTurns, ChatSession.TARGET_MILLIS));
            out.accept(new SessionEvent.StateChanged(SessionState.IDLE));
            return session;
        }
    }

    /** Provider settings from Quarkus config: environment variables, a local .env file, or properties. */
    private static Map<String, String> providerSettings() {
        Config config = ConfigProvider.getConfig();
        Map<String, String> settings = new HashMap<>();
        for (String name : List.of("GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_THINKING_LEVEL")) {
            config.getOptionalValue(name, String.class).ifPresent(v -> settings.put(name, v));
        }
        return settings;
    }

    private LanguageModel model() {
        LanguageModel m = model;
        if (m == null) {
            synchronized (this) {
                if (model == null) {
                    model = LanguageModels.create(provider, providerSettings());
                }
                m = model;
            }
        }
        return m;
    }
}
