package com.github.wednesday.cli;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ChatCliTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    private String run(FakeLanguageModel model, ConversationContext context, String input) throws Exception {
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        new ChatCli(new AiEngine(model, context), context, Timeline.system(),
                new BufferedReader(new StringReader(input)), out).run();
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void chatsAndShowsLatencyPerTurn() throws Exception {
        var model = FakeLanguageModel.builder().reply("Hola, ¿qué tal?").build();

        String output = run(model, new ConversationContext("p", 5), "hola\n/salir\n");

        assertTrue(output.contains("wednesday> Hola, ¿qué tal?"), output);
        assertTrue(output.contains("(primer token"), output);
    }

    @Test
    void tenTurnConversationKeepsContext() throws Exception {
        var model = FakeLanguageModel.builder()
                .reply(r -> "Mensaje " + (r.messages().size() / 2 + 1) + ".").build();
        var context = new ConversationContext("p", 20);
        String input = IntStream.rangeClosed(1, 10).mapToObj(i -> "frase " + i).collect(Collectors.joining("\n"));

        String output = run(model, context, input + "\n");

        assertTrue(output.contains("Mensaje 10."), output);
        assertEquals(10, context.turns().size());
        assertEquals(19, model.requests().getLast().messages().size(), "9 earlier turns x 2 + new message");
        assertEquals("frase 1", model.requests().getLast().messages().getFirst().text());
    }

    @Test
    void commandsReportResetAndExit() throws Exception {
        var model = FakeLanguageModel.builder().reply("Vale.").build();
        var context = new ConversationContext("p", 5);

        String output = run(model, context, "uno\n/reporte\n/nuevo\n/salir\nno se procesa\n");

        assertTrue(output.contains("Latency report"), output);
        assertTrue(output.contains("FIRST_TOKEN"), output);
        assertTrue(output.contains("(conversación olvidada)"), output);
        assertTrue(context.turns().isEmpty());
        assertEquals(1, model.requests().size(), "nothing after /salir");
    }

    @Test
    void parsesOptions() {
        var o = ChatCli.Options.parse(new String[] {"--fake", "--turns", "7", "--debug"});
        assertEquals("fake", o.provider);
        assertEquals(7, o.maxTurns);
        assertTrue(o.debug);
        assertThrows(IllegalArgumentException.class, () -> ChatCli.Options.parse(new String[] {"--nope"}));
    }
}
