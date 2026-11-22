package com.github.wednesday.context;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the spoken-style rules (P1-R3) in the bundled prompt against accidental removal. */
class SystemPromptRulesTest {

    private final String prompt = SystemPrompt.defaultPrompt().toLowerCase(Locale.ROOT);

    @Test
    void asksForShortAnswers() {
        assertTrue(prompt.contains("respuestas cortas"));
    }

    @Test
    void forbidsMarkdownAndLists() {
        assertTrue(prompt.contains("nada de markdown"));
        assertTrue(prompt.contains("nada de listas"));
    }

    @Test
    void asksForNumbersAndSymbolsReadAloud() {
        assertTrue(prompt.contains("números"));
        assertTrue(prompt.contains("como se leen en voz alta"));
    }

    @Test
    void spanishByDefaultEnglishWhenUserSwitches() {
        assertTrue(prompt.contains("español por defecto"));
        assertTrue(prompt.contains("si el usuario te habla en inglés, responde en inglés"));
    }

    @Test
    void containsNoMarkdownItself() {
        assertFalse(prompt.contains("**"));
        assertFalse(prompt.contains("#"));
    }
}
