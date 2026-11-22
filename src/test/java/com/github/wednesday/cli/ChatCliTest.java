package com.github.wednesday.cli;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.fake.FakeLanguageModel;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.tts.TextToSpeech;
import com.github.wednesday.tts.fake.FakeTextToSpeech;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ChatCliTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /** Records what would have been played. */
    static final class RecordingSink implements AudioSink {
        final List<AudioChunk> played = new CopyOnWriteArrayList<>();
        int drains;

        @Override
        public void play(AudioChunk chunk) {
            played.add(chunk);
        }

        @Override
        public void drain() {
            drains++;
        }

        @Override
        public void close() {
        }
    }

    private String run(FakeLanguageModel model, ConversationContext context, String input) throws Exception {
        return run(model, context, input, null, null);
    }

    private String run(FakeLanguageModel model, ConversationContext context, String input,
                       TextToSpeech tts, AudioSink sink) throws Exception {
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        new ChatCli(new AiEngine(model, context), context, Timeline.system(),
                new BufferedReader(new StringReader(input)), out, tts, sink).run();
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void speaksRepliesThroughTheAudioSinkAndWaitsForPlayback() throws Exception {
        var model = FakeLanguageModel.builder().reply("Hola, Adri. Son las diez.").build();
        var tts = FakeTextToSpeech.builder().chunksPerText(3).build();
        var sink = new RecordingSink();

        String output = run(model, new ConversationContext("p", 5), "hola\nqué hora es\n", tts, sink);

        assertTrue(output.contains("wednesday> Hola, Adri. Son las diez."), output);
        assertEquals(3 * tts.texts().size(), sink.played.size());
        assertEquals(2, sink.drains, "waits for the audio of each turn before the next prompt");
    }

    /** P2-R5: time from request to first audio is recorded and shown per turn. */
    @Test
    void reportAndTurnLineShowFirstAudioTime() throws Exception {
        var model = FakeLanguageModel.builder().reply("Hola. Adiós.").build();
        var tts = FakeTextToSpeech.builder().firstChunkDelay(java.time.Duration.ofMillis(50)).build();

        String output = run(model, new ConversationContext("p", 5), "uno\ndos\n/reporte\n", tts, new RecordingSink());

        assertTrue(output.contains("primer audio"), output);
        String header = output.lines().filter(l -> l.startsWith("turn ")).findFirst().orElseThrow();
        assertTrue(header.contains("FIRST_AUDIO"), header);
        assertEquals(2, output.lines().filter(l -> l.matches("^[12] .*")).count(), "one report row per turn");
    }

    @Test
    void voiceFailureIsShownAndTextStillArrives() throws Exception {
        var model = FakeLanguageModel.builder().reply("Hola.").build();
        var tts = FakeTextToSpeech.builder().failWith(new IllegalStateException("cuota agotada")).build();

        String output = run(model, new ConversationContext("p", 5), "hola\n", tts, new RecordingSink());

        assertTrue(output.contains("wednesday> Hola."), output);
        assertTrue(output.contains("(voz no disponible: cuota agotada)"), output);
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
    void readsDotEnvButRealEnvironmentWins(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var env = dir.resolve(".env");
        java.nio.file.Files.writeString(env, "# comentario\nGEMINI_MODEL=\"gemini-test\"\nWEDNESDAY_X='y'\nPATH=nope\nmalformed\n");

        var settings = ChatCli.settings(env);

        assertEquals("gemini-test", settings.get("GEMINI_MODEL"));
        assertEquals("y", settings.get("WEDNESDAY_X"));
        assertEquals(System.getenv("PATH"), settings.get("PATH"));
        assertFalse(settings.containsKey("malformed"));
        assertTrue(ChatCli.settings(dir.resolve("missing")).size() >= System.getenv().size());
    }

    @Test
    void parsesOptions() {
        var o = ChatCli.Options.parse(new String[] {"--fake", "--turns", "7", "--debug"});
        assertEquals("fake", o.provider);
        assertEquals("fake", o.voice, "--fake also fakes the voice");
        assertEquals("gemini", ChatCli.Options.parse(new String[] {}).voice);
        assertNull(ChatCli.Options.parse(new String[] {"--sin-voz"}).voice);
        assertEquals(7, o.maxTurns);
        assertTrue(o.debug);
        assertThrows(IllegalArgumentException.class, () -> ChatCli.Options.parse(new String[] {"--nope"}));
    }
}
