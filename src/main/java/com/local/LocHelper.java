package com.local;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Clase encargada do manexo de simbolos marcadores de texto para
 * Undertale/Deltarune.
 *
 * Funcións principais:
 * - texto orixinal --> texto sen marcadores (stripFormatting)
 * - texto sen marcadores modificado --> texto con marcadores
 * (reapplyFormatting)
 *
 * Marcadores soportados:
 * - \cXXX, \CXXX : cor de texto (ex: \cYLANCER, \cW)
 * - \En : expresión/sprite do personaxe (ex: \E0, \E8)
 * - \Mn : modo de voz (ex: \M0, \M1)
 * - \R, \ER, etc. : outros modificadores
 * - ^n : pausa de n frames (ex: ^1, ^3)
 * - ~n : efecto de texto (ex: ~1)
 * - & : salto de liña (newline)
 * - # : salto de liña (newline, alternativo)
 * - \n : salto de liña literal no JSON
 * - / /% % %% : marcadores de fin de texto
 * - \" : comilla escapada (visible como ")
 * - *texto* : marcador de cor (o usuario usa asteriscos para indicar texto
 * coloreado)
 * - ~ : placeholder para marcadores ~n (relocatable)
 * - @ : placeholder para marcadores \On (relocatable)
 * - $ : placeholder para marcadores \In (relocatable)
 */
@SuppressWarnings("StatementWithEmptyBody")
public class LocHelper {
    private final String filename;
    private final List<String> keys = new ArrayList<>(); // claves na orde do ficheiro
    private final List<String> originals = new ArrayList<>(); // valores orixinais na orde do ficheiro

    public LocHelper(String filename) throws IOException {
        this.filename = filename;
        readAndParseFile();
    }

