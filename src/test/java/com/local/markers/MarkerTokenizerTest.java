package com.local.markers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkerTokenizerTest {

    /** Resumo compacto do fluxo de tokens: "TIPO(raw)|TIPO(raw)|..." */
    private static String shape(String input) {
        return MarkerTokenizer.tokenize(input).stream()
                .map(t -> t.type() + "(" + t.raw().replace("\n", "\\n") + ")")
                .collect(Collectors.joining("|"));
    }

    private static List<Token> of(String input) {
        return MarkerTokenizer.tokenize(input);
    }

    @Test
    void nullAndEmptyGiveNoTokens() {
        assertTrue(MarkerTokenizer.tokenize(null).isEmpty());
        assertTrue(MarkerTokenizer.tokenize("").isEmpty());
    }

    @Test
    void plainTextIsAllVisible() {
        assertEquals("VISIBLE(a)|VISIBLE(b)", shape("ab"));
    }

    @Test
    void dialogPrefixIsInvisibleFormat() {
        assertEquals("FORMAT(* )|VISIBLE(h)|VISIBLE(i)", shape("* hi"));
    }

    @Test
    void dialogPrefixWithoutSpaceBeforeMarkerIsFormat() {
        // "*\E2 texto": o asterisco é o indicador de diálogo, non o placeholder de cor
        assertEquals("FORMAT(*)|FORMAT(\\E2)|VISIBLE( )|VISIBLE(x)", shape("*\\E2 x"));
    }

    @Test
    void expressionThenDialogPrefix() {
        assertEquals("FORMAT(\\E0)|FORMAT(* )|VISIBLE(x)", shape("\\E0* x"));
    }

    @Test
    void starGluedToTextAfterMarkerIsVisible() {
        assertEquals("FORMAT(\\E8)|VISIBLE(*)|VISIBLE(x)", shape("\\E8*x"));
    }

    @Test
    void colorMarkerTakesExactlyOneCharAfterBackslashC() {
        assertEquals("FORMAT(\\cY)|VISIBLE(A)|FORMAT(\\cW)", shape("\\cYA\\cW"));
        assertEquals("FORMAT(\\C2)|VISIBLE(A)", shape("\\C2A"));
    }

    @Test
    void newlineMarkers() {
        assertEquals("VISIBLE(a)|NEWLINE(&)|VISIBLE(b)", shape("a&b"));
        assertEquals("VISIBLE(a)|NEWLINE(#)|VISIBLE(b)", shape("a#b"));
        assertEquals("VISIBLE(a)|NEWLINE(\\n)|VISIBLE(b)", shape("a\nb"));
    }

    @Test
    void ampersandIsVisibleWhenLineAlreadyHasRealNewline() {
        // "&" só é salto de liña se a liña non usa xa \n
        assertEquals("VISIBLE(a)|VISIBLE(&)|VISIBLE(b)|NEWLINE(\\n)|VISIBLE(c)", shape("a&b\nc"));
    }

    @Test
    void pauseAndTildeArePending() {
        assertEquals("VISIBLE(a)|PENDING(^1)|VISIBLE(,)", shape("a^1,"));
        assertEquals("VISIBLE(a)|PENDING(~1)", shape("a~1"));
    }

    @Test
    void endMarkers() {
        assertEquals("VISIBLE(a)|END(/)", shape("a/"));
        assertEquals("VISIBLE(a)|END(/%)", shape("a/%"));
        assertEquals("VISIBLE(a)|END(%)", shape("a%"));
        assertEquals("VISIBLE(a)|END(%%)", shape("a%%"));
    }

    @Test
    void tokensAreClassifiedForRelocatableMarkers() {
        List<Token> tokens = of("\\cYa\\cW b~1 c\\O0 d\\I1");
        assertEquals(1, tokens.stream().filter(Markers::isTilde).count());
        assertEquals(2, tokens.stream().filter(Markers::isColor).count());
        assertEquals(1, tokens.stream().filter(Markers::isBackslashO).count());
        assertEquals(1, tokens.stream().filter(Markers::isBackslashI).count());
        assertEquals('*', Markers.placeholderFor(tokens.stream().filter(Markers::isColor).findFirst().get()));
    }

    @Test
    void perVisualLineDialogPrefixIsFormatOnEachLine() {
        assertEquals("FORMAT(* )|VISIBLE(a)|NEWLINE(&)|FORMAT(* )|VISIBLE(b)", shape("* a&* b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "* Hola/",
            "\\E0* Sure..^1. okay^1, we can try again./%",
            "SUSIE GOT THE \\cYPOWER CROISSANT\\cW",
            "a&b#c\nd",
            "* (Clues acquired.)&* (Now you can report.)~1",
            "Heaven knows^6&from our hearts^2 %",
            "* (Take it?)/ ",
            "text with trailing space  ",
            "\\E0\\M1/%",
            ":(/%"
    })
    void tokenizationIsLossless(String input) {
        assertEquals(input, MarkerTokenizer.rawJoin(MarkerTokenizer.tokenize(input)),
                () -> "rawJoin debe reproducir a entrada: " + shape(input));
    }
}
