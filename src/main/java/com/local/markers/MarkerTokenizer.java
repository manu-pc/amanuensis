package com.local.markers;

import java.util.ArrayList;
import java.util.List;

/**
 * Converte unha liña orixinal do xogo nun fluxo de {@link Token}.
 *
 * Marcadores recoñecidos:
 * <ul>
 * <li>{@code \cX} / {@code \CX} — cor de texto (relocalizable, placeholder *)</li>
 * <li>{@code \En}, {@code \Mn}, {@code \fn}, {@code \R}... — formato de posición fixa</li>
 * <li>{@code ^n} — pausa de n fotogramas</li>
 * <li>{@code ~n} — substitución do mod (relocalizable, placeholder ~), agás na
 * familia {@code ~1* Texto}: alí o {@code ~1} inicial é fixo (ocupa a rañura da
 * expresión, coma un {@code \EX}) e os {@code ~2} seguintes son saltos de liña</li>
 * <li>{@code \On} / {@code \In} — relocalizables, placeholders @ e $</li>
 * <li>{@code &amp;}, {@code #}, {@code \n} — saltos de liña</li>
 * <li>{@code /}, {@code /%}, {@code %}, {@code %%} — fin de texto</li>
 * <li>{@code * } ao inicio dunha liña visual — indicador de diálogo (invisible)</li>
 * </ul>
 *
 * Función pura: mesmo texto de entrada, mesmo fluxo de saída.
 */
public final class MarkerTokenizer {

    private MarkerTokenizer() {
    }

