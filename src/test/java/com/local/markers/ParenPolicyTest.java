package com.local.markers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ParenPolicyTest {

    private static String cleanWith(String original, ParenPolicy policy) {
        return MarkerStripper.strip(MarkerTokenizer.tokenize(original, policy));
    }

    private static String roundtrip(String original, ParenPolicy policy) {
        List<Token> tokens = MarkerTokenizer.tokenize(original, policy);
        return MarkerReapplier.reapply(tokens, MarkerStripper.strip(tokens));
    }

    @Test
    void visibleCharsIsTheDefault() {
        assertEquals(cleanWith("* (Take it?)/", ParenPolicy.VISIBLE_CHARS),
                MarkerStripper.strip(MarkerTokenizer.tokenize("* (Take it?)/")));
    }

    @Test
    void hiddenWrapperHidesAFullLineWrapper() {
        assertEquals("Take it?", cleanWith("* (Take it?)/", ParenPolicy.HIDDEN_BALANCED_WRAPPER));
        assertEquals("* (Take it?)/", roundtrip("* (Take it?)/", ParenPolicy.HIDDEN_BALANCED_WRAPPER));
    }

    @Test
    void hiddenWrapperLeavesMidTextParensAlone() {
        String original = "* Text (an aside) more./";
        assertEquals("Text (an aside) more.", cleanWith(original, ParenPolicy.HIDDEN_BALANCED_WRAPPER));
    }

    @Test
    void hiddenWrapperLeavesUnbalancedParensAlone() {
        // ")" sen "(": antes desaparecía do texto limpo
        assertEquals("BUT IT SUCKS... :(", cleanWith("BUT IT SUCKS... :(/%", ParenPolicy.HIDDEN_BALANCED_WRAPPER));
        assertEquals("(sen pechar", cleanWith("* (sen pechar/%", ParenPolicy.HIDDEN_BALANCED_WRAPPER));
    }

    @Test
    void hiddenWrapperLeavesTwoSeparatePairsAlone() {
        assertEquals("(a) e (b)", cleanWith("* (a) e (b)/%", ParenPolicy.HIDDEN_BALANCED_WRAPPER));
    }

    @Test
    void hiddenWrapperOnlyHidesTheOutermostPair() {
        assertEquals("(a (b) c)", cleanWith("* ((a (b) c))/%", ParenPolicy.HIDDEN_BALANCED_WRAPPER));
        assertEquals("* ((a (b) c))/%", roundtrip("* ((a (b) c))/%", ParenPolicy.HIDDEN_BALANCED_WRAPPER));
    }

    @Test
    void visibleCharsKeepsEveryParen() {
        assertEquals("(Take it?)", cleanWith("* (Take it?)/", ParenPolicy.VISIBLE_CHARS));
        assertEquals(":(", cleanWith(":(/%", ParenPolicy.VISIBLE_CHARS));
        assertEquals("* (a) e (b)/%", roundtrip("* (a) e (b)/%", ParenPolicy.VISIBLE_CHARS));
    }
}
