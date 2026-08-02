package com.local.markers;

import java.util.List;

/**
 * Representación da liña orixinal para a vista literal do editor: o texto tal
 * cal, pero cos marcadores de salto de liña marcados co glifo {@code ⏎} e
 * seguidos dun salto real, para que se vexa onde corta o xogo.
 *
 * Vai guiado polos tokens (unha soa pasada) en vez de por
 * {@code String.replace} en cadea: así un {@code &amp;} que non é salto de liña
 * (liñas que xa usan {@code \n}) móstrase como carácter normal, e o salto que se
 * insire para a visualización non se volve reescribir a si mesmo.
 */
public final class MarkerRenderer {

    private MarkerRenderer() {
    }

    public static String literalForDisplay(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            if (t.isNewline()) {
                sb.append(Markers.NEWLINE_GLYPH)
                        .append(t.raw().equals("\n") ? "\\n" : t.raw())
                        .append('\n');
            } else {
                sb.append(t.raw());
            }
        }
        return sb.toString();
    }
}