    /** Concatena o {@code raw} de todos os tokens; debe reproducir a entrada exacta. */
    public static String rawJoin(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            sb.append(t.raw());
        }
        return sb.toString();
    }

    public static List<Token> tokenize(String original) {
        return tokenize(original, ParenPolicy.VISIBLE_CHARS);
    }

    public static List<Token> tokenize(String original, ParenPolicy parenPolicy) {
        List<Token> out = new ArrayList<>();

        String full = original == null ? "" : original;

        // Cortamos a liña en catro anacos, sempre con índices sobre o orixinal, de
        // xeito que concatenar os raw dos tokens reproduza a entrada exacta:
        //     corpo | espazo antes do marcador | marcador de fin | espazos finais
        int wsStart = full.length();
        while (wsStart > 0 && Character.isWhitespace(full.charAt(wsStart - 1))) {
            wsStart--;
        }
        String trailingWs = full.substring(wsStart);
        String core = full.substring(0, wsStart);

        // marcadores finais: /% e / teñen prioridade sobre a serie de %
        String endMarker = "";
        if (core.endsWith("/%")) {
            endMarker = "/%";
        } else if (core.endsWith("/")) {
            endMarker = "/";
        } else {
            int p = core.length();
            while (p > 0 && core.charAt(p - 1) == '%') {
                p--;
            }
            endMarker = core.substring(p);
        }

        String s = core.substring(0, core.length() - endMarker.length());
        String endSpacer = "";
        if (!endMarker.isEmpty() && s.endsWith(" ")) {
            // o espazo antes do marcador de fin non é texto: viaxa co marcador
            endSpacer = " ";
            s = s.substring(0, s.length() - 1);
        }

        int i = 0;
        int n = s.length();

        // Se a liña xa usa saltos de liña reais (\n no JSON) ou literais (\\n),
        // o "&" non é un marcador de salto: é un carácter visible normal.
        boolean hasNewlineEscape = s.indexOf('\n') >= 0 || s.contains("\\n");

        // Detectar "* " ao inicio da liña como token de formato fixo
        // (indicador de diálogo, non debe confundirse co placeholder de cor)
        if (n >= 2 && s.charAt(0) == '*' && s.charAt(1) == ' ') {
            out.add(Token.format("* "));
            i = 2;
        } else if (n >= 2 && s.charAt(0) == '*' && s.charAt(1) == '\\') {
            out.add(Token.format("*"));
            i = 1;
        }

        boolean atLineStart = false;
        // "~1* Texto" (767 liñas do capítulo 5, tamén "\E~1* Texto"): o ~n inicial
        // non é unha substitución de texto senón a rañura da expresión, e o ~2 que
        // vén despois é o salto de liña do xogo (o anaco anterior mide 13-38
        // caracteres visibles, o ancho dunha liña de diálogo). Vense así.
        boolean tildeNewlines = false;
        boolean lineHasVisible = false;

        while (i < n) {
            if (!out.isEmpty() && out.get(out.size() - 1).isVisible()) {
                lineHasVisible = true;
            }
            char c = s.charAt(i);

            // Salto de liña literal (carácter \n real no string)
            if (c == '\n') {
                out.add(Token.newline("\n"));
                i++;
                atLineStart = true;
                lineHasVisible = false;
                continue;
            }

            // Marcador # = salto de liña
            if (c == '#') {
                out.add(Token.newline("#"));
                i++;
                atLineStart = true;
                lineHasVisible = false;
                continue;
            }

            // Marcador & = salto de liña (só se a liña non usa xa \n)
            if (c == '&' && !hasNewlineEscape) {
                out.add(Token.newline("&"));
                i++;
                atLineStart = true;
                lineHasVisible = false;
                continue;
            }

            // Secuencias con backslash
            if (c == '\\') {
                int j = i + 1;

                // comiña escapada \" -> comiña visible.
                // Nota: GSON xa desescapa \" ao parsear o JSON, así que na práctica
                // isto só se activa se o valor contén literalmente barra + comiña.
                if (j < n && s.charAt(j) == '"') {
                    out.add(Token.escapedQuote());
                    i = j + 1;
                    continue;
                }

                // \n literal no código fonte (2 caracteres: '\' e 'n')
                if (j < n && s.charAt(j) == 'n') {
                    out.add(Token.newline("\\n"));
                    i = j + 1;
                    continue;
                }

                // CASO ESPECIAL: Marcadores de cor \c ou \C
                // Formato: \c seguido de EXACTAMENTE un carácter (ex: \cY, \cW, \cR)
                if (j < n && (s.charAt(j) == 'c' || s.charAt(j) == 'C')) {
                    int end;
                    if (j + 1 < n) {
                        end = j + 2; // ex: \cY
                    } else {
                        end = j + 1; // \c ao final (caso límite)
                    }
                    out.add(Token.format(s.substring(i, end)));
                    // O prefixo de diálogo pode vir xusto detrás da cor
                    // (\cp* Texto), igual que detrás de calquera outro marcador
                    // (\E7* Texto). Sen isto, ese '*' líase como un placeholder de
                    // cor máis: o prefixo desaparecía e o \cX do final da liña
                    // remataba reaplicado ao principio.
                    i = consumeDialoguePrefix(s, end, out);
                    continue;
                }

                // detectar \ + letras (+ díxitos) patrón (ex: \E8, \M0, \R)
                int startLetters = j;
                while (startLetters < n && Character.isLetter(s.charAt(startLetters))) {
                    startLetters++;
                }

                if (startLetters == j) {
                    // non hai letras despois de \, buscar ata o próximo \ ou espazo
                    int k = j;
                    while (k < n && s.charAt(k) != '\\' && !Character.isWhitespace(s.charAt(k))) {
                        k++;
                    }
                    out.add(Token.format(s.substring(i, k)));
                    i = k;
                    continue;
                }

                int k = startLetters;
                while (k < n && Character.isDigit(s.charAt(k))) {
                    k++;
                }

                String tok = s.substring(i, k);

                // caso especial: \E3* Hello → \E3 (FORMAT) + "* " (FORMAT fixo)
                // \E8*text → \E8 (FORMAT) + * (VISIBLE)
                if (k < n && s.charAt(k) == '*') {
                    out.add(Token.format(tok));
                    i = consumeDialoguePrefix(s, k, out);
                    continue;
                }

                out.add(Token.format(tok));
                i = k;
            }
            // Marcadores ^n (pausa)
            else if (c == '^') {
                int j = i + 1;
                while (j < n && Character.isDigit(s.charAt(j))) {
                    j++;
                }
                out.add(Token.pending(s.substring(i, j))); // ex: ^1
                i = j;
            }
            // Marcadores ~n (substitución do mod ou salto de liña)
            else if (c == '~') {
                int j = i + 1;
                while (j < n && Character.isDigit(s.charAt(j))) {
                    j++;
                }

                // "~1* Texto": o ~n abre a liña na rañura da expresión e leva
                // pegado o prefixo de diálogo. É fixo, coma un \EX: se se deixa
                // como placeholder o tradutor ve un ~ que non debe tocar e, se a
                // liña ten cores, o primeiro \cX acaba no sitio do prefixo.
                if (!lineHasVisible && j < n && s.charAt(j) == '*') {
                    out.add(Token.format(s.substring(i, j)));
                    tildeNewlines = true;
                    i = consumeDialoguePrefix(s, j, out);
                    continue;
                }

                // ~2 dentro dunha liña desa familia: salto de liña. Só se toma un
                // díxito para que "más de~20 danos" conserve o seu 0 no texto limpo.
                if (tildeNewlines) {
                    int end = Math.min(i + 2, n);
                    out.add(Token.newline(s.substring(i, end)));
                    i = end;
                    atLineStart = true;
                    lineHasVisible = false;
                    continue;
                }

                out.add(Token.pending(s.substring(i, j))); // ex: ~1
                i = j;
            }
            // "* " ao inicio de liña (despois de newline ou inicio de string)
            else if (c == '*' && atLineStart && i + 1 < n && s.charAt(i + 1) == ' ') {
                out.add(Token.format("* "));
                i += 2;
            } else if (c == '*' && atLineStart && i + 1 < n && s.charAt(i + 1) == '\\') {
                out.add(Token.format("*"));
                i += 1;
            }
            // Calquera outro carácter = visible (parénteses incluídos: ver ParenPolicy)
            else {
                out.add(Token.visible(c));
                atLineStart = false;
                i++;
            }
        }

        if (parenPolicy == ParenPolicy.HIDDEN_BALANCED_WRAPPER) {
            hideBalancedWrapper(out);
        }

        if (!endMarker.isEmpty()) {
            out.add(Token.end(endSpacer + endMarker, endMarker));
        } else if (!endSpacer.isEmpty()) {
            out.add(Token.visible(endSpacer));
        }

        if (!trailingWs.isEmpty()) {
            out.add(Token.trailingWhitespace(trailingWs));
        }

        return out;
    }

    /**
     * Converte en marcadores invisibles o par de parénteses que envolve toda a
     * liña, se existe: apertura no primeiro carácter visible, peche no último e
     * sen quedar aberto en ningún punto intermedio. Non fai nada en calquera
     * outro caso (parénteses no medio, varios pares, desemparellados).
     */
    private static void hideBalancedWrapper(List<Token> tokens) {
        int first = -1;
        int last = -1;
        for (int k = 0; k < tokens.size(); k++) {
            if (tokens.get(k).isVisible()) {
                if (first < 0) {
                    first = k;
                }
                last = k;
            }
        }
        if (first < 0 || first == last
                || !"(".equals(tokens.get(first).text())
                || !")".equals(tokens.get(last).text())) {
            return;
        }

        int depth = 0;
        for (int k = first; k <= last; k++) {
            Token t = tokens.get(k);
            if (!t.isVisible()) {
                continue;
            }
            if ("(".equals(t.text())) {
                depth++;
            } else if (")".equals(t.text())) {
                depth--;
                // pecha o envoltorio antes do final: non é un envoltorio de liña
                if (depth == 0 && k != last) {
                    return;
                }
                if (depth < 0) {
                    return;
                }
            }
        }
        if (depth != 0) {
            return;
        }

        tokens.set(first, Token.format("("));
        tokens.set(last, Token.format(")"));
    }

    /**
     * Consome o prefixo de diálogo que veña xusto detrás dun marcador.
     *
     * En Deltarune o {@code * } que abre unha liña de diálogo pode ir pegado a
     * calquera marcador: {@code \E7* Ola} e tamén {@code \cp* Ola}. Ten que
     * quedar como FORMAT fixo; se se deixa como carácter normal confúndese co
     * placeholder de cor e a liña reconstrúese mal.
     *
     * @param at posición do posible {@code *}
     * @return posición seguinte a consumir
     */
    private static int consumeDialoguePrefix(String s, int at, List<Token> out) {
        int n = s.length();
        if (at >= n || s.charAt(at) != '*') {
            return at;
        }
        if (at + 1 < n && s.charAt(at + 1) == ' ') {
            out.add(Token.format("* "));
            return at + 2;
        }
        out.add(Token.visible('*'));
        return at + 1;
    }

}
