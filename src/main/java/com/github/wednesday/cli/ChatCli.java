package com.github.wednesday.cli;

import com.github.wednesday.ai.AiEngine;
import com.github.wednesday.ai.LanguageModel;
import com.github.wednesday.ai.LanguageModels;
import com.github.wednesday.common.SessionId;
import com.github.wednesday.common.SessionLogContext;
import com.github.wednesday.common.Stage;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.Timeline;
import com.github.wednesday.context.ConversationContext;
import com.github.wednesday.context.SystemPrompt;

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
 *   ./chat --fake                 offline, no API key
 *   GEMINI_API_KEY=... ./chat     real Gemini model
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

    ChatCli(AiEngine engine, ConversationContext context, Timeline timeline, BufferedReader in, PrintStream out) {
        this.engine = engine;
        this.context = context;
        this.timeline = timeline;
        this.in = in;
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        java.util.logging.Logger.getLogger("").setLevel(options.debug ? Level.FINE : Level.OFF);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        LanguageModel model;
        try {
            model = LanguageModels.create(options.provider, settings(Path.of(".env")));
        } catch (IllegalArgumentException e) {
            out.println("No puedo arrancar: " + e.getMessage());
            out.println("Usa --fake para probar sin API key, o exporta GEMINI_API_KEY.");
            System.exit(2);
            return;
        }
        String prompt = options.promptFile == null ? SystemPrompt.defaultPrompt() : SystemPrompt.fromFile(options.promptFile);
        ConversationContext context = new ConversationContext(prompt, options.maxTurns);
        var cli = new ChatCli(new AiEngine(model, context), context, Timeline.system(),
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);
        out.printf("Wednesday (modelo: %s, memoria: %d turnos). Escribe /salir para terminar.%n",
                options.provider, options.maxTurns);
        try (var scope = SessionLogContext.open(SessionId.random())) {
            cli.run();
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
        engine.respond(userText, new StreamListener<>() {
            @Override
            public void onEvent(String token) {
                timeline.mark(Stage.FIRST_TOKEN);
                out.print(token);
                out.flush();
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
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        out.println();
        long first = timeline.between(turn, Stage.REQUEST_SENT, Stage.FIRST_TOKEN).orElse(-1L);
        long total = timeline.between(turn, Stage.REQUEST_SENT, Stage.LAST_TOKEN).orElse(-1L);
        out.printf("   (primer token %d ms, total %d ms)%n", first, total);
    }

    static final class Options {
        String provider = "gemini";
        int maxTurns = 20;
        Path promptFile;
        boolean debug;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--fake" -> o.provider = "fake";
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
