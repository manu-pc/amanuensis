package com.local.markers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MarkerRendererTest {

    private static String render(String original) {
        return MarkerRenderer.literalForDisplay(MarkerTokenizer.tokenize(original));
    }

    @Test
    void ampersandGetsGlyphAndLineBreakExactlyOnce() {
        // o \n que se engade para visualizar non se reescribe a si mesmo
        assertEquals("a⏎&\nb", render("a&b"));
    }

    @Test
    void hashGetsGlyph() {
        assertEquals("a⏎#\nb", render("a#b"));
    }

    @Test
    void realNewlineIsShownAsEscape() {
        assertEquals("a⏎\\n\nb", render("a\nb"));
    }

    @Test
    void ampersandThatIsNotANewlineStaysPlain() {
        // liña que xa usa \n: o & é texto normal, non leva glifo
        assertEquals("Fish & chips⏎\\n\nmais", render("Fish & chips\nmais"));
    }

    @Test
    void markersAreShownVerbatim() {
        assertEquals("\\E0* Ola^1, mundo/%", render("\\E0* Ola^1, mundo/%"));
    }

    @Test
    void spaceBeforeEndMarkerIsPreserved() {
        assertEquals("from our hearts^2 %", render("from our hearts^2 %"));
    }
}
