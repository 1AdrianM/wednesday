package com.github.wednesday.common;

import java.util.Objects;

/** One message of the conversation sent to the model. */
public record ChatMessage(Role role, String text) {

    public enum Role { USER, ASSISTANT }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text);
    }
}
