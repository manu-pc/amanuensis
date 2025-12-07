package com.local;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
//? clase encargada do manexo de simbolos marcadores de texto. fai:
    // texto orixinal --> texto sin marcadores  (stripFormatting)
    // texto sin marcadores modificado --> texto con marcadores modificado (reapplyFormatting)
    // permitindo así modificar/traducir texto de forma cómoda


//! detecta os indicadores de formato usados para strings en Undertale e Deltarune.
    // ver comentario en stripFormatting para ver o formato exacto
    // modificar os 2 metodos mencionados para adaptalo a un texto con distinto formato


@SuppressWarnings("StatementWithEmptyBody")
public class LocHelper {
    private final String filename;
    private final List<String> keys = new ArrayList<>();       // claves na orde do ficheiro
    private final List<String> originals = new ArrayList<>();  // valores orixinais na orde do ficheiro

    public LocHelper(String filename) throws Exception {
        this.filename = filename;
        readAndParseFile();
    }

    private void readAndParseFile() throws Exception {
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
     * @param isVisible        true: representa un carácter visible (1 char) ; false: token de formato
     * @param text             para visible: un único char (ou "\""); para formato: o token (ex: "\E1", "^1", "&", "%%")
     * @param placeholderCount cantidade de caracteres que este token produciu no texto limpo (por exemplo '&' -> 1 espazo)
     */ // token e parser
        private record Token(boolean isVisible, String text, int placeholderCount) {

        Token(boolean isVisible, String text) {
                this(isVisible, text, 0);
            }

            @Override
            public String toString() {
                return (isVisible ? "V(" + text + ")" : "F(" + text + (placeholderCount > 0 ? ",ph=" + placeholderCount : "") + ")");
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
        while (i < n) {
            char c = s.charAt(i);

            if (c == '\\') {
                int j = i + 1;
                // escaped quote \" -> visible quote
                if (j < n && s.charAt(j) == '"') {
                    out.add(new Token(true, "\""));
                    i = j + 1;
                    continue;
                }

                // detectar \ + letters (+ digits) patrón (ex: \E8, \M0, \cRHEART)
                int startLetters = j;
                while (startLetters < n && Character.isLetter(s.charAt(startLetters))) startLetters++;

                if (startLetters == j) {
                    int k = j;
                    while (k < n && s.charAt(k) != '\\' && !Character.isWhitespace(s.charAt(k))) k++;
                    String tok = s.substring(i, k);
                    out.add(new Token(false, tok, 0));
                    i = k;
                    continue;
                }

                int k = startLetters;
                while (k < n && Character.isDigit(s.charAt(k))) k++;

                String tok = s.substring(i, k);
                if (k < n && Character.isLetter(s.charAt(k))) {
                    int kk = k;
                    while (kk < n && s.charAt(kk) != '\\' && !Character.isWhitespace(s.charAt(kk))) kk++;
                    tok = s.substring(i, kk);
                    out.add(new Token(false, tok, 0));
                    i = kk;
                    continue;
                }

                // caso especial: \E8* → token \E8 e '*' como visible
                if (k < n && s.charAt(k) == '*') {
                    out.add(new Token(false, tok, 0));
                    out.add(new Token(true, "*", 0));
                    i = k + 1;
                    continue;
                }

                out.add(new Token(false, tok, 0));
                i = k;
            } else if (c == '^') {
                int j = i + 1;
                while (j < n && Character.isDigit(s.charAt(j))) j++;
                String tok = s.substring(i, j); // ex: ^1
                out.add(new Token(false, tok, 0));
                i = j;
            } else if (c == '&') {
                out.add(new Token(false, "&", 1));
                i++;
            } else {
                out.add(new Token(true, String.valueOf(c), 0));
                i++;
            }
        }

        if (endMarker != null && !endMarker.isEmpty()) {
            out.add(new Token(false, endMarker, 0));
        }

        return out;
    }

    private static String rtrim(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return s.substring(0, i + 1);
    }

    // -------------------------
    // métodos de instancia que usan as liñas cargadas
    // -------------------------

    // devolve o texto plano sen marcadores da liña index (0-based)

    // regras aplicadas:
    /* - & -> ' ' (espazo)
     - ^n non produce newline no texto limpo (é ignorado)
    - tokens \M0, \M1, \E1, \c... non aparecen
     - marcadores finais de % non aparecen no texto limpo */

    // exemplo:
    //? \E0* Sure..^1. okay^1, we can try again./%
    //! Sure... Okay, we can try again.

    public String stripFormatting(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            if (t.isVisible) {
                sb.append(t.text);
            } else {
                // tokens de formato que xeran placeholders no texto limpo
                if ("&".equals(t.text)) {
                    // & -> espazo placeholder
                    sb.append(' ');
                } else {
                    // completa como quiras!
                }
            }
        }
        return sb.toString();
    }

    // a partir de unha liña sen indicadores de formato, e a posición da liña orixinal,
    // reaplica os indicadores de formato que require esa liña.

    // exemplo:
    //? Liña orixinal: \E0* Sure..^1. okay^1, we can try again./%
    //* Entrada usuario: Si... Vale, podémolo tentar outra vez.
    //! Salida función: \E0* Si... ^1Vale, ^1podémolo tentar outra vez./%

    public String reapplyFormatting(int lineIndex, String newPlain) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);

