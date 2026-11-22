package com.github.wednesday.context;

import com.github.wednesday.common.ChatMessage;
import com.github.wednesday.common.ModelRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The recent conversation: keeps the last {@code maxTurns} completed exchanges (user message plus
 * assistant reply) and builds the model input from them. Thread-safe.
 */
public final class ConversationContext {

    /** One completed exchange. */
    public record Turn(String user, String assistant) {
        public Turn {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(assistant, "assistant");
        }
    }

    private final String systemPrompt;
    private final int maxTurns;
    private final Deque<Turn> turns = new ArrayDeque<>();

    public ConversationContext(String systemPrompt, int maxTurns) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be >= 1");
        }
        this.maxTurns = maxTurns;
    }

    /** Records a completed exchange, dropping the oldest when over the limit. */
    public synchronized void addTurn(String userText, String assistantText) {
        turns.addLast(new Turn(userText, assistantText));
        while (turns.size() > maxTurns) {
            turns.removeFirst();
        }
    }

    /** Model input: system prompt, the kept history, then the new user message. */
    public synchronized ModelRequest buildRequest(String newUserText) {
        List<ChatMessage> messages = new ArrayList<>(turns.size() * 2 + 1);
        for (Turn t : turns) {
            messages.add(ChatMessage.user(t.user()));
            messages.add(ChatMessage.assistant(t.assistant()));
        }
        messages.add(ChatMessage.user(Objects.requireNonNull(newUserText, "newUserText")));
        return new ModelRequest(systemPrompt, messages);
    }

    public synchronized List<Turn> turns() {
        return List.copyOf(turns);
    }

    public synchronized void clear() {
        turns.clear();
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public int maxTurns() {
        return maxTurns;
    }
}
