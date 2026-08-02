package com.local.markers;

import java.util.List;

/**
 * Converte un fluxo de tokens no texto plano que edita o usuario: os
 * marcadores invisibles desaparecen, os saltos de liña vólvense saltos reais e
 * os marcadores relocalizables deixan o seu placeholder ({@code * ~ @ $}).
 *
 * <pre>
 * Orixinal: SUSIE GOT THE \cYPOWER CROISSANT\cW
 * Limpo   : SUSIE GOT THE *POWER CROISSANT*
 * </pre>
 */
public final class MarkerStripper {

    private MarkerStripper() {
    }

    public static String strip(String original) {
        return strip(MarkerTokenizer.tokenize(original));
    }

    public static String strip(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            if (t.isVisible() || t.isNewline()) {
                sb.append(t.clean());
            } else {
                char ph = Markers.placeholderFor(t);
                if (ph != 0) {
                    sb.append(ph);
                }
            }
            // PENDING (^n), FORMAT non relocalizable, END e TRAILING_WS non
            // achegan texto visible.
        }
        return sb.toString();
    }
}
