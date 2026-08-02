package com.local.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonObject;
import com.local.JsonIo;

/**
 * Propagación masiva: leva a tradución de cada mensaxe repetida ás demais
 * aparicións, capítulo a capítulo, dunha soa pasada.
 *
 * É a versión «todo de golpe» do que fai o editor liña a liña ao gardar. Úsase
 * despois de traducir un capítulo enteiro, ou a primeira vez que se xera o
 * {@code message-map.json} sobre unha tradución que xa estaba avanzada.
 *
 * <h2>De onde sae o texto bo</h2>
 * Por cada grupo colléndose a aparición <b>máis antiga que xa estea traducida</b>,
 * é dicir a primeira, en orde de capítulo, cuxo valor xa non é o inglés orixinal
 * que garda o grupo. Se ningunha está traducida, o grupo sáltase.
 *
 * Isto importa: coller literalmente a de capítulo 1 revertería ao inglés todo o
 * que estea traducido en capítulo 2 pero non en capítulo 1, que é precisamente o
 * estado no que está unha tradución a medias. Con {@code --forzar-primeira}
 * cóllese a primeira aparición pase o que pase.
 *
 * <h2>Seguridade</h2>
 * Por defecto <b>non escribe nada</b>: ensina o que faría. Só con {@code --aplicar}
 * toca os ficheiros, e sempre a través do {@link com.local.EditLedger} de cada un,
 * así que o resultado vai no seguinte push coma calquera outra edición.
 */
public final class PropagateChapters {

    /** Un cambio que se faría (ou se fixo). */
    public record Change(String relPath, String key, String from, String to, String sourceRelPath) {
    }

    /** Resultado dunha pasada completa. */
    public record Report(List<Change> changes, int groupsConsidered, int groupsWithSource,
            int overwritten, List<String> missing, List<String> errors) {
    }

    /**
     * Que se propaga e onde.
     *
     * @param apply      false = só simulación
     * @param forceFirst usar sempre a primeira aparición aínda que estea sen traducir
     * @param only       se non está baleiro, só se escribe nestes ficheiros (rutas
     *                   relativas ao repo). É o que permite encher un capítulo novo
     *                   sen tocar nin unha liña dos xa revisados
     * @param useLedger  false = escribir sen anotar no rexistro de edicións. Só para
     *                   traballo de mantemento que se vai commitear a man; a app
     *                   nunca debe usalo, porque entón o cambio non se subiría
     */
    public record Options(boolean apply, boolean forceFirst, Set<String> only, boolean useLedger) {

        public Options {
            only = only == null ? Set.of() : Set.copyOf(only);
        }

        public static Options dryRun() {
            return new Options(false, false, Set.of(), true);
        }

        boolean targets(String relPath) {
            return only.isEmpty() || only.contains(relPath);
        }
    }

    private PropagateChapters() {
    }

    /**
     * Calcula (e, se {@code apply}, aplica) a propagación de todas as mensaxes
     * repetidas do repositorio.
     */
    public static Report run(Path repoRoot, MessageMap map, boolean apply, boolean forceFirst)
            throws IOException {
        return run(repoRoot, map, new Options(apply, forceFirst, Set.of(), true));
    }

    /** Como {@link #run(Path, MessageMap, boolean, boolean)} pero con todas as opcións. */
    public static Report run(Path repoRoot, MessageMap map, Options options) throws IOException {
        boolean apply = options.apply();
        boolean forceFirst = options.forceFirst();
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        for (String rel : map.files()) {
            Path f = repoRoot.resolve(rel);
            if (Files.isRegularFile(f)) {
                files.put(rel, valuesOf(f));
            }
        }

        List<Change> changes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int withSource = 0;
        int overwritten = 0;

        // ficheiro -> (clave -> valor novo): agrúpase para escribir cada JSON unha vez
        Map<String, Map<String, String>> pending = new LinkedHashMap<>();

        for (MessageMap.Group group : map.groups()) {
            MessageMap.Member source = null;
            String sourceValue = null;
            for (MessageMap.Member m : group.members()) {
                Map<String, String> values = files.get(m.relPath());
                if (values == null) {
                    continue;
                }
                String v = values.get(m.key());
                if (v == null) {
                    missing.add(m.relPath() + " / " + m.key());
                    continue;
                }
                if (forceFirst || !Objects.equals(v, group.base())) {
                    source = m;
                    sourceValue = v;
                    break;
                }
            }
            if (source == null) {
                continue; // ninguén traduciu aínda esta mensaxe
            }
            withSource++;

            for (MessageMap.Member m : group.members()) {
                if (m.equals(source) || !options.targets(m.relPath())) {
                    continue;
                }
                Map<String, String> values = files.get(m.relPath());
                if (values == null) {
                    continue;
                }
                String current = values.get(m.key());
                if (current == null || Objects.equals(current, sourceValue)) {
                    continue;
                }
                if (!Objects.equals(current, group.base())) {
                    overwritten++;
                }
                changes.add(new Change(m.relPath(), m.key(), current, sourceValue, source.relPath()));
                pending.computeIfAbsent(m.relPath(), r -> new LinkedHashMap<>())
                        .put(m.key(), sourceValue);
            }
        }

        if (apply) {
            errors.addAll(applyChanges(repoRoot, map, pending, options.useLedger()));
        }

        return new Report(List.copyOf(changes), map.groups().size(), withSource, overwritten,
                List.copyOf(missing), List.copyOf(errors));
    }

