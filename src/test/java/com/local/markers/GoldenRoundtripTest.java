package com.local.markers;


import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.local.LocHelper;

/**
 * Proba de ida e volta ("golden roundtrip"): para cada liña dun corpus,
 * {@code reapplyFormatting(i, stripFormatting(i))} debe devolver o orixinal
 * exacto. É a rede de seguridade do sistema de marcadores: unha edición nula
 * do usuario non pode alterar nin un byte.
 *
 * As liñas que hoxe non cumpren a invariante están listadas en
 * {@code src/test/resources/markers/roundtrip-expected-failures.txt} cunha
 * razón. A proba compara CONXUNTOS: se un arranxo fai pasar unha liña da lista,
 * a proba falla ata que se borre a entrada (a lista só pode encollerse), e se
 * unha liña nova rompe, tamén falla.
 */
class GoldenRoundtripTest {

    @Test
    void edgeCasesRoundtrip(@TempDir Path dir) throws IOException {
        assertRoundtrip(MarkerFixtures.EDGE_CASES, dir);
    }

    @Test
    void corpusSampleRoundtrip(@TempDir Path dir) throws IOException {
        assertRoundtrip(MarkerFixtures.CORPUS_SAMPLE, dir);
    }

    /**
     * Barrido opcional sobre un strings.json real completo:
     * {@code ./mvnw -Damanuensis.corpus=$PWD/eng-4.json test}.
     * Sen a propiedade a proba sáltase.
     */
    @Test
    void externalCorpusRoundtrip() throws IOException {
        String path = System.getProperty("amanuensis.corpus");
        Assumptions.assumeTrue(path != null && !path.isBlank() && !path.startsWith("${"),
                "amanuensis.corpus non definido: sáltase o barrido do corpus completo");
        Assumptions.assumeTrue(Files.exists(Path.of(path)), "corpus inexistente: " + path);

        LocHelper helper = new LocHelper(path);
        Map<String, String[]> failures = collectFailures(helper);
        int total = helper.getLineCount();
        String report = report(failures, 15);
        System.out.printf("corpus %s: %d/%d fallos (%.2f%%)%n%s%n",
                path, failures.size(), total, 100.0 * failures.size() / total, report);

        // eng-4.json completo (15771 liñas) dá 5 fallos inherentes ao deseño: 3 pausas
        // no medio de palabra (efecto letra a letra) e 2 asteriscos literais que
        // colisionan co placeholder de cor. Ver roundtrip-expected-failures.txt.
        int max = Integer.getInteger("amanuensis.corpus.maxFailures", 5);
        assertTrue(failures.size() <= max,
                () -> "fallos de ida e volta no corpus completo: " + failures.size()
                        + " (máximo permitido " + max + ")\n" + report);
    }

    private void assertRoundtrip(String fixture, Path dir) throws IOException {
        LocHelper helper = new LocHelper(MarkerFixtures.materialize(fixture, dir).toString());
        Map<String, String[]> failures = collectFailures(helper);
        dumpActual(fixture, failures);

        Set<String> actual = new LinkedHashSet<>(failures.keySet());
        Set<String> expected = new LinkedHashSet<>(MarkerFixtures.expectedFailures(fixture).keySet());

        Set<String> unexpected = MarkerFixtures.minus(actual, expected);
        Set<String> fixed = MarkerFixtures.minus(expected, actual);

        if (unexpected.isEmpty() && fixed.isEmpty()) {
            return;
        }
        Map<String, String[]> regressions = new LinkedHashMap<>();
        for (String key : unexpected) {
            regressions.put(key, failures.get(key));
        }
        throw new AssertionError("ida e volta en " + fixture + "\n"
                + "REGRESIÓNS (" + unexpected.size() + " fallan e non estaban na lista): "
                + head(unexpected) + "\n" + report(regressions, 10) + "\n"
                + "XA ARRANXADAS (" + fixed.size()
                + ", bórraas de roundtrip-expected-failures.txt, ou copia\n"
                + "  target/roundtrip-actual-" + fixture + ".txt): " + head(fixed));
    }

    /** Primeiras claves dun conxunto, para que a mensaxe de erro non se dispare. */
    private static String head(Set<String> keys) {
        List<String> shown = keys.stream().limit(12).toList();
        return shown + (keys.size() > shown.size() ? " ... (+" + (keys.size() - shown.size()) + ")" : "");
    }

    /** clave → {orixinal, limpo, reaplicado} das liñas que non sobreviven á ida e volta. */
    private Map<String, String[]> collectFailures(LocHelper helper) {
        Map<String, String[]> failures = new LinkedHashMap<>();
        for (int i = 0; i < helper.getLineCount(); i++) {
            String original = helper.getOriginal(i);
            String clean = helper.stripFormatting(i);
            String back = helper.reapplyFormatting(i, clean);
            if (!original.equals(back)) {
                failures.put(helper.getKey(i), new String[] { original, clean, back });
            }
        }
        return failures;
    }

    private String report(Map<String, String[]> failures, int limit) {
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, String[]> e : failures.entrySet()) {
            if (shown++ >= limit) {
                lines.add("  ... e " + (failures.size() - limit) + " máis");
                break;
            }
            lines.add("  " + e.getKey()
                    + "\n    orixinal: " + show(e.getValue()[0])
                    + "\n    limpo   : " + show(e.getValue()[1])
                    + "\n    volta   : " + show(e.getValue()[2]));
        }
        return String.join("\n", lines);
    }

    /**
     * Escribe os fallos reais en target/roundtrip-actual-&lt;fixture&gt;.txt co mesmo
     * formato que roundtrip-expected-failures.txt, para poder actualizar a lista
     * despois dun arranxo sen copiar claves á man.
     */
    private void dumpActual(String fixture, Map<String, String[]> failures) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : failures.entrySet()) {
            sb.append(fixture).append(':').append(e.getKey()).append('\t')
                    .append(classify(e.getValue()[0], e.getValue()[2])).append('\n');
        }
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("roundtrip-actual-" + fixture + ".txt"), sb.toString());
    }

    /** Etiqueta curta da causa dun fallo de ida e volta, para a lista de fallos esperados. */
    private String classify(String original, String back) {
        if (back.contains("\\\"") && !original.contains("\\\"")) {
            return "escape duplicado de comiñas";
        }
        if (back.contains("* * ") && !original.contains("* * ")) {
            return "prefixos '* ' agrupados ao inicio";
        }
        if (back.contains("()") && !original.contains("()")) {
            return "parénteses agrupados ao inicio";
        }
        boolean onlyCaretOne = back.replace("^1", "").equals(original.replace("^1", ""));
        if (onlyCaretOne) {
            return "^1 rederivado a partir da puntuación";
        }
        if (back.replaceAll("\\s", "").equals(original.replaceAll("\\s", ""))) {
            return "espazo antes do marcador final";
        }
        if (sorted(back).equals(sorted(original))) {
            return "marcadores reordenados";
        }
        return "outro";
    }

    private static String sorted(String s) {
        char[] c = s.toCharArray();
        java.util.Arrays.sort(c);
        return new String(c);
    }

    private String show(String s) {
        return "«" + s.replace("\n", "\\n") + "»";
    }
}
