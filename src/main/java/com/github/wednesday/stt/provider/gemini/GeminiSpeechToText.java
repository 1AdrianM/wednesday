package com.github.wednesday.stt.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wednesday.common.AudioChunk;
import com.github.wednesday.common.AudioFormats;
import com.github.wednesday.common.StreamListener;
import com.github.wednesday.common.VirtualThreadStream;
import com.github.wednesday.stt.SpeechToText;
import com.github.wednesday.stt.SttStream;
import com.github.wednesday.stt.TranscriptEvent;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@link SpeechToText} backed by Gemini Transcribe Live over the Live API WebSocket, in
 * push-to-talk mode: automatic activity detection is disabled, the stream opens the turn with
 * {@code activityStart} and closes it with {@code activityEnd} on {@link SttStream#endOfAudio()}.
 *
 * <p>Interim hypotheses become {@link TranscriptEvent.Partial}s; settled segments
 * ({@code inputTranscription}, VERBATIM mode) are appended, and on {@code turnComplete} the
 * joined text is emitted once as {@link TranscriptEvent.Final}. Audio is batched to 100 ms per
 * message, as the API recommends.
 *
 * <p>One virtual thread owns the socket and all protocol state; socket callbacks and the caller's
 * {@code send}/{@code endOfAudio} only put items in its queue. The API key travels in the
 * {@code key} query parameter (the only option the Live API documents) and is scrubbed from every
 * error message.
 */
public final class GeminiSpeechToText implements SpeechToText {

    private static final Logger LOG = Logger.getLogger(GeminiSpeechToText.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int BATCH_BYTES = AudioFormats.INPUT.bytesPerSecond() / 10;
    private static final String MIME_TYPE = "audio/pcm;rate=" + AudioFormats.INPUT.sampleRate();

    /**
     * @param languageCodes BCP-47 hints; empty means automatic detection (Spanish and English both work)
     * @param finalTimeout  how long to wait for {@code turnComplete} after the end of audio
     */
    public record Config(URI url, String apiKey, String model, List<String> languageCodes, Duration finalTimeout) {

        public static final URI DEFAULT_URL = URI.create("wss://generativelanguage.googleapis.com/ws/"
                + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent");
        public static final String DEFAULT_MODEL = "gemini-3.5-transcribe-live";
        public static final Duration DEFAULT_FINAL_TIMEOUT = Duration.ofSeconds(3);

        public Config {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(model, "model");
            languageCodes = List.copyOf(languageCodes);
            Objects.requireNonNull(finalTimeout, "finalTimeout");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Gemini API key missing: set GEMINI_API_KEY");
            }
        }

        /** Reads {@code GEMINI_API_KEY} (required), {@code GEMINI_STT_MODEL}, {@code GEMINI_STT_LANGUAGES} (comma separated). */
        public static Config fromEnvironment(Map<String, String> env) {
            String langs = env.getOrDefault("GEMINI_STT_LANGUAGES", "");
            List<String> codes = Arrays.stream(langs.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            return new Config(DEFAULT_URL, env.get("GEMINI_API_KEY"),
                    env.getOrDefault("GEMINI_STT_MODEL", DEFAULT_MODEL), codes, DEFAULT_FINAL_TIMEOUT);
        }

        @Override
        public String toString() {
            return "Config[url=" + url + ", model=" + model + ", languages=" + languageCodes + "]";
        }
    }

    /** Provider error with a message that is safe to log. */
    public static final class GeminiSttException extends RuntimeException {
        GeminiSttException(String message) {
            super(message);
        }
    }

    private final Config config;
    private final HttpClient http;

    public GeminiSpeechToText(Config config) {
        this.config = Objects.requireNonNull(config);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public SttStream open(StreamListener<TranscriptEvent> listener) {
        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        VirtualThreadStream<TranscriptEvent> stream =
                VirtualThreadStream.start("gemini-stt", listener, emit -> new Turn(inbox, emit).run());
        return new SttStream() {
            @Override
            public void send(AudioChunk audio) {
                inbox.add(Objects.requireNonNull(audio));
            }

            @Override
            public void endOfAudio() {
                inbox.add(END_OF_AUDIO);
            }

            @Override
            public void cancel() {
                stream.cancel();
            }

            @Override
            public boolean isActive() {
                return stream.isActive();
            }

            @Override
            public CompletionStage<Void> completion() {
                return stream.completion();
            }
        };
    }

    private static final Object END_OF_AUDIO = new Object();

    /** A message from the server, already reassembled from fragments. */
    private record ServerMessage(String json) {
    }

    private record Closed(int code, String reason) {
    }

    private record Broken(Throwable error) {
    }

    /** State of one push-to-talk turn; only the stream's thread touches it. */
    private final class Turn {
        private final BlockingQueue<Object> inbox;
        private final VirtualThreadStream.Emitter<TranscriptEvent> emit;
        private final Deque<Object> beforeSetup = new ArrayDeque<>();
        private final ByteArrayOutputStream batch = new ByteArrayOutputStream();
        private final StringBuilder settled = new StringBuilder();
        private boolean ready;
        private long finalDeadline = Long.MAX_VALUE;

        Turn(BlockingQueue<Object> inbox, VirtualThreadStream.Emitter<TranscriptEvent> emit) {
            this.inbox = inbox;
            this.emit = emit;
        }

        void run() throws Exception {
            WebSocket ws = connect();
            try {
                send(ws, setupMessage());
                while (true) {
                    long waitNanos = finalDeadline == Long.MAX_VALUE
                            ? TimeUnit.SECONDS.toNanos(1) : finalDeadline - System.nanoTime();
                    if (waitNanos <= 0) {
                        LOG.debugf("no turnComplete within %s; using what was heard", config.finalTimeout());
                        emit.emit(new TranscriptEvent.Final(text(settled, "")));
                        return;
                    }
                    Object item = inbox.poll(waitNanos, TimeUnit.NANOSECONDS);
                    if (item == null) {
                        continue;
                    }
                    if (item instanceof ServerMessage(String json)) {
                        if (onServerMessage(ws, json)) {
                            return;
                        }
                    } else if (item instanceof Closed(int code, String reason)) {
                        if (finalDeadline != Long.MAX_VALUE && !settled.isEmpty()) {
                            emit.emit(new TranscriptEvent.Final(text(settled, "")));
                            return;
                        }
                        throw new GeminiSttException(scrub("Gemini STT connection closed " + code + ": " + reason));
                    } else if (item instanceof Broken(Throwable error)) {
                        throw new GeminiSttException(scrub("Gemini STT connection failed: " + error));
                    } else if (!ready) {
                        beforeSetup.add(item);
                    } else {
                        onClientItem(ws, item);
                    }
                }
            } finally {
                close(ws);
            }
        }

        /** Returns true when the turn is finished. */
        private boolean onServerMessage(WebSocket ws, String raw) {
            JsonNode msg;
            try {
                msg = JSON.readTree(raw);
            } catch (Exception e) {
                LOG.debugf("ignoring unreadable server message (%d chars)", raw.length());
                return false;
            }
            if (msg.has("setupComplete")) {
                ready = true;
                send(ws, "{\"realtimeInput\":{\"activityStart\":{}}}");
                while (!beforeSetup.isEmpty()) {
                    onClientItem(ws, beforeSetup.poll());
                }
                return false;
            }
            JsonNode content = msg.path("serverContent");
            JsonNode interim = content.path("interimInputTranscription").path("text");
            if (interim.isTextual()) {
                emit.emit(new TranscriptEvent.Partial(text(settled, interim.asText())));
            }
            JsonNode segment = content.path("inputTranscription").path("text");
            if (segment.isTextual() && !segment.asText().isEmpty()) {
                append(settled, segment.asText());
                emit.emit(new TranscriptEvent.Partial(text(settled, "")));
            }
            if (content.path("turnComplete").asBoolean(false) && finalDeadline != Long.MAX_VALUE) {
                emit.emit(new TranscriptEvent.Final(text(settled, "")));
                return true;
            }
            if (msg.has("goAway")) {
                LOG.warnf("Gemini STT asked to disconnect soon: %s", msg.path("goAway"));
            }
            return false;
        }

        private void onClientItem(WebSocket ws, Object item) {
            if (item == END_OF_AUDIO) {
                if (finalDeadline != Long.MAX_VALUE) {
                    return;
                }
                flush(ws);
                send(ws, "{\"realtimeInput\":{\"activityEnd\":{}}}");
                finalDeadline = System.nanoTime() + config.finalTimeout().toNanos();
            } else if (item instanceof AudioChunk chunk && finalDeadline == Long.MAX_VALUE) {
                batch.writeBytes(chunk.bytes());
                if (batch.size() >= BATCH_BYTES) {
                    flush(ws);
                }
            }
        }

        private void flush(WebSocket ws) {
            if (batch.size() == 0) {
                return;
            }
            ObjectNode audio = JSON.createObjectNode();
            audio.putObject("realtimeInput").putObject("audio")
                    .put("data", Base64.getEncoder().encodeToString(batch.toByteArray()))
                    .put("mimeType", MIME_TYPE);
            batch.reset();
            send(ws, audio.toString());
        }

        private WebSocket connect() throws Exception {
            URI uri = URI.create(config.url() + "?key=" + URLEncoder.encode(config.apiKey(), StandardCharsets.UTF_8));
            CompletableFuture<WebSocket> opening = http.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(uri, new Feed(inbox));
            try {
                return opening.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // Cancelled while connecting: close the socket whenever it does open.
                opening.thenAccept(GeminiSpeechToText::close);
                throw e;
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new GeminiSttException(scrub("Gemini STT cannot connect: " + cause));
            }
        }

        private String setupMessage() {
            ObjectNode root = JSON.createObjectNode();
            ObjectNode setup = root.putObject("setup");
            setup.put("model", "models/" + config.model());
            setup.putObject("generationConfig").putArray("responseModalities").add("TEXT");
            var codes = setup.putObject("inputAudioTranscription").putArray("languageCodes");
            config.languageCodes().forEach(codes::add);
            setup.putObject("realtimeInputConfig").putObject("automaticActivityDetection").put("disabled", true);
            return root.toString();
        }
    }

    private static void send(WebSocket ws, String text) {
        ws.sendText(text, true).join();
    }

    private static void close(WebSocket ws) {
        if (ws.isOutputClosed()) {
            ws.abort();
            return;
        }
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "")
                .orTimeout(1, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)
                        .execute(ws::abort));
    }

    /** Joins settled text and the current hypothesis, with single spaces. */
    static String text(CharSequence settled, String interim) {
        StringBuilder sb = new StringBuilder(settled);
        append(sb, interim);
        return sb.toString().strip().replaceAll("\\s+", " ");
    }

    private static void append(StringBuilder sb, String piece) {
        if (piece.isEmpty()) {
            return;
        }
        if (!sb.isEmpty() && !Character.isWhitespace(sb.charAt(sb.length() - 1))
                && !Character.isWhitespace(piece.charAt(0))) {
            sb.append(' ');
        }
        sb.append(piece);
    }

    private String scrub(String message) {
        String key = config.apiKey();
        return message.replace(key, "***").replace(URLEncoder.encode(key, StandardCharsets.UTF_8), "***");
    }

    /** Socket callbacks: reassemble fragments and hand whole messages to the turn's queue. */
    private static final class Feed implements WebSocket.Listener {
        private final BlockingQueue<Object> inbox;
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        Feed(BlockingQueue<Object> inbox) {
            this.inbox = inbox;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                inbox.add(new ServerMessage(text.toString()));
                text.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binary.writeBytes(bytes);
            if (last) {
                inbox.add(new ServerMessage(binary.toString(StandardCharsets.UTF_8)));
                binary.reset();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            inbox.add(new Closed(statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            inbox.add(new Broken(error));
        }
    }
}