    /** Escribe os cambios reutilizando o propagador (mesma disciplina de rexistro). */
    private static List<String> applyChanges(Path repoRoot, MessageMap map,
            Map<String, Map<String, String>> pending, boolean useLedger) {
        List<String> errors = new ArrayList<>();
        MessagePropagator.Ledgers ledgers = useLedger
                ? MessagePropagator.defaultLedgers(repoRoot)
                : MessagePropagator.noLedgers();
        // agrúpanse por valor novo para poder reutilizar MessagePropagator.write,
        // que escribe un mesmo texto nun conxunto de destinos
        for (Map.Entry<String, Map<String, String>> file : pending.entrySet()) {
            Map<String, List<MessageMap.Member>> byValue = new LinkedHashMap<>();
            Map<String, String> baseOf = new HashMap<>();
            int fileIndex = map.files().indexOf(file.getKey());
            for (Map.Entry<String, String> e : file.getValue().entrySet()) {
                MessageMap.Group g = map.find(file.getKey(), e.getKey());
                byValue.computeIfAbsent(e.getValue(), v -> new ArrayList<>())
                        .add(new MessageMap.Member(file.getKey(), e.getKey(), fileIndex));
                if (g != null) {
                    baseOf.put(e.getValue(), g.base());
                }
            }
            for (Map.Entry<String, List<MessageMap.Member>> e : byValue.entrySet()) {
                MessagePropagator.Result r = MessagePropagator.write(repoRoot, e.getValue(),
                        baseOf.get(e.getKey()), e.getKey(), ledgers);
                errors.addAll(r.errors());
            }
        }
        return errors;
    }

    private static Map<String, String> valuesOf(Path file) throws IOException {
        JsonObject obj = JsonIo.read(file);
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : obj.keySet()) {
            String v = JsonIo.stringOrNull(obj, key);
            if (v != null) {
                out.put(key, v);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // main
    // ---------------------------------------------------------------

    /**
     * {@code PropagateChapters <raíz> [--aplicar] [--forzar-primeira]
     * [--so <ruta>]… [--sen-rexistro]}
     */
    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(".").toAbsolutePath().normalize();
        boolean apply = false;
        boolean forceFirst = false;
        boolean useLedger = true;
        Set<String> only = new LinkedHashSet<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--aplicar", "--apply" -> apply = true;
                case "--forzar-primeira", "--force-first" -> forceFirst = true;
                case "--sen-rexistro", "--no-ledger" -> useLedger = false;
                case "--so", "--only" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--so precisa unha ruta (ex: lang/chapter5/strings.json)");
                        System.exit(2);
                    }
                    only.add(MessageMap.normalize(args[++i]));
                }
                default -> repoRoot = Path.of(args[i]).toAbsolutePath().normalize();
            }
        }
        Options options = new Options(apply, forceFirst, only, useLedger);

        MessageMap map = MessageMap.load(repoRoot);
        if (map.isEmpty()) {
            System.err.println("non hai " + MessageMap.fileIn(repoRoot)
                    + " (ou está baleiro): xera primeiro o mapa con build-message-map.sh");
            System.exit(1);
        }

        Report report = run(repoRoot, map, options);

        Map<String, Integer> perFile = new LinkedHashMap<>();
        for (Change c : report.changes()) {
            perFile.merge(c.relPath(), 1, Integer::sum);
        }

        System.out.println(apply ? "APLICANDO cambios" : "simulación (engade --aplicar para escribir)");
        if (!only.isEmpty()) {
            System.out.println("só se escribe en: " + String.join(", ", only));
        }
        if (!useLedger) {
            System.out.println("sen rexistro de edicións (os cambios hai que commitealos a man)");
        }
        System.out.printf("grupos no mapa           %d%n", report.groupsConsidered());
        System.out.printf("grupos cunha tradución   %d%n", report.groupsWithSource());
        System.out.printf("liñas a actualizar       %d%n", report.changes().size());
        System.out.printf("  delas con tradución propia distinta (píranse)  %d%n", report.overwritten());
        System.out.println("por ficheiro:");
        perFile.forEach((f, n) -> System.out.printf("  %-34s %6d%n", f, n));
        if (!report.missing().isEmpty()) {
            System.out.printf("claves do mapa que xa non existen: %d (rexenera o mapa)%n",
                    report.missing().size());
        }
        if (!report.errors().isEmpty()) {
            System.out.println("erros:");
            report.errors().forEach(e -> System.out.println("  " + e));
        }
        if (!apply) {
            System.out.println();
            report.changes().stream().limit(20).forEach(c -> System.out.printf(
                    "  %s / %s%n    de:  %s%n    a:   %s   (fonte: %s)%n",
                    c.relPath(), c.key(), shorten(c.from()), shorten(c.to()), c.sourceRelPath()));
            if (report.changes().size() > 20) {
                System.out.printf("  … e %d máis%n", report.changes().size() - 20);
            }
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "(nada)";
        }
        return s.length() > 70 ? s.substring(0, 67) + "..." : s;
    }
}
