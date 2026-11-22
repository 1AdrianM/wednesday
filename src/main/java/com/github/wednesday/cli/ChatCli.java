package com.github.wednesday.cli;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.LanguageModel;
import com.github.wednesday.ai.LanguageModels;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.SessionLogContext;
import com.github.wednesday.common.Stage;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.context.SystemPrompt;
import com.github.wednesday.session.SpokenReply;
import com.github.wednesday.tts.TextToSpeech;
import com.github.wednesday.tts.TextToSpeechs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;

/**
 * Text chat with Wednesday in the terminal. Commands: {@code /reporte} (latency report),
 * {@code /nuevo} (forget the conversation), {@code /salir} (exit).
 *
 * <pre>
 *   ./chat --fake                 offline, no API key (voice is silent)
 *   ./chat                        real Gemini model and voice (key in .env or GEMINI_API_KEY)
 *   ./chat --sin-voz              text only
 * </pre>
 */
public final class ChatCli {

    static final String PROMPT = "tú> ";
    static final String ASSISTANT = "wednesday> ";

    private final AiEngine engine;
    private final ConversationContext context;
    private final Timeline timeline;
    private final BufferedReader in;
    private final PrintStream out;
    private final TextToSpeech tts;
    private final AudioSink sink;

    /** {@code tts} and {@code sink} are both null for text-only mode. */
    ChatCli(AiEngine engine, ConversationContext context, Timeline timeline, BufferedReader in, PrintStream out,
            TextToSpeech tts, AudioSink sink) {
        this.engine = engine;
        this.context = context;
        this.timeline = timeline;
        this.in = in;
        this.out = out;
        this.tts = tts;
        this.sink = sink;
    }

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        java.util.logging.Logger.getLogger("").setLevel(options.debug ? Level.FINE : Level.OFF);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        LanguageModel model;
        TextToSpeech tts = null;
        AudioSink sink = null;
        try {
            var settings = settings(Path.of(".env"));
            model = LanguageModels.create(options.provider, settings);
            if (options.voice != null) {
                tts = TextToSpeechs.create(options.voice, settings);
            }
        } catch (IllegalArgumentException e) {
            out.println("No puedo arrancar: " + e.getMessage());
            out.println("Usa --fake para probar sin API key, o pon GEMINI_API_KEY en .env.");
            System.exit(2);
            return;
        }
        if (tts != null) {
            sink = SpeakerSink.open(tts.format()).orElse(null);
            if (sink == null) {
                out.println("(no encuentro un dispositivo de audio: sigo sólo con texto)");
                tts = null;
            }
        }
        String prompt = options.promptFile == null ? SystemPrompt.defaultPrompt() : SystemPrompt.fromFile(options.promptFile);
        ConversationContext context = new ConversationContext(prompt, options.maxTurns);
        var cli = new ChatCli(new AiEngine(model, context), context, Timeline.system(),
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out, tts, sink);
        out.printf("Wednesday (modelo: %s, voz: %s, memoria: %d turnos). Escribe /salir para terminar.%n",
                options.provider, tts == null ? "no" : options.voice, options.maxTurns);
        try (var scope = SessionLogContext.open(SessionId.random())) {
            cli.run();
        } finally {
            if (sink != null) {
                sink.close();
            }
        }
        System.exit(0);
    }

    /** Process environment over the KEY=VALUE lines of a local .env file (if present). */
    static Map<String, String> settings(Path dotEnv) throws IOException {
        Map<String, String> settings = new HashMap<>();
        if (Files.isRegularFile(dotEnv)) {
            for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq < 1) {
                    continue;
                }
                String value = l.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                settings.put(l.substring(0, eq).strip(), value);
            }
        }
        settings.putAll(System.getenv());
        return settings;
    }

    /** Reads lines until EOF or /salir. */
    void run() throws IOException {
        while (true) {
            out.print(PROMPT);
            out.flush();
            String line = in.readLine();
            if (line == null) {
                out.println();
                return;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            switch (line) {
                case "/salir", "/exit" -> {
                    return;
                }
                case "/reporte" -> out.print(timeline.report().render());
                case "/nuevo" -> {
                    context.clear();
                    out.println("(conversación olvidada)");
                }
                default -> turn(line);
            }
        }
    }

    private void turn(String userText) {
        int turn = timeline.beginTurn();
        timeline.mark(Stage.REQUEST_SENT);
        CountDownLatch done = new CountDownLatch(1);
        out.print(ASSISTANT);
        if (tts == null) {
            engine.respond(userText, new StreamListener<>() {
                @Override
                public void onEvent(String token) {
                    timeline.mark(Stage.FIRST_TOKEN);
                    printToken(token);
                }

                @Override
                public void onError(Throwable error) {
                    out.print("[error: " + error.getMessage() + "]");
                    done.countDown();
                }

                @Override
                public void onComplete() {
                    timeline.mark(Stage.LAST_TOKEN);
                    done.countDown();
                }
            });
        } else {
            StringBuilder voiceNote = new StringBuilder();
            SpokenReply.start(engine, tts, userText, timeline, SpokenReply.Settings.DEFAULT, new StreamListener<>() {
                @Override
                public void onEvent(SpokenReply.Event event) {
                    switch (event) {
                        case SpokenReply.Event.Text(String token) -> printToken(token);
                        case SpokenReply.Event.Audio(AudioChunk chunk) -> sink.play(chunk);
                        case SpokenReply.Event.SpeechFailed(String reason) -> voiceNote.append(reason);
                        case SpokenReply.Event.Segment segment -> { }
                    }
                }

                @Override
                public void onError(Throwable error) {
                    out.print("[error: " + error.getMessage() + "]");
                    done.countDown();
                }

                @Override
                public void onComplete() {
                    if (!voiceNote.isEmpty()) {
                        out.print(System.lineSeparator() + "   (voz no disponible: " + voiceNote + ")");
                    }
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        out.println();
        printLatency(turn);
        if (sink != null) {
            sink.drain();
        }
    }

    private void printToken(String token) {
        out.print(token);
        out.flush();
    }

    private void printLatency(int turn) {
        long first = timeline.between(turn, Stage.REQUEST_SENT, Stage.FIRST_TOKEN).orElse(-1L);
        long total = timeline.between(turn, Stage.REQUEST_SENT, Stage.LAST_TOKEN).orElse(-1L);
        var audio = timeline.between(turn, Stage.REQUEST_SENT, Stage.FIRST_AUDIO);
        if (audio.isPresent()) {
            out.printf("   (primer token %d ms, primer audio %d ms, texto completo %d ms)%n", first, audio.get(), total);
        } else {
            out.printf("   (primer token %d ms, total %d ms)%n", first, total);
        }
    }

    static final class Options {
        String provider = "gemini";
        /** TTS provider, or null for text only. */
        String voice = "gemini";
        int maxTurns = 20;
        Path promptFile;
        boolean debug;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--fake" -> {
                        o.provider = "fake";
                        o.voice = o.voice == null ? null : "fake";
                    }
                    case "--sin-voz" -> o.voice = null;
                    case "--voz" -> o.voice = args[++i];
                    case "--model" -> o.provider = args[++i];
                    case "--turns" -> o.maxTurns = Integer.parseInt(args[++i]);
                    case "--prompt" -> o.promptFile = Path.of(args[++i]);
                    case "--debug" -> o.debug = true;
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return o;
        }
    }
}
