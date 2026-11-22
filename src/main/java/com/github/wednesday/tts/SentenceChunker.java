package com.github.wednesday.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a stream of model tokens into speakable segments for TTS.
 *
 * <p>A segment ends at a sentence end ({@code . ! ? …}, with any closing quotes or brackets) once
 * the next sentence has visibly started: whitespace and then a character that is not lowercase.
 * Waiting for that next character is what keeps abbreviations ("Sr. García", "aprox. a las"),
 * initials ("J. R. R. Tolkien"), decimals ("3.5") and ellipses inside a sentence together. Text
 * that grows past {@code maxChars} without a sentence end is cut at the last comma, semicolon,
 * colon or space before the limit. Not thread-safe; one instance per reply.
 */
public final class SentenceChunker {

    /** Lowercase abbreviations (without the final dot) that never end a sentence. */
    private static final Set<String> ABBREVIATIONS = Set.of(
            "sr", "sra", "srta", "dr", "dra", "ud", "uds", "vd", "vds", "lic", "ing", "prof", "profa",
            "aprox", "etc", "núm", "num", "pág", "pag", "tel", "av", "avda", "c", "ej", "p", "pp",
            "ee", "uu", "dto", "dpto", "vol", "cap", "fig", "art", "máx", "mín", "min", "max",
            "mr", "mrs", "ms", "st", "vs", "e.g", "i.e", "approx", "jr", "inc", "ltd", "no");

    private static final String TERMINALS = ".!?…";
    private static final String CLOSERS = "\"'»)]”’";
    private static final String SOFT_BREAKS = ",;:";

    private final int maxChars;
    private final StringBuilder buffer = new StringBuilder();

    /** @param maxChars longest segment before a forced cut; at least 20 */
    public SentenceChunker(int maxChars) {
        if (maxChars < 20) {
            throw new IllegalArgumentException("maxChars must be >= 20");
        }
        this.maxChars = maxChars;
    }

    /** Adds a token and returns the segments it completed (often none). */
    public List<String> push(String token) {
        buffer.append(token);
        List<String> out = new ArrayList<>();
        int end;
        while ((end = sentenceEnd()) > 0) {
            emit(end, out);
        }
        while (buffer.length() > maxChars) {
            emit(softCut(), out);
        }
        return out;
    }

    /** Returns whatever is left as a final segment and clears the buffer. */
    public Optional<String> flush() {
        String rest = buffer.toString().strip();
        buffer.setLength(0);
        return rest.isEmpty() ? Optional.empty() : Optional.of(rest);
    }

    private void emit(int end, List<String> out) {
        String segment = buffer.substring(0, end).strip();
        buffer.delete(0, end);
        while (!buffer.isEmpty() && Character.isWhitespace(buffer.charAt(0))) {
            buffer.deleteCharAt(0);
        }
        if (!segment.isEmpty()) {
            out.add(segment);
        }
    }

    /** Index just after a confirmed sentence end, or -1 if none is confirmed yet. */
    private int sentenceEnd() {
        int len = buffer.length();
        for (int i = 0; i < len; i++) {
            if (TERMINALS.indexOf(buffer.charAt(i)) < 0) {
                continue;
            }
            int runEnd = i;
            while (runEnd < len && (TERMINALS.indexOf(buffer.charAt(runEnd)) >= 0
                    || CLOSERS.indexOf(buffer.charAt(runEnd)) >= 0)) {
                runEnd++;
            }
            if (runEnd >= len) {
                return -1;
            }
            if (!Character.isWhitespace(buffer.charAt(runEnd))) {
                i = runEnd - 1;
                continue;
            }
            int next = runEnd;
            while (next < len && Character.isWhitespace(buffer.charAt(next))) {
                next++;
            }
            if (next >= len) {
                return -1;
            }
            boolean continuesSentence = Character.isLowerCase(buffer.charAt(next));
            boolean singleDot = runEnd - i == 1 && buffer.charAt(i) == '.';
            if (continuesSentence || singleDot && isAbbreviationOrInitial(i)) {
                i = runEnd - 1;
                continue;
            }
            return runEnd;
        }
        return -1;
    }

    /** True if the word ending just before the dot at {@code dot} is an abbreviation or an initial. */
    private boolean isAbbreviationOrInitial(int dot) {
        int start = dot;
        while (start > 0 && !Character.isWhitespace(buffer.charAt(start - 1))) {
            start--;
        }
        String word = buffer.substring(start, dot).replaceAll("^[¿¡«\"'(\\[]+", "");
        if (word.length() == 1 && Character.isLetter(word.charAt(0))) {
            return true;
        }
        return ABBREVIATIONS.contains(word.toLowerCase(Locale.ROOT));
    }

    /** Where to cut an over-long buffer: after the last soft break, else the last space, else the limit. */
    private int softCut() {
        int limit = Math.min(maxChars, buffer.length());
        for (int i = limit - 1; i > 0; i--) {
            if (SOFT_BREAKS.indexOf(buffer.charAt(i)) >= 0
                    && (i + 1 >= buffer.length() || Character.isWhitespace(buffer.charAt(i + 1)))) {
                return i + 1;
            }
        }
        for (int i = limit; i > 0; i--) {
            if (i < buffer.length() && Character.isWhitespace(buffer.charAt(i))) {
                return i;
            }
        }
        return limit;
    }
}
