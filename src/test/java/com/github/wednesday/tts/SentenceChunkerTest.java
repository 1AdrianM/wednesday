package com.github.wednesday.tts;

import com.github.wednesday.ai.fake.FakeLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SentenceChunkerTest {

    /** Feeds text as model-like word tokens and returns every segment, including the final flush. */
    private static List<String> chunk(SentenceChunker chunker, String text) {
        List<String> out = new ArrayList<>();
        for (String token : FakeLanguageModel.tokenize(text)) {
            out.addAll(chunker.push(token));
        }
        chunker.flush().ifPresent(out::add);
        return out;
    }

    private static List<String> chunk(String text) {
        return chunk(new SentenceChunker(160), text);
    }

    @Test
    void splitsAtSentenceEnds() {
        assertEquals(List.of("Hola, Adri.", "Son las diez y cuarto.", "¿Algo más?", "¡Genial!"),
                chunk("Hola, Adri. Son las diez y cuarto. ¿Algo más? ¡Genial!"));
    }

    @Test
    void emitsASentenceAsSoonAsTheNextOneStarts() {
        var chunker = new SentenceChunker(160);
        assertEquals(List.of(), chunker.push("Hola, "));
        assertEquals(List.of(), chunker.push("Adri."));
        assertEquals(List.of("Hola, Adri."), chunker.push(" Son"));
    }

    @Test
    void tokensThatSplitWordsOrPunctuationStillWork() {
        var chunker = new SentenceChunker(160);
        List<String> out = new ArrayList<>();
        for (String t : List.of("Uno", ". D", "os", ".", " Tres")) {
            out.addAll(chunker.push(t));
        }
        chunker.flush().ifPresent(out::add);
        assertEquals(List.of("Uno.", "Dos.", "Tres"), out);
    }

    @Test
    void keepsAbbreviationsTogether() {
        assertEquals(List.of("El Sr. García y la Dra. López llegan aprox. a las diez.", "Vale."),
                chunk("El Sr. García y la Dra. López llegan aprox. a las diez. Vale."));
        assertEquals(List.of("I met Mr. Smith, e.g. at work.", "Fine."),
                chunk("I met Mr. Smith, e.g. at work. Fine."));
    }

    @Test
    void keepsInitialsTogether() {
        assertEquals(List.of("Lo escribió J. R. R. Tolkien.", "Es largo."),
                chunk("Lo escribió J. R. R. Tolkien. Es largo."));
    }

    @Test
    void keepsDecimalsAndThousandsTogether() {
        assertEquals(List.of("Mide 3.5 metros y pesa 1.200 kilos.", "Pi es 3,14."),
                chunk("Mide 3.5 metros y pesa 1.200 kilos. Pi es 3,14."));
    }

    @Test
    void ellipsisAndClosingQuotesStayWithTheirSentence() {
        assertEquals(List.of("Bueno... no sé.", "Dijo «adiós.»", "Y se fue."),
                chunk("Bueno... no sé. Dijo «adiós.» Y se fue."));
    }

    @Test
    void longTextWithoutSentenceEndFlushesAtASoftBreakBeforeTheMaximum() {
        var chunker = new SentenceChunker(40);
        List<String> out = chunk(chunker,
                "Esto es una frase larguísima, sin ningún punto, que sigue y sigue sin parar nunca");
        assertTrue(out.size() >= 2, out.toString());
        out.forEach(s -> assertTrue(s.length() <= 40, "too long: " + s));
        assertEquals("Esto es una frase larguísima,", out.getFirst());
        assertEquals("Esto es una frase larguísima, sin ningún punto, que sigue y sigue sin parar nunca",
                String.join(" ", out));
    }

    @Test
    void flushReturnsTheRestAndBlankIsNothing() {
        var chunker = new SentenceChunker(160);
        chunker.push("Sin punto final");
        assertEquals("Sin punto final", chunker.flush().orElseThrow());
        assertTrue(chunker.flush().isEmpty());
        chunker.push("   ");
        assertTrue(chunker.flush().isEmpty());
    }

    @Test
    void rejectsTinyMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new SentenceChunker(10));
    }
}
