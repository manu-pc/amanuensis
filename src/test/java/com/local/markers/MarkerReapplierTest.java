package com.local.markers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkerReapplierTest {

    private static String strip(String original) {
        return MarkerStripper.strip(MarkerTokenizer.tokenize(original));
    }

    private static String reapply(String original, String userText) {
        return MarkerReapplier.reapply(MarkerTokenizer.tokenize(original), userText);
    }

    private static String reapply(String original, String userText, CaretOnePolicy policy) {
        return MarkerReapplier.reapply(MarkerTokenizer.tokenize(original), userText, policy);
    }

    // ---------- texto limpo ----------

    @Test
    void stripHidesFixedMarkersAndShowsPlaceholders() {
        assertEquals("Sure... okay, we can try again.",
                strip("\\E0* Sure..^1. okay^1, we can try again./%"));
        assertEquals("SUSIE GOT THE *POWER CROISSANT*",
                strip("SUSIE GOT THE \\cYPOWER CROISSANT\\cW"));
        assertEquals("Ola\nmundo", strip("Ola&mundo"));
        assertEquals("Look @here@.", strip("* Look \\O0here\\O1./"));
    }

    @Test
    void parensAreVisibleInCleanText() {
        assertEquals("(Take it?)", strip("* (Take it?)/ "));
        // o espazo tras \EH é texto visible, así que forma parte do texto limpo
        assertEquals(" (AM I THAT BAD???)", strip("*\\EH (AM I THAT BAD???)"));
    }

    // ---------- reaplicar ----------

    @Test
    void translationKeepsLeadingMarkersAndEndMarker() {
        // o orixinal levaba ^1: rededucíense da puntuación da tradución
        assertEquals("\\E0* Claro..^1. vale^1, podemos tentalo de novo./%",
                reapply("\\E0* Sure..^1. okay^1, we can try again./%",
                        "Claro... vale, podemos tentalo de novo."));
    }

    @Test
    void colorMarkersFollowThePlaceholders() {
        assertEquals("SUSIE RECIBIU O \\cYCRUASÁN DO PODER\\cW",
                reapply("SUSIE GOT THE \\cYPOWER CROISSANT\\cW",
                        "SUSIE RECIBIU O *CRUASÁN DO PODER*"));
    }

    @Test
    void dialogPrefixIsRepeatedOnEachVisualLine() {
        // antes agrupábanse todos os "* " ao principio: "* * * You won!&..."
        assertEquals("* Gañaches^1!&* Colliches ~1 EXP e ~2 D$^1.&* Susie subiu!/%",
                reapply("* You won^1!&* Got ~1 EXP and ~2 D$.&* Susie's heal power increased!/%",
                        "Gañaches!\nColliches ~ EXP e ~ D$.\nSusie subiu!"));
    }

    @Test
    void quotesAreNotEscaped() {
        // o escape de JSON faino Gson ao gardar; facelo aquí producía \\" no ficheiro
        assertEquals("\\E8* Sobre as \"regras\" deste mundo./",
                reapply("\\E8* About the \"rules\" of this world./",
                        "Sobre as \"regras\" deste mundo."));
    }

    @Test
    void spaceBeforeEndMarkerSurvives() {
        assertEquals("dos nosos corazóns^2 %",
                reapply("from our hearts^2 %", "dos nosos corazóns"));
    }

    @Test
    void extraUserLinesReuseTheLastNewlineMarker() {
        assertEquals("* un#* dous#* tres/",
                reapply("* one#* two/", "un\ndous\ntres"));
    }

    @Test
    void fewerUserLinesDropTheUnusedLineButKeepEndMarker() {
        assertEquals("* todo nunha liña/", reapply("* one#* two/", "todo nunha liña"));
    }

    @Test
    void pauseGoesToTheWordBoundaryNearItsOriginalPosition() {
        // ^6 ía ao final da liña: non debe saltar ao principio
        assertEquals("Ceo sabe^6&dos nosos corazóns^2 %",
                reapply("Heaven knows^6&from our hearts^2 %", "Ceo sabe\ndos nosos corazóns"));
    }

    @Test
    void pauseInScriptsWithoutSpacesStaysWhereItWas() {
        // o xaponés non ten espazos: a pausa queda na posición exacta
        String original = "\\E7＊ はずかしがらず^1に&　 おじさんと/%";
        assertEquals(original, reapply(original, strip(original)));
    }

    // ---------- política de ^1 ----------

    @Test
    void caretOneIsPreservedWhenTextIsUnchanged() {
        String original = "\\E2* Of course^1!/%";
        assertEquals(original, reapply(original, strip(original)));
    }

    @Test
    void caretOneIsRederivedFromPunctuationWhenTextChanges() {
        assertEquals("\\E7* Vaia^1, bo traxe^1, Ralsei?\\f0/%",
                reapply("\\E7* Damn^1, nice outfit^1, Ralsei?\\f0/%", "Vaia, bo traxe, Ralsei?"));
    }

    @Test
    void rederivedCaretOneReachesTerminalPunctuationOnlyIfOriginalHadItThere() {
        // o orixinal levaba ^1 antes do "!" final → mantense na tradución
        assertEquals("\\E2* Pois claro^1!/%",
                reapply("\\E2* Of course^1!/%", "Pois claro!", CaretOnePolicy.REDERIVE));
        // o orixinal non o levaba → non se engade ao final
        assertEquals("\\E2* Pois claro!/%",
                reapply("\\E2* Of course^1, meu!/%", "Pois claro!", CaretOnePolicy.REDERIVE));
    }

    @Test
    void rederivedCaretOneHandlesEllipsisAndFullwidthPunctuation() {
        assertEquals("\\E0* そうか^1。だが^1、ちがう^1！/%",
                reapply("\\E0* そうか^1。だが^1、それは違う^1！/%", "そうか。だが、ちがう！",
                        CaretOnePolicy.REDERIVE));
    }

    @Test
    void caretOneIsNeverInjectedInsideAMarker() {
        // \f0 contén un "0", non puntuación, pero a comprobación fai sobre o texto
        // plano: ningún ^1 pode acabar dentro dun marcador
        String out = reapply("\\E7* Damn^1, nice.\\f0/%", "Vaia, ben.");
        assertEquals("\\E7* Vaia^1, ben.\\f0/%", out);
    }

    @Test
    void emptyUserTextKeepsMarkersAndEnd() {
        assertEquals("\\E0* /%", reapply("\\E0* Hello/%", ""));
    }

    @Test
    void deletedPlaceholdersKeepTheirMarkersAtTheEnd() {
        // se o tradutor borra un placeholder, o marcador non se perde
        assertEquals("Texto sen cor\\cY\\cW", reapply("\\cYCores\\cW", "Texto sen cor"));
    }

    @Test
    void nullUserTextIsTreatedAsEmpty() {
        assertEquals("/%", MarkerReapplier.reapply(List.of(Token.end("/%", "/%")), null));
    }
}
