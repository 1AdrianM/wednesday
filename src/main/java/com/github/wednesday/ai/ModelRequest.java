package com.github.wednesday.ai;

import java.util.List;
import java.util.Objects;

/** Provider-neutral input for one model call. */
public record ModelRequest(String systemPrompt, List<ChatMessage> messages) {

    public ModelRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        messages = List.copyOf(messages);
    }
}