        char[] plainChars = newPlain == null ? new char[0] : newPlain.toCharArray();
        int p = 0, plen = plainChars.length;

        StringBuilder out = new StringBuilder();

        // lista para gardar ^n e outros placeholders (ex: '&') que non queremos inserir no medio dunha palabra
        List<String> pendingPlaceholders = new ArrayList<>();

        // último carácter visible que puxemos ao out (ou null se non hai)
        var lambdaContext = new Object() {
            Character lastOutChar = null;
        };

        // util: comprobar se un char é letra (inclúe acentos)
        java.util.function.Predicate<Character> isLetter = ch -> ch != null && Character.isLetter(ch);

        // vaciar pendingPlaceholders ao out
        Runnable flushPending = () -> {
            for (String c : pendingPlaceholders) {
                out.append(c);
                if (!c.isEmpty()) lambdaContext.lastOutChar = c.charAt(c.length() - 1);
            }
            pendingPlaceholders.clear();
        };

        for (Token t : tokens) {
            if (!t.isVisible) {
                // caret (^n): gardar como pending en vez de inserilo inmediatamente
                if (t.text.startsWith("^")) {
                    pendingPlaceholders.add(t.text);
                    continue;
                }

                // placeholder tokens (ex: '&') — consumen caracteres do newPlain pero non se inseren agora,
                // engádense a pendingPlaceholders para insertalos nun límite de palabra posterior.
                if (t.placeholderCount > 0) {
                    for (int k = 0; k < t.placeholderCount; k++) {
                        if (p < plen) p++; // consumir placeholder chars do newPlain (esperando espazos normalmente)
                    }
                    // non flush aquí: gardámolo como pending para evitar cortar palabras
                    pendingPlaceholders.add(t.text);
                    continue;
                }

                // se é marcador final tipo "/" ou "/%" ou composto de '%' (ex: "%%")
                boolean isEndMarker = t.text.equals("/") || t.text.equals("/%") || t.text.matches("%+");
                if (isEndMarker) {
                    // primeiro engadir calquera carácter sobrante en newPlain
                    if (p < plen) {
                        out.append(new String(plainChars, p, plen - p));
                        p = plen;
                    }
                    // logo insertar os placeholders pendentes (se hai)
                    flushPending.run();
                    // e finalmente o marcador final
                    out.append(t.text);
                    continue;
                }

                // outros tokens de formato (p.ex \M0, \E1, etc.)
                // antes de inserir, flush pending (non queremos deixalos por detrás doutros tokens)
                flushPending.run();
                out.append(t.text);
                if (!t.text.isEmpty()) lambdaContext.lastOutChar = t.text.charAt(t.text.length() - 1);
            } else {
                // token visible: substituír pola próxima letra dispoñible en newPlain
                if (p < plen) {
                    char ch = plainChars[p++];

                    // decidir se inserir pendingPlaceholders antes deste carácter
                    boolean chIsLetter = isLetter.test(ch);
                    boolean lastIsLetter = isLetter.test(lambdaContext.lastOutChar);

                    if (!lastIsLetter || !chIsLetter) {
                        // hai unha barreira: podemos inserir os placeholders pendentes agora
                        flushPending.run();
                    }
                    // inserir o carácter (escapando " se é preciso)
                    if (ch == '"') {
                        out.append("\\\"");
                        lambdaContext.lastOutChar = '"';
                    } else {
                        out.append(ch);
                        lambdaContext.lastOutChar = ch;
                    }
                } else {
                    // newPlain esgotado: non inserimos o carácter visible orixinal (bórrase)
                    // non actualizamos lastOutChar nin consumimos nada.
                    // de novo, esto podes modificalo para o teu formato
                }
            }
        }

        // tras procesar todos os tokens:
        // 1) engadir calquera carácter sobrante de newPlain
        if (p < plen) {
            out.append(new String(plainChars, p, plen - p));
        }

        // 2) inserir remaining placeholders se os houbo (ao final)
        if (!pendingPlaceholders.isEmpty()) {
            for (String c : pendingPlaceholders) out.append(c);
            pendingPlaceholders.clear();
        }

        return out.toString();
    }


}
