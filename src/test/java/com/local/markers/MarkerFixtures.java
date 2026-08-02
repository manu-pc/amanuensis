package com.local.markers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utilidades comúns das probas de marcadores: materializar os JSON de
 * src/test/resources nun directorio temporal (LocHelper traballa con rutas de
 * ficheiro, non con recursos do classpath) e ler a lista de fallos esperados.
 */
final class MarkerFixtures {
    static final String EDGE_CASES = "edge-cases.json";
    static final String CORPUS_SAMPLE = "corpus-sample.json";
    private static final String EXPECTED_FAILURES = "/markers/roundtrip-expected-failures.txt";

    private MarkerFixtures() {
    }

    /** Copia un JSON de /markers/<name> do classpath a dir e devolve a ruta. */
    static Path materialize(String name, Path dir) throws IOException {
        Path target = dir.resolve(name);
        try (InputStream in = MarkerFixtures.class.getResourceAsStream("/markers/" + name)) {
            if (in == null) {
                throw new IllegalStateException("falta o recurso de proba /markers/" + name);
            }
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    /**
     * Le roundtrip-expected-failures.txt. Formato por liña: {@code fixture:clave<TAB>razón}.
     * As liñas baleiras e as que comezan por # ignóranse.
     *
     * @return claves esperadas como fallo (sen o prefixo do fixture) → razón
     */
    static Map<String, String> expectedFailures(String fixture) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = MarkerFixtures.class.getResourceAsStream(EXPECTED_FAILURES)) {
            if (in == null) {
                return out;
            }
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                String left = tab < 0 ? line.trim() : line.substring(0, tab).trim();
                String reason = tab < 0 ? "" : line.substring(tab + 1).trim();
                int colon = left.indexOf(':');
                if (colon < 0) {
                    throw new IllegalStateException("liña sen prefixo de fixture: " + line);
                }
                if (left.substring(0, colon).equals(fixture)) {
                    out.put(left.substring(colon + 1), reason);
                }
            }
        }
        return out;
    }

    /** Diferenza de conxuntos a → b, preservando a orde. */
    static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