    private void readAndParseFile() throws IOException {
        String jsonText = Files.readString(Path.of(filename));
        JsonObject obj = JsonParser.parseString(jsonText).getAsJsonObject();

        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            keys.add(e.getKey());
            originals.add(e.getValue().getAsString());
        }
    }

    public int getLineCount() {
        return originals.size();
    }

    public String getKey(int lineIndex) {
        return keys.get(lineIndex);
    }

    public String getOriginal(int lineIndex) {
        return originals.get(lineIndex);
    }

    /**
     * Actualiza o valor orixinal en memoria para a liña indicada.
     * Útil para manter a lista en sincronía despois de gardar cambios.
     */
    public void updateOriginal(int lineIndex, String newValue) {
        originals.set(lineIndex, newValue);
    }

    /**
     * Conta o número de saltos de liña (newline markers) no texto orixinal.
     * Útil para que o usuario saiba cantas liñas ten o texto orixinal.
     */
    public int countNewlines(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        int count = 0;
        for (Token t : tokens) {
            if (t.isNewline())
                count++;
        }
        return count;
    }

    /**
     * Devolve true se a liña contén marcadores de cor (\c ou \C).
     */
    public boolean hasColorMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        return original.contains("\\c") || original.contains("\\C");
    }

    /**
     * Extrae os marcadores de cor dunha liña na orde en que aparecen.
     * Devolve unha lista de strings (ex: ["\\cY", "\\cW"]).
     */
    private List<String> extractColorMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        List<String> colors = new ArrayList<>();
        for (Token t : tokens) {
            if (isColorToken(t)) {
                colors.add(t.text());
            }
        }
        return colors;
    }

    /**
     * Extrae os marcadores ~ dunha liña na orde en que aparecen.
     * Devolve unha lista de strings (ex: ["~1", "~2"]).
     */
    private List<String> extractTildeMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        List<String> tildes = new ArrayList<>();
        for (Token t : tokens) {
            if (isTildeToken(t)) {
                tildes.add(t.text());
            }
        }
        return tildes;
    }

    /**
     * Extrae os marcadores \O dunha liña na orde en que aparecen.
     * Devolve unha lista de strings (ex: ["\\O0", "\\O1"]).
     */
    private List<String> extractBackslashOMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        List<String> markers = new ArrayList<>();
        for (Token t : tokens) {
            if (isBackslashOToken(t)) {
                markers.add(t.text());
            }
        }
        return markers;
    }

    /**
     * Extrae os marcadores \I dunha liña na orde en que aparecen.
     * Devolve unha lista de strings (ex: ["\\I0", "\\I1"]).
     */
    private List<String> extractBackslashIMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        List<String> markers = new ArrayList<>();
        for (Token t : tokens) {
            if (isBackslashIToken(t)) {
                markers.add(t.text());
            }
        }
        return markers;
    }

    private static boolean isColorToken(Token t) {
        return t.type() == TokenType.FORMAT &&
                (t.text().startsWith("\\c") || t.text().startsWith("\\C"));
    }

    private static boolean isTildeToken(Token t) {
        return t.type() == TokenType.PENDING && t.text().startsWith("~");
    }

    private static boolean isBackslashOToken(Token t) {
        return t.type() == TokenType.FORMAT && t.text().startsWith("\\O");
    }

    private static boolean isBackslashIToken(Token t) {
        return t.type() == TokenType.FORMAT && t.text().startsWith("\\I");
    }

    /**
     * Devolve true se a liña contén marcadores de pausa (^n).
     */
    public boolean hasPauseMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        return original.contains("^");
    }

    /**
     * Devolve true se a liña contén marcadores ~n (efecto de texto).
     */
    public boolean hasTildeMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        return original.contains("~");
    }

    /**
     * Devolve true se a liña contén marcadores \O (ex: \O0, \O1).
     */
    public boolean hasBackslashOMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        return original.contains("\\O");
    }

    /**
     * Devolve true se a liña contén marcadores \I (ex: \I0, \I1).
     */
    public boolean hasBackslashIMarkers(int lineIndex) {
        String original = originals.get(lineIndex);
        return original.contains("\\I");
    }

    /**
     * Tipos de token para clasificar o comportamento no reapply:
     * - VISIBLE: carácter visible (aparece no texto limpo)
     * - FORMAT: marcador de formato (non visible, posición fixa)
     * - PENDING: marcador que se insire en límites de palabra (^n, ~n)
     * - NEWLINE: marcador que produce salto de liña (&, #, \n literal)
     * - END: marcador de fin de texto (/, /%, %, %%)
     */
    private enum TokenType {
        VISIBLE,
        FORMAT,
        PENDING,
        NEWLINE,
        END
    }

    /**
     * @param type      tipo de token
     * @param text      para VISIBLE: un único char (ou "\""); para outros: o token
     *                  completo
     * @param cleanText texto que este token produce no texto limpo (normalmente ""
     *                  ou "\n")
     */
    private record Token(TokenType type, String text, String cleanText) {
        Token(TokenType type, String text) {
            this(type, text, "");
        }

        boolean isVisible() {
            return type == TokenType.VISIBLE;
        }

        boolean isPending() {
            return type == TokenType.PENDING;
        }

        boolean isNewline() {
            return type == TokenType.NEWLINE;
        }

        boolean isEnd() {
            return type == TokenType.END;
        }

        @Override
        public String toString() {
            return type + "(" + text + (cleanText.isEmpty() ? "" : ",clean=" + cleanText.replace("\n", "\\n")) + ")";
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();

        if (s == null) s = "";

        // primeiro detectamos marcadores finais de barra /% ou / (tienen prioridade)
        String trimmed = rtrim(s);
        String endMarker = null;
        int endMarkerLen = 0;
        if (trimmed.endsWith("/%")) {
            endMarker = "/%";
            endMarkerLen = 2;
        } else if (trimmed.endsWith("/")) {
            endMarker = "/";
            endMarkerLen = 1;
        } else {
            // se non hai / ou /%, detectamos agora % finais (un ou varios)
            int tlen = trimmed.length();
            int p = tlen - 1;
            while (p >= 0 && trimmed.charAt(p) == '%') p--;
            if (p < tlen - 1) {
                int startPercent = p + 1;
                endMarker = trimmed.substring(startPercent); // "%%" ou "%"
                endMarkerLen = endMarker.length();
            }
        }

        if (endMarker != null && endMarkerLen > 0) {
            // eliminar a parte final correspondente do string s (tamén recortar espazo antes se existe)
            s = s.substring(0, s.length() - endMarkerLen);
            if (s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        }

        int i = 0;
        int n = s.length();

        // Detectar "* " ao inicio da liña como token de formato fixo
        // (indicador de diálogo, non debe confundirse co placeholder de cor)
        if (n >= 2 && s.charAt(0) == '*' && s.charAt(1) == ' ') {
            out.add(new Token(TokenType.FORMAT, "* "));
            i = 2;
        }

        while (i < n) {
            char c = s.charAt(i);

            // Salto de liña literal (carácter \n real no string)
            if (c == '\n') {
                out.add(new Token(TokenType.NEWLINE, "\n", "\n"));
                i++;
                continue;
            }

            // Marcador # = salto de liña
            if (c == '#') {
                out.add(new Token(TokenType.NEWLINE, "#", "\n"));
                i++;
                continue;
            }

            // Marcador & = salto de liña
            if (c == '&') {
                out.add(new Token(TokenType.NEWLINE, "&", "\n"));
                i++;
                continue;
            }

            // Secuencias con backslash
            if (c == '\\') {
                int j = i + 1;

                // escaped quote \" -> visible quote
                if (j < n && s.charAt(j) == '"') {
                    out.add(new Token(TokenType.VISIBLE, "\""));
                    i = j + 1;
                    continue;
                }

                // \n literal no código fonte (2 caracteres: '\' e 'n')
                // Nota: isto normalmente non ocorre porque GSON xa parsea \n como newline real
                // pero por se acaso alguén usa \\n no JSON
                if (j < n && s.charAt(j) == 'n') {
                    out.add(new Token(TokenType.NEWLINE, "\\n", "\n"));
                    i = j + 1;
                    continue;
                }

                // CASO ESPECIAL: Marcadores de cor \c ou \C
                // Formato: \c seguido de EXACTAMENTE un carácter (ex: \cY, \cW, \cR)
                if (j < n && (s.charAt(j) == 'c' || s.charAt(j) == 'C')) {
                    if (j + 1 < n) {
                        // \c + un carácter = token completo
                        String tok = s.substring(i, j + 2); // ex: \cY
                        out.add(new Token(TokenType.FORMAT, tok));
                        i = j + 2;
                        continue;
                    } else {
                        // \c ao final do string (caso edge)
                        String tok = s.substring(i, j + 1);
                        out.add(new Token(TokenType.FORMAT, tok));
                        i = j + 1;
                        continue;
                    }
                }

                // detectar \ + letters (+ digits) patrón (ex: \E8, \M0, \R)
                int startLetters = j;
                while (startLetters < n && Character.isLetter(s.charAt(startLetters))) startLetters++;

                if (startLetters == j) {
                    // non hai letras despois de \, buscar ata o próximo \ ou espazo
                    int k = j;
                    while (k < n && s.charAt(k) != '\\' && !Character.isWhitespace(s.charAt(k))) k++;
                    String tok = s.substring(i, k);
                    out.add(new Token(TokenType.FORMAT, tok));
                    i = k;
                    continue;
                }

                int k = startLetters;
                while (k < n && Character.isDigit(s.charAt(k))) k++;

                String tok = s.substring(i, k);

                // caso especial: \E3* Hello → \E3 (FORMAT) + "* " (FORMAT fixo)
                // \E8*text → \E8 (FORMAT) + * (VISIBLE)
                if (k < n && s.charAt(k) == '*') {
                    out.add(new Token(TokenType.FORMAT, tok));
                    if (k + 1 < n && s.charAt(k + 1) == ' ') {
                        // "* " é token de formato fixo (indicador de diálogo)
                        out.add(new Token(TokenType.FORMAT, "* "));
                        i = k + 2;
                    } else {
                        out.add(new Token(TokenType.VISIBLE, "*"));
                        i = k + 1;
                    }
                    continue;
                }

                out.add(new Token(TokenType.FORMAT, tok));
                i = k;
            }
            // Marcadores ^n (pausa)
            else if (c == '^') {
                int j = i + 1;
                while (j < n && Character.isDigit(s.charAt(j))) j++;
                String tok = s.substring(i, j); // ex: ^1
                out.add(new Token(TokenType.PENDING, tok));
                i = j;
            }
            // Marcadores ~n (efecto de texto)
            else if (c == '~') {
                int j = i + 1;
                while (j < n && Character.isDigit(s.charAt(j))) j++;
                String tok = s.substring(i, j); // ex: ~1
                out.add(new Token(TokenType.PENDING, tok));
                i = j;
            }
            // Calquera outro carácter = visible
            else {
                out.add(new Token(TokenType.VISIBLE, String.valueOf(c)));
                i++;
            }
        }

        if (endMarker != null && !endMarker.isEmpty()) {
            out.add(new Token(TokenType.END, endMarker));
        }

        return out;
    }

    private static String rtrim(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i)))
            i--;
        return s.substring(0, i + 1);
    }

    // -------------------------
    // métodos de instancia que usan as liñas cargadas
    // -------------------------

    /**
     * Devolve o texto plano sen marcadores da liña index (0-based).
     *
     * Regras aplicadas:
     * - & → newline
     * - # → newline
     * - \n (literal) → newline
     * - ^n → ignorados (son pausas)
     * - ~n → placeholder ~ (relocatable, como cores)
     * - \On → placeholder @ (relocatable, como cores)
     * - \In → placeholder $ (relocatable, como cores)
     * - \E, \M, \c, \C, etc. → ignorados
     * - /, /%, %, %% → ignorados
     * - \" → "
     *
     * CASO ESPECIAL - Marcadores de cor:
     * Se a liña contén marcadores \c ou \C, o texto coloreado rodéase con
     * asteriscos.
     * Exemplo:
     * Orixinal: SUSIE GOT THE \cYPOWER CROISSANT\cW
     * Limpo: SUSIE GOT THE *POWER CROISSANT*
     *
     * Exemplo:
     * Orixinal: \E0* Sure..^1. okay^1, we can try again./%
     * Limpo: * Sure... okay, we can try again.
     *
     * Orixinal: Ah, podo#moverme. Mola.
     * Limpo: Ah, podo
     * moverme. Mola.
     */
    public String stripFormatting(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        StringBuilder sb = new StringBuilder();

        boolean insideColor = false;

        for (Token t : tokens) {
            if (t.isVisible()) {
                sb.append(t.text());
            } else if (t.isNewline()) {
                // & # \n → salto de liña
                sb.append(t.cleanText());
            } else if (isColorToken(t)) {
                // Marcador de cor: inserir asterisco
                sb.append("*");
                insideColor = !insideColor;
            } else if (isTildeToken(t)) {
                // Marcador ~n: inserir ~ como placeholder relocatable
                sb.append("~");
            } else if (isBackslashOToken(t)) {
                // Marcador \On: inserir @ como placeholder relocatable
                sb.append("@");
            } else if (isBackslashIToken(t)) {
                // Marcador \In: inserir $ como placeholder relocatable
                sb.append("$");
            }
            // PENDING (^n), FORMAT (non-relocatable) e END non producen texto visible
        }

        return sb.toString();
    }

    /**
     * A partir dunha liña sen indicadores de formato e a posición da liña orixinal,
     * reaplica os indicadores de formato que require esa liña.
     *
     * Estratexia:
     * 1. Os marcadores de formato (\E, \M, etc.) insírense nas mesmas posicións
     * relativas
     * 2. Os marcadores de cor (\c, \C) tráctanse especialmente:
     * - O usuario marca o texto coloreado con asteriscos
     * - Os asteriscos reemplázanse polos marcadores de cor orixinais
     * 3. Os marcadores ~n tráctanse como relocatables (placeholder ~)
     * 4. Os marcadores \On tráctanse como relocatables (placeholder @)
     * 5. Os marcadores pending (^n) insírense en límites de palabra
     * 6. Os saltos de liña do usuario (\n) convértense no marcador orixinal (&, #,
     * ou \n)
     * 7. O marcador de fin engádese ao final
     *
     * Exemplo con cores:
     * Orixinal: SUSIE GOT THE \cYPOWER CROISSANT\cW
     * Usuario: SUSIE RECIBIÓ EL *CRUASÁN DE PODER*
     * Resultado: SUSIE RECIBIÓ EL \cYCRUASÁN DE PODER\cW
     */
    public String reapplyFormatting(int lineIndex, String newPlain) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);

        if (newPlain == null)
            newPlain = "";

        // Comprobar se esta liña ten marcadores relocatables
        List<String> colorMarkers = extractColorMarkers(lineIndex);
        List<String> tildeMarkers = extractTildeMarkers(lineIndex);
        List<String> backslashOMarkers = extractBackslashOMarkers(lineIndex);
        List<String> backslashIMarkers = extractBackslashIMarkers(lineIndex);
        boolean hasRelocatable = !colorMarkers.isEmpty() || !tildeMarkers.isEmpty()
                || !backslashOMarkers.isEmpty() || !backslashIMarkers.isEmpty();

        // Se hai marcadores relocatables, procesamento especial
        if (hasRelocatable) {
            return reapplyFormattingWithRelocatable(lineIndex, newPlain, tokens,
                    colorMarkers, tildeMarkers, backslashOMarkers, backslashIMarkers);
        }

        // Se non hai marcadores relocatables, procesamento normal
        return reapplyFormattingNormal(lineIndex, newPlain, tokens);
    }

    /**
     * Procesamento especial para liñas con marcadores relocatables.
     * Placeholders no input do usuario:
     *   * → marcadores de cor (\c, \C)
     *   ~ → marcadores de efecto (~n)
     *   @ → marcadores \On
     *   $ → marcadores \In
     */
    private String reapplyFormattingWithRelocatable(int lineIndex, String newPlain,
            List<Token> tokens, List<String> colorMarkers,
            List<String> tildeMarkers, List<String> backslashOMarkers,
            List<String> backslashIMarkers) {
        // Separar o newPlain en segmentos por liñas
        String[] userLines = newPlain.split("\n", -1);

        StringBuilder out = new StringBuilder();
        int colorIdx = 0;
        int tildeIdx = 0;
        int backslashOIdx = 0;
        int backslashIIdx = 0;

        // Recoller os marcadores de newline orixinais
        List<String> newlineMarkers = new ArrayList<>();
        for (Token t : tokens) {
            if (t.isNewline()) {
                newlineMarkers.add(t.text());
            }
        }

        // Recoller marcadores FORMAT non-relocatable e PENDING non-relocatable (^n)
        List<Token> fixedFormatTokens = new ArrayList<>();
        List<Token> pendingTokens = new ArrayList<>();
        for (Token t : tokens) {
            if (t.type() == TokenType.FORMAT && !isColorToken(t) && !isBackslashOToken(t) && !isBackslashIToken(t)) {
                fixedFormatTokens.add(t);
            } else if (t.type() == TokenType.PENDING && !isTildeToken(t)) {
                pendingTokens.add(t);
            }
        }

        // Procesar cada liña do usuario
        for (int lineNum = 0; lineNum < userLines.length; lineNum++) {
            String userLine = userLines[lineNum];

            // Inserir marcadores FORMAT fixos ao inicio se é a primeira liña
            if (lineNum == 0) {
                for (Token fmt : fixedFormatTokens) {
                    out.append(fmt.text());
                }
            }

            // Procesar a liña carácter por carácter
            List<Token> pendingToInsert = new ArrayList<>(pendingTokens);
            Character lastChar = null;

            for (int i = 0; i < userLine.length(); i++) {
                char ch = userLine.charAt(i);

                if (ch == '*' && colorIdx < colorMarkers.size()) {
                    // Reemplazar asterisco con marcador de cor
                    out.append(colorMarkers.get(colorIdx++));
                } else if (ch == '~' && tildeIdx < tildeMarkers.size()) {
                    // Reemplazar ~ con marcador ~n
                    out.append(tildeMarkers.get(tildeIdx++));
                } else if (ch == '@' && backslashOIdx < backslashOMarkers.size()) {
                    // Reemplazar @ con marcador \On
                    out.append(backslashOMarkers.get(backslashOIdx++));
                } else if (ch == '$' && backslashIIdx < backslashIMarkers.size()) {
                    // Reemplazar $ con marcador \In
                    out.append(backslashIMarkers.get(backslashIIdx++));
                } else {
                    // Comprobar se podemos inserir tokens pending (límite de palabra)
                    boolean chIsLetter = Character.isLetter(ch);
                    boolean lastIsLetter = lastChar != null && Character.isLetter(lastChar);

                    if (!pendingToInsert.isEmpty() && (!lastIsLetter || !chIsLetter)) {
                        for (Token pt : pendingToInsert) {
                            out.append(pt.text());
                        }
                        pendingToInsert.clear();
                    }

                    // Escapar comillas
                    if (ch == '"') {
                        out.append("\\\"");
                    } else {
                        out.append(ch);
                    }
                    lastChar = ch;
                }
            }

            // Flush remaining pending tokens
            for (Token pt : pendingToInsert) {
                out.append(pt.text());
            }

            // Engadir newline se non é a última liña
            if (lineNum < userLines.length - 1 && lineNum < newlineMarkers.size()) {
                out.append(newlineMarkers.get(lineNum));
            }
        }

        // Engadir marcador END se existe
        for (Token t : tokens) {
            if (t.isEnd()) {
                out.append(t.text());
                break;
            }
        }

        return out.toString();
    }

    /**
     * Procesamento normal para liñas sen marcadores de cor.
     */
    private String reapplyFormattingNormal(int lineIndex, String newPlain, List<Token> tokens) {
        // Separar o newPlain en segmentos por liñas
        String[] userLines = newPlain.split("\n", -1);

        // Usar arrays para poder modificar dentro do bucle
        final int[] pos = { 0, 0 }; // pos[0] = currentUserLine, pos[1] = posInLine

        StringBuilder out = new StringBuilder();
        List<Token> pending = new ArrayList<>();

        // Recoller os marcadores de newline orixinais para usalos na saída
        List<String> newlineMarkers = new ArrayList<>();
        for (Token t : tokens) {
            if (t.isNewline()) {
                newlineMarkers.add(t.text());
            }
        }
        final int[] nlIdx = { 0 }; // índice de newlineMarkers

        // Estado para o tracking
        final Character[] lastOutChar = { null };

        // Helper: comprobar se un char é letra
        java.util.function.Predicate<Character> isLetter = ch -> ch != null && Character.isLetter(ch);

        // Helper: flush pending tokens
        Runnable flushPending = () -> {
            for (Token pt : pending) {
                out.append(pt.text());
            }
            pending.clear();
        };

        // Helper: obter o char actual (ou null se non hai)
        java.util.function.Supplier<Character> currentChar = () -> {
            if (pos[0] >= userLines.length)
                return null;
            String line = userLines[pos[0]];
            if (pos[1] >= line.length())
                return null;
            return line.charAt(pos[1]);
        };

        // Helper: consumir e engadir un char
        Runnable consumeChar = () -> {
            if (pos[0] >= userLines.length)
                return;
            String line = userLines[pos[0]];
            if (pos[1] >= line.length())
                return;

            char ch = line.charAt(pos[1]++);
            if (ch == '"') {
                out.append("\\\"");
            } else {
                out.append(ch);
            }
            lastOutChar[0] = ch;
        };

        // Helper: completar a liña actual
        Runnable finishCurrentLine = () -> {
            if (pos[0] >= userLines.length)
                return;
            String line = userLines[pos[0]];
            while (pos[1] < line.length()) {
                char ch = line.charAt(pos[1]++);
                if (ch == '"') {
                    out.append("\\\"");
                } else {
                    out.append(ch);
                }
                lastOutChar[0] = ch;
            }
        };

        // Helper: avanzar á seguinte liña
        Runnable nextLine = () -> {
            pos[0]++;
            pos[1] = 0;
        };

        // Procesar os tokens
        for (Token t : tokens) {
            switch (t.type()) {
                case VISIBLE -> {
                    Character ch = currentChar.get();
                    if (ch != null) {
                        // Comprobar se podemos flush pending (límite de palabra)
                        boolean chIsLetter = isLetter.test(ch);
                        boolean lastIsLetter = isLetter.test(lastOutChar[0]);

                        if (!lastIsLetter || !chIsLetter) {
                            flushPending.run();
                        }

                        consumeChar.run();
                    }
                }

                case FORMAT -> {
                    // Marcadores de formato insírense directamente
                    flushPending.run();
                    out.append(t.text());
                }

                case PENDING -> {
                    // Marcadores pending (^n, ~n) gárdanse para inserir en límites de palabra
                    pending.add(t);
                }

                case NEWLINE -> {
                    // Completar a liña actual do usuario
                    finishCurrentLine.run();

                    // Flush pending antes do newline
                    flushPending.run();

                    // Inserir o marcador de newline orixinal
                    out.append(t.text());
                    lastOutChar[0] = '\n';

                    // Avanzar á seguinte liña do usuario
                    nextLine.run();
                }

                case END -> {
                    // Engadir todo o texto restante do usuario
                    while (pos[0] < userLines.length) {
                        finishCurrentLine.run();
                        nextLine.run();

                        // Se hai máis liñas, engadir un newline marker
                        if (pos[0] < userLines.length) {
                            // Usar o marcador de newline orixinal se queda, ou & por defecto
                            String nlMarker = nlIdx[0] < newlineMarkers.size()
                                    ? newlineMarkers.get(nlIdx[0]++)
                                    : "&";
                            out.append(nlMarker);
                            lastOutChar[0] = '\n';
                        }
                    }

                    flushPending.run();
                    out.append(t.text());
                }
            }
        }

        // Se non había marcador de fin, engadir texto restante
        while (pos[0] < userLines.length) {
            finishCurrentLine.run();
            nextLine.run();

            // Se hai máis liñas, engadir newline
            if (pos[0] < userLines.length) {
                String nlMarker = nlIdx[0] < newlineMarkers.size()
                        ? newlineMarkers.get(nlIdx[0]++)
                        : "&";
                out.append(nlMarker);
                lastOutChar[0] = '\n';
            }
        }

        // Flush pending final
        flushPending.run();

        return out.toString();
    }
}
