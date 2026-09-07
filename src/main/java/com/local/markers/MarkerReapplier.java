package com.local.markers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reaplica os marcadores dunha liña orixinal sobre o texto plano que escribiu o
 * usuario.
 *
 * <p>
 * Un só algoritmo para todas as liñas: primeiro {@link #compile} converte o
 * fluxo de tokens nun «molde» por liña visual ({@link LineSpec}) e despois
 * renderízase o texto do usuario contra ese molde.
 *
 * <ul>
 * <li>os marcadores de formato que abrían unha liña visual ({@code \E0},
 * {@code * }...) reemítense ao comezo da liña correspondente do usuario, non
 * amontoados ao principio de todo</li>
 * <li>os que ían no medio ancóranse pola posición en caracteres visibles,
 * limitada ao longo da liña traducida</li>
 * <li>os relocalizables recupéranse dos placeholders {@code * ~ @ $} na orde en
 * que aparecían no orixinal</li>
 * <li>as pausas {@code ^n} insírense no primeiro límite de palabra a partir da
 * súa posición orixinal (non ao comezo da liña)</li>
 * <li>os saltos de liña usan os marcadores orixinais e, se o usuario engade máis
 * liñas das que había, repítese o último marcador usado</li>
 * <li>os marcadores de fin e os espazos finais engádense verbatim ao remate</li>
 * </ul>
 *
 * <pre>
 * Orixinal : SUSIE GOT THE \cYPOWER CROISSANT\cW
 * Usuario  : SUSIE RECIBIU O *CRUASÁN DO PODER*
 * Resultado: SUSIE RECIBIU O \cYCRUASÁN DO PODER\cW
 * </pre>
 *
 * As comiñas emítense sen escapar: o escape de JSON é responsabilidade de Gson
 * ao gardar ({@code JsonIo.writeAtomic}). Escapalas aquí producía
 * {@code \\"} no ficheiro e unha barra visible no xogo.
 */
public final class MarkerReapplier {

    private MarkerReapplier() {
    }

    /** Marcador ancorado a unha posición en caracteres visibles da súa liña. */
    private record Anchored(Token token, int visibleIndex) {
    }

    /**
     * Molde dunha liña visual do orixinal.
     *
     * @param prefix     marcadores fixos anteriores ao primeiro carácter visible
     * @param mid        marcadores fixos intermedios, ancorados
     * @param suffix     marcadores posteriores ao último carácter visible, na orde
     *                   orixinal (formatos e pausas mesturados)
     * @param pendings   pausas {@code ^n} ancoradas dentro do texto
     * @param leadReloc  cantos relocalizables abrían a liña antes do primeiro
     *                   carácter visible ({@code ~1* Texto}, {@code \cp* Texto}):
     *                   os marcadores ancorados dentro dese tramo desprázanse aos
     *                   placeholders que o usuario escribise de verdade
     * @param newlineRaw marcador de salto que pechaba a liña, ou null se é a última
     */
    private record LineSpec(List<Token> prefix, List<Anchored> mid, List<Token> suffix,
            List<Anchored> pendings, int leadReloc, String newlineRaw) {
    }

    public static String reapply(List<Token> originalTokens, String newPlain) {
        return reapply(originalTokens, newPlain, CaretOnePolicy.REDERIVE_IF_CHANGED);
    }

    public static String reapply(List<Token> originalTokens, String newPlain, CaretOnePolicy caretOne) {
        String plain = newPlain == null ? "" : newPlain;

        boolean hasCaretOne = originalTokens.stream()
                .anyMatch(t -> t.isPending() && CARET_ONE.equals(t.text()));
        boolean rederive = hasCaretOne && switch (caretOne) {
            case PRESERVE -> false;
            case REDERIVE -> true;
            case REDERIVE_IF_CHANGED -> !plain.equals(MarkerStripper.strip(originalTokens));
        };

        if (!rederive) {
            // os ^1 viaxan como calquera outra pausa, ancorados á súa posición
            return render(originalTokens, plain, null);
        }

        List<Token> tokens = originalTokens.stream()
                .filter(t -> !(t.isPending() && CARET_ONE.equals(t.text())))
                .toList();
        List<List<Integer>> caretOffsets = caretOneOffsets(plain, hadCaretOneBeforeTerminal(originalTokens));
        return render(tokens, plain, caretOffsets);
    }

    // ------------------------------------------------------------------
    // compilación do molde
    // ------------------------------------------------------------------

    private static List<LineSpec> compile(List<Token> tokens) {
        List<LineSpec> specs = new ArrayList<>();

        List<Token> prefix = new ArrayList<>();
        List<Anchored> mid = new ArrayList<>();
        List<Anchored> pendings = new ArrayList<>();
        // marcadores vistos despois do último carácter visible ata agora: se chega
        // máis texto visible resólvense como intermedios, se non quedan como sufixo
        List<Token> trailing = new ArrayList<>();
        int visible = 0;
        boolean anyVisible = false;
        int leadReloc = 0;
        boolean anyPlainVisible = false;

        for (Token t : tokens) {
            if (t.isNewline()) {
                specs.add(new LineSpec(List.copyOf(prefix), List.copyOf(mid),
                        List.copyOf(trailing), List.copyOf(pendings), leadReloc, t.raw()));
                prefix.clear();
                mid.clear();
                pendings.clear();
                trailing.clear();
                visible = 0;
                anyVisible = false;
                leadReloc = 0;
                anyPlainVisible = false;
                continue;
            }
            if (t.isEnd() || t.type() == TokenType.TRAILING_WS) {
                continue; // engádense ao final, fóra do molde de liñas
            }

            if (t.isVisible() || Markers.isRelocatable(t)) {
                for (Token pendingMarker : trailing) {
                    if (pendingMarker.isPending()) {
                        pendings.add(new Anchored(pendingMarker, visible));
                    } else {
                        mid.add(new Anchored(pendingMarker, visible));
                    }
                }
                trailing.clear();
                // un relocalizable ocupa un carácter (o seu placeholder) no texto limpo
                visible += t.isVisible() ? t.clean().length() : 1;
                anyVisible = true;
                if (t.isVisible()) {
                    anyPlainVisible = true;
                } else if (!anyPlainVisible) {
                    leadReloc++;
                }
            } else if (t.isFormat() && !anyVisible && trailing.isEmpty()) {
                prefix.add(t);
            } else if (t.isFormat() || t.isPending()) {
                trailing.add(t);
            }
        }

        specs.add(new LineSpec(List.copyOf(prefix), List.copyOf(mid),
                List.copyOf(trailing), List.copyOf(pendings), leadReloc, null));
        return specs;
    }

    // ------------------------------------------------------------------
    // renderizado
    // ------------------------------------------------------------------

    /**
     * @param caretOffsets posicións por liña onde inserir {@code ^1} deducido da
     *                     puntuación, ou null para non deducir ningún
     */
    private static String render(List<Token> tokens, String newPlain, List<List<Integer>> caretOffsets) {
        List<LineSpec> specs = compile(tokens);
        Map<Character, Deque<String>> relocatable = relocatableQueues(tokens);
        String[] userLines = newPlain.split("\n", -1);

        StringBuilder out = new StringBuilder();
        String lastNewline = null;

        for (int lineNum = 0; lineNum < userLines.length; lineNum++) {
            // se o usuario engade liñas de máis, reutilízase o molde da última
            LineSpec spec = specs.get(Math.min(lineNum, specs.size() - 1));
            String userLine = userLines[lineNum];

            for (Token t : spec.prefix()) {
                out.append(t.text());
            }

            // Cantos placeholders abren realmente a liña que escribiu o usuario.
            // Se o orixinal era "~1* Texto" e o usuario parte a liña en dúas, a
            // segunda xa non leva o "~": sen isto o "* " entraría un carácter
            // dentro do texto ("\\Ex* ogar").
            int lead = leadingPlaceholders(userLine, relocatable);
            Deque<Anchored> mid = new ArrayDeque<>(shiftLead(spec.mid(), spec.leadReloc(), lead));
            Deque<Anchored> pendings = new ArrayDeque<>(
                    shiftLead(spec.pendings(), spec.leadReloc(), lead));
            Deque<Integer> carets = new ArrayDeque<>(
                    caretOffsets != null && lineNum < caretOffsets.size()
                            ? caretOffsets.get(lineNum)
                            : List.of());
            Character lastChar = null;

            for (int i = 0; i < userLine.length(); i++) {
                char ch = userLine.charAt(i);

                while (!mid.isEmpty() && mid.peek().visibleIndex() <= i) {
                    out.append(mid.poll().token().text());
                }

                // as pausas entran no primeiro límite de palabra a partir da súa ancoraxe
                boolean atWordBoundary = lastChar == null
                        || !Markers.isWordLetter(lastChar) || !Markers.isWordLetter(ch);
                while (!pendings.isEmpty() && pendings.peek().visibleIndex() <= i && atWordBoundary) {
                    out.append(pendings.poll().token().text());
                }

                Deque<String> queue = relocatable.get(ch);
                if (queue != null && !queue.isEmpty()) {
                    out.append(queue.poll());
                    lastChar = null; // un marcador non forma parte dunha palabra
                    continue;
                }

                if (!carets.isEmpty() && carets.peek() == i) {
                    carets.poll();
                    out.append(CARET_ONE);
                }

                out.append(ch);
                lastChar = ch;
            }

            while (!mid.isEmpty()) {
                out.append(mid.poll().token().text());
            }
            while (!pendings.isEmpty()) {
                out.append(pendings.poll().token().text());
            }
            for (Token t : spec.suffix()) {
                out.append(t.text());
            }

            if (lineNum < userLines.length - 1) {
                String marker = spec.newlineRaw() != null ? spec.newlineRaw()
                        : (lastNewline != null ? lastNewline : "&");
                out.append(marker);
                lastNewline = marker;
            }
        }

        // marcadores relocalizables que o usuario borrou: mellor conservalos ao final
        // que perdelos silenciosamente
        for (Deque<String> queue : relocatable.values()) {
            while (!queue.isEmpty()) {
                out.append(queue.poll());
            }
        }

        // fin de texto e espazos finais, verbatim (raw inclúe o espazo previo)
        for (Token t : tokens) {
            if (t.isEnd() || t.type() == TokenType.TRAILING_WS) {
                out.append(t.raw());
            }
        }

        return out.toString();
    }

    /**
     * Reancora ao inicio real da liña do usuario os marcadores que no orixinal
     * ían dentro do tramo de relocalizables inicial. Os demais non se tocan.
     */
    private static List<Anchored> shiftLead(List<Anchored> anchors, int leadReloc, int lead) {
        if (leadReloc == 0 || lead >= leadReloc) {
            return anchors;
        }
        List<Anchored> out = new ArrayList<>(anchors.size());
        for (Anchored a : anchors) {
            out.add(a.visibleIndex() <= leadReloc
                    ? new Anchored(a.token(), Math.min(a.visibleIndex(), lead))
                    : a);
        }
        return out;
    }

    /** Placeholders con marcador pendente que abren a liña do usuario. */
    private static int leadingPlaceholders(String userLine, Map<Character, Deque<String>> relocatable) {
        int i = 0;
        while (i < userLine.length()) {
            Deque<String> queue = relocatable.get(userLine.charAt(i));
            if (queue == null || queue.isEmpty()) {
                break;
            }
            i++;
        }
        return i;
    }

    /** Colas FIFO de marcadores relocalizables, indexadas polo seu placeholder. */
    private static Map<Character, Deque<String>> relocatableQueues(List<Token> tokens) {
        Map<Character, Deque<String>> queues = new LinkedHashMap<>();
        for (Token t : tokens) {
            char ph = Markers.placeholderFor(t);
            if (ph != 0) {
                queues.computeIfAbsent(ph, k -> new ArrayDeque<>()).add(t.text());
            }
        }
        return queues;
    }

    // ------------------------------------------------------------------
    // dedución das pausas ^1 a partir da puntuación
    // ------------------------------------------------------------------

    private static final String CARET_ONE = "^1";
    /** Puntuación que sempre leva pausa diante (inclúe as variantes de ancho completo). */
    private static final String PAUSE_ALWAYS = ",;:，、；：";
    /** Exclamación e interrogación: unha soa pausa por grupo consecutivo. */
    private static final String PAUSE_RUN = "!?！？";
    /** Punto e puntos suspensivos: pausa antes do último dunha serie. */
    private static final String PAUSE_DOT = ".…。";

    /**
     * Posicións (por liña do texto do usuario) nas que hai que inserir un
     * {@code ^1}, sempre <em>antes</em> do carácter de puntuación.
     *
     * <p>
     * Traballa sobre o texto plano, non sobre o resultado con marcadores: así non
     * pode inxectar un {@code ^1} dentro dun marcador.
     *
     * @param allowTerminal se o orixinal xa levaba un {@code ^1} antes da
     *                      puntuación final, tamén se pon aí
     */
    private static List<List<Integer>> caretOneOffsets(String plain, boolean allowTerminal) {
        String[] lines = plain.split("\n", -1);
        List<List<Integer>> out = new ArrayList<>(lines.length);

        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
            String s = lines[lineNum];
            boolean lastLine = lineNum == lines.length - 1;
            List<Integer> offsets = new ArrayList<>();

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean insert = false;

                if (PAUSE_ALWAYS.indexOf(c) >= 0) {
                    insert = true;
                } else if (PAUSE_RUN.indexOf(c) >= 0) {
                    boolean firstInRun = i == 0 || PAUSE_RUN.indexOf(s.charAt(i - 1)) < 0;
                    insert = firstInRun && (allowTerminal || !isSentenceEnd(s, i, lastLine));
                } else if (PAUSE_DOT.indexOf(c) >= 0) {
                    boolean lastInRun = i + 1 >= s.length() || PAUSE_DOT.indexOf(s.charAt(i + 1)) < 0;
                    insert = lastInRun && (allowTerminal || !isSentenceEnd(s, i, lastLine));
                }

                if (insert) {
                    offsets.add(i);
                }
            }
            out.add(offsets);
        }
        return out;
    }

    /**
     * True se a partir de {@code i} xa non hai máis texto: só puntuación final (e
     * un posible paréntese de peche) ata o final da última liña. Nunha liña que
     * non é a última nunca é fin de frase, porque despois vén máis texto.
     */
    private static boolean isSentenceEnd(String s, int i, boolean lastLine) {
        if (!lastLine) {
            return false;
        }
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (PAUSE_RUN.indexOf(c) < 0 && PAUSE_DOT.indexOf(c) < 0 && c != ')') {
                return false;
            }
        }
        return true;
    }

    /** True se o orixinal levaba un {@code ^1} xusto antes da puntuación final da liña. */
    private static boolean hadCaretOneBeforeTerminal(List<Token> tokens) {
        return TERMINAL_CARET_ONE.matcher(MarkerTokenizer.rawJoin(tokens)).find();
    }

    private static final java.util.regex.Pattern TERMINAL_CARET_ONE = java.util.regex.Pattern
            .compile("\\^1[.!?…。！？]+\\s*[)]?\\s*(?:/%|/|%+)?\\s*$");
}
