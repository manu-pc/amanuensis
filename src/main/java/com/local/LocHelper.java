package com;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // gson non garante explícitamente LinkedHashMap aquí, así que reconstruímos respectando a orde de entrySet()
        // e gardámolo en listas paralelas.
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

    // -------------------------
    // token e parser (copiados e adaptados)
    // -------------------------
    private static class Token {
        final boolean isVisible; // true: representa un carácter visible (1 char) ou newline; false: token de formato
        final String text; // para visible: un único char (ou "\n"), para formato: o token (ex: "\E1", "^1", "&", "/%")

        Token(boolean isVisible, String text) {
            this.isVisible = isVisible;
            this.text = text;
        }

        @Override
        public String toString() {
            return (isVisible ? "V(" + text + ")" : "F(" + text + ")");
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();

        // extraer marcador final /% ou / se existe
        String endMarker = null;
        if (s.endsWith("/%")) {
            endMarker = "/%";
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("/")) {
            endMarker = "/";
            s = s.substring(0, s.length() - 1);
        }

        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);

            if (c == '\\') {
                int j = i + 1;
                if (j < n && s.charAt(j) == '"') {
                    out.add(new Token(true, "\"")); // visible char
                    i = j + 1;
                    continue;
                }
                while (j < n && s.charAt(j) != '\\' && !Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                String tok = s.substring(i, j);
                out.add(new Token(false, tok));
                i = j;
            } else if (c == '^') {
                int j = i + 1;
                while (j < n && Character.isDigit(s.charAt(j))) j++;
                String tok = s.substring(i, j); // ex: ^1
                out.add(new Token(false, tok));
                i = j;
            } else if (c == '&') {
                out.add(new Token(false, "&"));
                i++;
            } else {
                out.add(new Token(true, String.valueOf(c)));
                i++;
            }
        }

        if (endMarker != null) {
            out.add(new Token(false, endMarker));
        }

        return out;
    }

    // -------------------------
    // métodos de instancia que usan as liñas cargadas
    // -------------------------

    // devolve o texto plano sen marcadores da liña index (0-based)
    public String stripFormatting(int lineIndex) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            if (t.isVisible) {
                sb.append(t.text);
            } else {
                if (t.text.startsWith("^")) {
                    sb.append('\n');
                } else if (t.text.equals("\"") || t.text.equals("\\\"")) {
                    sb.append('"');
                } else {
                    // ignorar outros marcadores ao crear o texto plano
                }
            }
        }
        return sb.toString();
    }

    // reaplica os formatos da liña orixinal sobre newPlain
    public String reapplyFormatting(int lineIndex, String newPlain) {
        String original = originals.get(lineIndex);
        List<Token> tokens = tokenize(original);

        char[] plainChars = newPlain.toCharArray();
        int p = 0, plen = plainChars.length;

        StringBuilder out = new StringBuilder();

        for (Token t : tokens) {
            if (!t.isVisible) {
                out.append(t.text);
            } else {
                char ch;
                if (p < plen) {
                    ch = plainChars[p++];
                } else {
                    ch = t.text.charAt(0);
                }

                if (ch == '"') {
                    out.append("\\\"");
                } else {
                    out.append(ch);
                }
            }
        }

        if (p < plen) {
            out.append(new String(plainChars, p, plen - p));
        }

        return out.toString();
    }

    public void updateLineFormatted(int lineIndex, String formattedValue) {
        originals.set(lineIndex, formattedValue);
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            m.put(keys.get(i), originals.get(i));
        }
        return m;
    }
}
