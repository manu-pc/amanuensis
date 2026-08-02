package com.local.markers;

/**
 * Constantes e clasificadores do sistema de marcadores. Único lugar onde viven
 * os caracteres de reserva (placeholders) que o usuario ve no texto limpo:
 * calquera outro compoñente (editor, corrector ortográfico) debe preguntar aquí
 * en vez de repetir a lista.
 */
public final class Markers {
    /** Reserva para os marcadores de cor \cX / \CX. */
    public static final char PH_COLOR = '*';
    /** Reserva para os marcadores de efecto ~n. */
    public static final char PH_TILDE = '~';
    /** Reserva para os marcadores \On. */
    public static final char PH_O = '@';
    /** Reserva para os marcadores \In. */
    public static final char PH_I = '$';
    /** Todos os placeholders relocalizables. */
    public static final String PLACEHOLDERS = "" + PH_COLOR + PH_TILDE + PH_O + PH_I;
    /** Glifo co que se representa un marcador de salto de liña na vista literal. */
    public static final char NEWLINE_GLYPH = '⏎';

    private Markers() {
    }

    public static boolean isPlaceholder(char c) {
        return PLACEHOLDERS.indexOf(c) >= 0;
    }

    /**
     * Substitúe cada placeholder por un espazo conservando a lonxitude, para que
     * as posicións do texto limpo sigan valendo (usado polo corrector).
     */
    public static String blankPlaceholders(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(isPlaceholder(c) ? ' ' : c);
        }
        return sb.toString();
    }

    /**
     * Letra que forma parte dunha palabra separada por espazos. As escrituras que
     * escriben sen espazos (han, kana, hangul, tailandés) non contan: aí calquera
     * posición é válida para inserir unha pausa, e tratalas como unha única
     * palabra desprazaría os {@code ^n} ao final da frase.
     */
    public static boolean isWordLetter(char c) {
        if (!Character.isLetter(c)) {
            return false;
        }
        return switch (Character.UnicodeScript.of(c)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, THAI, LAO, KHMER, MYANMAR -> false;
            default -> true;
        };
    }

    public static boolean isColor(Token t) {
        return t.type() == TokenType.FORMAT
                && (t.text().startsWith("\\c") || t.text().startsWith("\\C"));
    }

    public static boolean isTilde(Token t) {
        return t.type() == TokenType.PENDING && t.text().startsWith("~");
    }

    public static boolean isBackslashO(Token t) {
        return t.type() == TokenType.FORMAT && t.text().startsWith("\\O");
    }

    public static boolean isBackslashI(Token t) {
        return t.type() == TokenType.FORMAT && t.text().startsWith("\\I");
    }

    /** Marcador que o usuario pode mover no texto limpo mediante o seu placeholder. */
    public static boolean isRelocatable(Token t) {
        return isColor(t) || isTilde(t) || isBackslashO(t) || isBackslashI(t);
    }

    /** Placeholder que representa este marcador no texto limpo, ou 0 se non é relocalizable. */
    public static char placeholderFor(Token t) {
        if (isColor(t)) {
            return PH_COLOR;
        }
        if (isTilde(t)) {
            return PH_TILDE;
        }
        if (isBackslashO(t)) {
            return PH_O;
        }
        if (isBackslashI(t)) {
            return PH_I;
        }
        return 0;
    }
}
