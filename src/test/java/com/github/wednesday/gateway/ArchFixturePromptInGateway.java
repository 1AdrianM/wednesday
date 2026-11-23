package com.github.wednesday.gateway;

import com.github.wednesday.context.SystemPrompt;

/** Architecture-test fixture: a gateway class that reaches for prompts. Never used at runtime. */
public final class ArchFixturePromptInGateway {
    String prompt() {
        return SystemPrompt.defaultPrompt();
    }
}
