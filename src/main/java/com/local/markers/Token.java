package com.local.markers;

/**
 * Unidade do fluxo de tokens dunha liña.
 *
 * @param type  tipo de token
 * @param raw   anaco EXACTO do orixinal que produciu este token (incluídos
 *              espazos adxacentes que pertenzan ao marcador). Concatenar o
 *              {@code raw} de todos os tokens dunha liña debe devolver o
 *              orixinal byte a byte (ver
 *              {@link MarkerTokenizer#rawJoin(java.util.List)})
 * @param text  texto que se emite ao reaplicar formato (normalmente igual a
 *              {@code raw}, sen os espazos de relleno)
 * @param clean texto que este token achega ao texto limpo que edita o usuario
 *              ("" para marcadores invisibles, "\n" para saltos de liña)
 */
public record Token(TokenType type, String raw, String text, String clean) {

    public static Token visible(char c) {
        return new Token(TokenType.VISIBLE, String.valueOf(c), String.valueOf(c), String.valueOf(c));
    }

    public static Token visible(String s) {
        return new Token(TokenType.VISIBLE, s, s, s);
    }

    /** Comiña escapada no orixinal (\") que se mostra como comiña simple. */
    public static Token escapedQuote() {
        return new Token(TokenType.VISIBLE, "\\\"", "\"", "\"");
    }

    public static Token format(String raw) {
        return new Token(TokenType.FORMAT, raw, raw, "");
    }

    public static Token pending(String raw) {
        return new Token(TokenType.PENDING, raw, raw, "");
    }

    public static Token newline(String raw) {
        return new Token(TokenType.NEWLINE, raw, raw, "\n");
    }

    /**
     * Marcador final. {@code raw} pode incluír o espazo que o precede no
     * orixinal; {@code text} é só o marcador.
     */
    public static Token end(String raw, String text) {
        return new Token(TokenType.END, raw, text, "");
    }

    public static Token trailingWhitespace(String raw) {
        return new Token(TokenType.TRAILING_WS, raw, raw, "");
    }

    public boolean isVisible() {
        return type == TokenType.VISIBLE;
    }

    public boolean isFormat() {
        return type == TokenType.FORMAT;
    }

    public boolean isPending() {
        return type == TokenType.PENDING;
    }

    public boolean isNewline() {
        return type == TokenType.NEWLINE;
    }

    public boolean isEnd() {
        return type == TokenType.END;
    }

    @Override
    public String toString() {
        return type + "(" + raw.replace("\n", "\\n") + ")";
    }
}
