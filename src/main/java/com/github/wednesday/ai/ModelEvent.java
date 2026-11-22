package com.github.wednesday.ai;

/** Events streamed back by a {@link LanguageModel}. */
public sealed interface ModelEvent {

    /** A piece of response text, in order. */
    record Token(String text) implements ModelEvent {
    }

    /** The model asks to call a tool. {@code argumentsJson} is the raw JSON object of arguments. */
    record ToolCall(String id, String name, String argumentsJson) implements ModelEvent {
    }
}
