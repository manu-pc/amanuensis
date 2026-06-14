package com.local;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class HunspellChecker {
    private final String dictPath;
    private final boolean available;
    // dicionario persoal do usuario (palabras engadidas con "engadir ao dicionario").
    // Garda baixo lang/ se existe, para que viaxe co proxecto de tradución.
    private final Path personalDict;

    public HunspellChecker() {
        String found = findDictionary();
        this.dictPath = found;
        this.available = found != null && checkHunspellBinary();
        this.personalDict = Files.isDirectory(Path.of("lang"))
                ? Path.of("lang", "amanuensis-personal.dic")
                : Path.of("amanuensis-personal.dic");
    }

    public boolean isAvailable() {
        return available;
    }

    /** Ruta do dicionario persoal (palabras aceptadas polo usuario). */
    public Path getPersonalDictPath() {
        return personalDict;
    }

    /**
     * Engade unha palabra ao dicionario persoal para que deixe de marcarse como
     * incorrecta (tamén en futuras sesións). Devolve true se quedou rexistrada.
     */
    public boolean addToDictionary(String word) {
        if (word == null || word.isBlank()) return false;
        String w = word.trim();
        try {
            // evitar duplicados se o ficheiro xa existe
            if (Files.exists(personalDict)) {
                for (String line : Files.readAllLines(personalDict)) {
                    if (line.trim().equals(w)) return true;
                }
            }
            Files.writeString(personalDict, w + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Constrúe o comando hunspell engadindo o dicionario persoal con -p se existe.
    private List<String> buildCommand(String modeFlag) {
        List<String> cmd = new ArrayList<>();
        cmd.add(hunspellCmd());
        cmd.add("-d");
        cmd.add(dictPath);
        if (Files.exists(personalDict)) {
            cmd.add("-p");
            cmd.add(personalDict.toString());
        }
        cmd.add(modeFlag);
        return cmd;
    }

    /**
     * Rexión dunha palabra mal escrita dentro do texto orixinal.
     * start/end son offsets de caracteres (end exclusivo) que mapean
     * 1:1 sobre o texto do editor, grazas a que a limpeza conserva a lonxitude.
     */
    public record Region(int start, int end, String word) {}

    /**
     * Substitúe os placeholders de amanuensis (*~@$) e os saltos de liña por
     * espazos SEN cambiar a lonxitude da cadea. Así os offsets do resultado
     * coinciden carácter a carácter cos do texto orixinal do editor.
     */
    private static String cleanPreservingLength(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '~' || c == '@' || c == '$' || c == '\n') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Devolve as rexións (offsets) de todas as palabras mal escritas no texto.
     * Pensado para subliñado en liña (squiggles) no editor: cada ocorrencia
     * dunha palabra incorrecta xera unha rexión, conservando as posicións.
     * Os placeholders (*~@$) convértense en espazos e nunca se subliñan.
     */
    public List<Region> getMisspelledRegions(String text) {
        if (!available || text == null || text.isBlank()) return Collections.emptyList();

        String cleaned = cleanPreservingLength(text);
        // hunspell -l pode devolver as palabras con puntuación pegada (ex: "erroor.").
        // Normalizamos cada palabra ao seu núcleo de letras para que coincida coa
        // tokenización por offsets de abaixo.
        Set<String> bad = new HashSet<>();
        for (String w : getMisspelled(cleaned)) {
            String core = stripToWord(w);
            if (!core.isEmpty()) bad.add(core);
        }
        if (bad.isEmpty()) return Collections.emptyList();

        List<Region> regions = new ArrayList<>();
        int n = cleaned.length();
        int i = 0;
        while (i < n) {
            if (isWordChar(cleaned.charAt(i))) {
                int j = i + 1;
                while (j < n && isWordChar(cleaned.charAt(j))) j++;
                String w = cleaned.substring(i, j);
                if (bad.contains(w)) regions.add(new Region(i, j, w));
                i = j;
            } else {
                i++;
            }
        }
        return regions;
    }

    // Carácter que forma parte dunha palabra (letras + apóstrofo interior).
    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'' || c == '’';
    }

    // Elimina os caracteres que non forman parte da palabra dos dous extremos
    // (ex: "erroor." -> "erroor", "«casa»" -> "casa").
    private static String stripToWord(String s) {
        int a = 0, b = s.length();
        while (a < b && !isWordChar(s.charAt(a))) a++;
        while (b > a && !isWordChar(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    // Returns the list of misspelled words found in the given text.
    // Strips amanuensis placeholder chars before checking.
    public List<String> getMisspelled(String text) {
        if (!available || text == null || text.isBlank()) return Collections.emptyList();

        // Remove amanuensis placeholders and collapse newlines to spaces
        String cleaned = text
                .replaceAll("[*~@$]", " ")
                .replace('\n', ' ')
                .trim();

        if (cleaned.isBlank()) return Collections.emptyList();

        try {
            ProcessBuilder pb = new ProcessBuilder(buildCommand("-l"));
            pb.redirectErrorStream(false);
            Process p = pb.start();

            // Write text and close stdin so hunspell knows input is done
            try (OutputStream os = p.getOutputStream()) {
                os.write(cleaned.getBytes("UTF-8"));
            }

            List<String> misspelled = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) misspelled.add(line);
                }
            }

            p.waitFor(5, TimeUnit.SECONDS);
            return misspelled;

        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // Returns up to 6 spelling suggestions for a single word.
    public List<String> getSuggestions(String word) {
        if (!available || word == null || word.isBlank()) return Collections.emptyList();

        try {
            ProcessBuilder pb = new ProcessBuilder(buildCommand("-a"));
            pb.redirectErrorStream(false);
            Process p = pb.start();

            try (OutputStream os = p.getOutputStream()) {
                os.write((word + "\n").getBytes("UTF-8"));
            }

            List<String> suggestions = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                boolean headerSkipped = false;
                String line;
                while ((line = br.readLine()) != null) {
                    if (!headerSkipped) {
                        headerSkipped = true;
                        continue; // first line is always the hunspell version header
                    }
                    if (line.startsWith("& ")) {
                        // format: "& word count offset: sugg1, sugg2, ..."
                        int colon = line.indexOf(':');
                        if (colon >= 0 && colon + 2 < line.length()) {
                            for (String s : line.substring(colon + 2).split(", ")) {
                                String t = s.trim();
                                if (!t.isEmpty()) suggestions.add(t);
                            }
                        }
                        break;
                    } else if (line.startsWith("*") || line.startsWith("#")) {
                        break;
                    }
                }
            }

            p.destroy();
            return suggestions.size() <= 6 ? suggestions : suggestions.subList(0, 6);

        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String hunspellCmd() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "hunspell.exe"
                : "hunspell";
    }

    private String findDictionary() {
        // 1. Project-local built galician dictionary (hunspellgal sources → scons)
        if (Files.exists(Path.of("hunspellgal/build/gl.aff"))) {
            return "hunspellgal/build/gl";
        }

        // 2. System dictionaries (Linux / macOS)
        String[] candidates = {
            "/usr/share/hunspell/gl_ES",
            "/usr/share/hunspell/gl",
            "/usr/local/share/hunspell/gl_ES",
            "/usr/local/share/hunspell/gl",
            "/usr/share/myspell/dicts/gl_ES",
        };
        for (String c : candidates) {
            if (Files.exists(Path.of(c + ".aff"))) return c;
        }

        // 3. Windows common paths
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String[] winCandidates = {
                "C:/Program Files/hunspell/gl",
                "C:/Program Files (x86)/hunspell/gl",
                "C:/hunspell/gl",
            };
            for (String c : winCandidates) {
                if (Files.exists(Path.of(c + ".aff"))) return c;
            }
        }

        return null;
    }

    private boolean checkHunspellBinary() {
        try {
            ProcessBuilder pb = new ProcessBuilder(hunspellCmd(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor(3, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
