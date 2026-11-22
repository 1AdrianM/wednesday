package com.github.wednesday.context;

import com.github.wednesday.common.ChatMessage;
import com.github.wednesday.common.ModelRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationContextTest {

    @Test
    void buildsRequestWithPromptHistoryAndNewMessage() {
        var context = new ConversationContext("Eres Wednesday.", 5);
        context.addTurn("hola", "¡Hola!");

        ModelRequest request = context.buildRequest("¿qué hora es?");

        assertEquals("Eres Wednesday.", request.systemPrompt());
        assertEquals(List.of(ChatMessage.user("hola"), ChatMessage.assistant("¡Hola!"),
                ChatMessage.user("¿qué hora es?")), request.messages());
    }

    @Test
    void keepsOnlyTheLastNTurns() {
        var context = new ConversationContext("p", 3);
        for (int i = 1; i <= 5; i++) {
            context.addTurn("u" + i, "a" + i);
        }

        assertEquals(List.of("u3", "u4", "u5"), context.turns().stream().map(ConversationContext.Turn::user).toList());
        List<ChatMessage> messages = context.buildRequest("u6").messages();
        assertEquals(7, messages.size(), "3 kept turns x 2 + new message");
        assertEquals(ChatMessage.user("u3"), messages.getFirst());
    }

    @Test
    void buildingARequestDoesNotRecordTheNewMessage() {
        var context = new ConversationContext("p", 3);
        context.buildRequest("solo pregunto");
        assertTrue(context.turns().isEmpty());
    }

    @Test
    void rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationContext("p", 0));
    }

    @Test
    void loadsPromptFromClasspathAndFile(@TempDir Path dir) throws Exception {
        assertFalse(SystemPrompt.defaultPrompt().isBlank());

        Path file = dir.resolve("prompt.txt");
        Files.writeString(file, "  Eres una prueba.\n");
        assertEquals("Eres una prueba.", SystemPrompt.fromFile(file));
        assertThrows(IllegalArgumentException.class, () -> SystemPrompt.fromClasspath("/prompts/missing.txt"));
    }
}
