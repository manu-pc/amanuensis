package com.local.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonObject;
import com.local.EditLedger;
import com.local.JsonIo;

/**
 * Propaga o texto dunha liña ás demais aparicións da <b>mesma mensaxe do xogo</b>
 * segundo o {@link MessageMap}.
 *
 * Escribe sempre: se unha aparición xa tiña outra tradución, tamén se
 * sobrescribe (decisión do proxecto — unha mensaxe idéntica en inglés debe selo
 * tamén en galego). O que si se distingue é o <b>informe</b>: o
 * {@link Result} separa as que estaban sen traducir (o valor en disco aínda era
 * o inglés orixinal do grupo) das que tiñan outra tradución, para que a interface
 * poida avisar de que se pisou traballo.
 *
 * Cada escritura pasa polo {@link EditLedger} do seu ficheiro, coa mesma disciplina
 * que unha edición manual: así a propagación viaxa no seguinte push como calquera
 * outro cambio, e non hai que tocar nada de git.
 */
public final class MessagePropagator {

    /** Unha aparición escrita. */
    public record Target(String relPath, String key, String previous, boolean wasUntranslated) {
    }

    /**
     * @param written       apariciones actualizadas
     * @param overwritten   as de {@code written} que xa tiñan outra tradución
     * @param missing       claves do mapa que xa non existen no seu ficheiro (o mapa
     *                      quedou vello: hai que rexeneralo)
     * @param errors        ficheiros que non se puideron escribir
     */
    public record Result(List<Target> written, List<Target> overwritten,
            List<String> missing, List<String> errors) {

        public static final Result NONE = new Result(List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return written.isEmpty() && missing.isEmpty() && errors.isEmpty();
        }

        /** Resumo curto para a barra de estado. */
        public String summary() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("propagado a ").append(written.size())
                    .append(written.size() == 1 ? " aparición" : " aparicións");
            if (!overwritten.isEmpty()) {
                sb.append(" (").append(overwritten.size()).append(" xa tiñan outra tradución)");
            }
            if (!missing.isEmpty()) {
                sb.append("; ").append(missing.size()).append(" claves xa non existen");
            }
            if (!errors.isEmpty()) {
                sb.append("; ").append(errors.size()).append(" erros");
            }
            return sb.toString();
        }
    }

    /**
     * De onde saen os rexistros de edicións. Existe para que quen xa teña aberto o
     * rexistro dun ficheiro (o editor) pase o seu en vez de abrir un segundo, que
     * escribiría por riba do primeiro.
     */
    public interface Ledgers {
        /** O rexistro dese ficheiro, ou null para escribir sen anotar nada. */
        EditLedger ledgerFor(Path jsonFile) throws IOException;
    }

    /**
     * Escribe sen tocar ningún rexistro de edicións.
     *
     * <b>Só para traballo de mantemento que se vai commitear a man</b> (montar o
     * estado inicial dun capítulo, por exemplo). Dentro da app nunca se debe usar:
     * un cambio sen entrada no rexistro non se sube nunca, porque o commit
     * constrúese a partir do rexistro, non da árbore de traballo.
     */
    public static Ledgers noLedgers() {
        return file -> null;
    }

    /** Abre (e reutiliza) un rexistro por ficheiro. */
    public static Ledgers defaultLedgers(Path repoRoot) {
        Map<Path, EditLedger> cache = new HashMap<>();
        return file -> {
            Path abs = file.toAbsolutePath().normalize();
            EditLedger cached = cache.get(abs);
            if (cached == null) {
                cached = EditLedger.openFor(abs, repoRoot);
                cache.put(abs, cached);
            }
            return cached;
        };
    }

    private MessagePropagator() {
    }

    /**
     * Escribe {@code newValue} en todas as aparicións da mensaxe distintas da
     * indicada. Non fai nada se a liña non está no mapa.
     */
    public static Result propagate(Path repoRoot, MessageMap map, String relPath, String key,
            String newValue, Ledgers ledgers) {
        if (repoRoot == null || map == null || map.isEmpty()) {
            return Result.NONE;
        }
        MessageMap.Group group = map.find(relPath, key);
        if (group == null) {
            return Result.NONE;
        }
        return write(repoRoot, group.others(MessageMap.normalize(relPath), key),
                group.base(), newValue, ledgers);
    }

    /**
     * Escribe o valor nas apariciones indicadas, agrupando por ficheiro para ler e
     * escribir cada JSON unha soa vez.
     */
    static Result write(Path repoRoot, List<MessageMap.Member> targets, String base,
            String newValue, Ledgers ledgers) {
        List<Target> written = new ArrayList<>();
        List<Target> overwritten = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Map<String, List<MessageMap.Member>> byFile = new LinkedHashMap<>();
        for (MessageMap.Member m : targets) {
            byFile.computeIfAbsent(m.relPath(), r -> new ArrayList<>()).add(m);
        }

        for (Map.Entry<String, List<MessageMap.Member>> e : byFile.entrySet()) {
            String rel = e.getKey();
            Path file = repoRoot.resolve(rel);
            if (!Files.isRegularFile(file)) {
                errors.add(rel + ": non existe");
                continue;
            }
            try {
                JsonObject obj = JsonIo.read(file);
                EditLedger ledger = ledgers.ledgerFor(file);
                boolean dirty = false;

                // unha soa escritura do rexistro por ficheiro: nunha propagación
                // masiva isto son miles de claves
                if (ledger != null) {
                    ledger.beginBatch();
                }
                try {
                    for (MessageMap.Member m : e.getValue()) {
                        if (!obj.has(m.key())) {
                            missing.add(rel + " / " + m.key());
                            continue;
                        }
                        String disk = JsonIo.stringOrNull(obj, m.key());
                        if (Objects.equals(disk, newValue)) {
                            continue; // xa está
                        }
                        // o rexistro é quen decide: se devolve false non hai nada que gardar
                        if (ledger != null && !ledger.record(m.key(), disk, newValue)) {
                            continue;
                        }
                        obj.addProperty(m.key(), newValue);
                        dirty = true;

                        boolean untranslated = Objects.equals(disk, base);
                        Target t = new Target(rel, m.key(), disk, untranslated);
                        written.add(t);
                        if (!untranslated) {
                            overwritten.add(t);
                        }
                    }
                } finally {
                    if (ledger != null) {
                        ledger.endBatch();
                    }
                }

                if (dirty) {
                    JsonIo.writeAtomic(file, obj);
                }
            } catch (IOException | RuntimeException ex) {
                errors.add(rel + ": " + ex.getMessage());
            }
        }

        return new Result(List.copyOf(written), List.copyOf(overwritten),
                List.copyOf(missing), List.copyOf(errors));
    }
}
