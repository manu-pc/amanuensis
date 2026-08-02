package com.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * Rexistro durable das edicións pendentes de subir dun ficheiro de tradución,
 * clave a clave.
 *
 * Substitúe ao vello sistema de copias {@code .copy.json}: as gardas van
 * directamente ao ficheiro real (o clon de git xa é a copia de seguridade) e
 * aquí só se anota, por clave, <b>o valor que tiña cando o usuario a tocou por
 * primeira vez</b> ({@code base}) e <b>o valor que escribiu</b> ({@code value}).
 *
 * Ese {@code base} é a peza que faltaba: é a única forma de distinguir «isto
 * editeino eu» de «o meu ficheiro está desactualizado aquí». Sen el, a
 * reconciliación tomaba calquera clave obsoleta da árbore de traballo como unha
 * edición deliberada e sobrescribía en silencio a tradución doutra persoa.
 *
 * O ficheiro vive fóra de {@code lang/} (ver {@link LedgerStore}) e reescríbese
 * de forma atómica en cada cambio; é pequeno, así que sae case gratis.
 */
public final class EditLedger {

    /** Versión do formato en disco, para poder migralo se cambia. */
    static final int VERSION = 1;

    /**
     * @param base  valor que a clave tiña no ficheiro cando se editou por
     *              primeira vez nesta tanda de cambios (a base real de tres vías)
     * @param value valor que escribiu o usuario
     * @param at    instante ISO-8601 da última modificación
     */
    public record Entry(String base, String value, String at) {
    }

    private final Path ledgerFile;
    private final Path targetFile;
    private final String relPath;
    private final Path repoRoot;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean batching; // ver beginBatch/endBatch

    private EditLedger(Path ledgerFile, Path targetFile, Path repoRoot, String relPath) {
        this.ledgerFile = ledgerFile;
        this.targetFile = targetFile;
        this.repoRoot = repoRoot;
        this.relPath = relPath;
    }

    /**
     * Abre (ou crea) o rexistro do ficheiro indicado.
     *
     * @param repoRoot raíz do repositorio, ou null se o ficheiro está fóra: nese
     *                 caso {@link #relPath()} é null e as edicións non se poden subir
     */
    public static EditLedger openFor(Path jsonFile, Path repoRoot) throws IOException {
        Path abs = jsonFile.toAbsolutePath().normalize();
        String rel = relativize(repoRoot, abs);
        EditLedger ledger = new EditLedger(LedgerStore.fileFor(abs), abs, repoRoot, rel);
        ledger.load();
        return ledger;
    }

    /** Carga un rexistro xa existente a partir do seu propio ficheiro. */
    static EditLedger fromLedgerFile(Path ledgerFile) throws IOException {
        JsonObject obj = JsonIo.read(ledgerFile);
        String absPath = JsonIo.stringOrNull(obj, "absPath");
        String repoRootText = JsonIo.stringOrNull(obj, "repoRoot");
        if (absPath == null) {
            throw new IOException("rexistro de edicións sen absPath: " + ledgerFile);
        }
        EditLedger ledger = new EditLedger(ledgerFile, Path.of(absPath),
                repoRootText != null ? Path.of(repoRootText) : null,
                JsonIo.stringOrNull(obj, "relPath"));
        ledger.readEntries(obj);
        return ledger;
    }

    private static String relativize(Path repoRoot, Path abs) {
        if (repoRoot == null) {
            return null;
        }
        Path root = repoRoot.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : null;
    }

    private void load() throws IOException {
        if (!Files.exists(ledgerFile)) {
            return;
        }
        readEntries(JsonIo.read(ledgerFile));
    }

    private void readEntries(JsonObject obj) {
        entries.clear();
        if (!obj.has("entries") || !obj.get("entries").isJsonObject()) {
            return;
        }
        JsonObject stored = obj.getAsJsonObject("entries");
        for (String key : stored.keySet()) {
            if (!stored.get(key).isJsonObject()) {
                continue;
            }
            JsonObject e = stored.getAsJsonObject(key);
            String value = JsonIo.stringOrNull(e, "value");
            if (value == null) {
                continue; // entrada corrupta: ignórase en vez de romper a sesión
            }
            entries.put(key, new Entry(JsonIo.stringOrNull(e, "base"), value,
                    JsonIo.stringOrNull(e, "at")));
        }
    }

    // ------------------------------------------------------------------
    // consulta
    // ------------------------------------------------------------------

    public Path targetFile() {
        return targetFile;
    }

    /** Ruta relativa á raíz do repo, ou null se o ficheiro está fóra (non subible). */
    public String relPath() {
        return relPath;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    Path ledgerFile() {
        return ledgerFile;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public Map<String, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    public Entry entry(String key) {
        return entries.get(key);
    }

    public boolean isPending(String key) {
        return entries.containsKey(key);
    }

    // ------------------------------------------------------------------
    // modificación
    // ------------------------------------------------------------------

    /**
     * Anota unha edición.
     *
     * <ul>
     * <li>se o valor novo é igual ao que xa hai no disco, non se anota nada:
     * «tocar» non é «cambiar», e así gardar sen editar non pode subir nada</li>
     * <li>a primeira vez que se toca unha clave gárdase {@code base = diskValue};
     * este é o <b>único</b> sitio onde se escribe {@code base}</li>
     * <li>se o usuario volve escribir o texto orixinal, a entrada desaparece</li>
     * </ul>
     *
     * @return true se o rexistro cambiou (hai que escribir o valor no ficheiro)
     */
    public boolean record(String key, String diskValue, String newValue) throws IOException {
        if (Objects.equals(newValue, diskValue)) {
            return false;
        }
        Entry existing = entries.get(key);
        if (existing == null) {
            entries.put(key, new Entry(diskValue, newValue, now()));
        } else if (Objects.equals(newValue, existing.base())) {
            // volveu ao texto de partida: xa non hai nada que subir
            entries.remove(key);
            flush();
            return true;
        } else {
            entries.put(key, new Entry(existing.base(), newValue, now()));
        }
        flush();
        return true;
    }

    public void remove(Collection<String> keys) throws IOException {
        if (entries.keySet().removeAll(new java.util.LinkedHashSet<>(keys))) {
            flush();
        }
    }

    /** Baleira o rexistro e borra o seu ficheiro. */
    public void clear() throws IOException {
        entries.clear();
        Files.deleteIfExists(ledgerFile);
    }

    /**
     * Reaxusta o rexistro contra o contido actual do ficheiro (tras un pull).
     *
     * <ul>
     * <li>disco == o noso valor → a nosa edición xa chegou por outra vía: fóra</li>
     * <li>disco == base → ninguén tocou esa clave: mantense pendente</li>
     * <li>calquera outra cosa → <b>choque</b>: adóptase o valor do servidor como
     * nova base e avísase, para que o usuario decida en vez de gañar en silencio</li>
     * </ul>
     *
     * @return claves en choque (editadas por nós e tamén cambiadas no servidor)
     */
    public List<String> rebaseAgainst(JsonObject disk) throws IOException {
        List<String> clashes = new ArrayList<>();
        List<String> landed = new ArrayList<>();

        for (Map.Entry<String, Entry> e : new LinkedHashMap<>(entries).entrySet()) {
            String key = e.getKey();
            Entry entry = e.getValue();
            String diskValue = JsonIo.stringOrNull(disk, key);

            if (Objects.equals(diskValue, entry.value())) {
                landed.add(key);
            } else if (Objects.equals(diskValue, entry.base())) {
                // nada novo do servidor nesta clave: segue pendente tal cal
            } else {
                entries.put(key, new Entry(diskValue, entry.value(), now()));
                clashes.add(key);
            }
        }

        entries.keySet().removeAll(new java.util.LinkedHashSet<>(landed));
        if (!landed.isEmpty() || !clashes.isEmpty()) {
            flush();
        }
        return clashes;
    }

    /**
     * Agrupa moitas anotacións nunha soa escritura.
     *
     * {@link #record} escribe o rexistro enteiro cada vez, o que está ben para
     * unha persoa escribindo liña a liña pero é cuadrático cando a propagación
     * entre capítulos toca miles de claves dun golpe. Entre {@code beginBatch} e
     * {@link #endBatch} anótase todo en memoria e escríbese unha única vez.
     *
     * Se o proceso morre no medio pérdense as anotacións do lote, non o rexistro:
     * o ficheiro en disco segue sendo o último estado consistente.
     */
    public void beginBatch() {
        batching = true;
    }

    /** Remata o lote e escribe. Chamar sempre nun {@code finally}. */
    public void endBatch() throws IOException {
        batching = false;
        flush();
    }

    /** Escribe o rexistro en disco (atomicamente). Bórrao se quedou baleiro. */
    public void flush() throws IOException {
        if (batching) {
            return;
        }
        if (entries.isEmpty()) {
            Files.deleteIfExists(ledgerFile);
            return;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("version", VERSION);
        if (repoRoot != null) {
            obj.addProperty("repoRoot", repoRoot.toAbsolutePath().normalize().toString());
        }
        if (relPath != null) {
            obj.addProperty("relPath", relPath);
        }
        obj.addProperty("absPath", targetFile.toString());
        obj.addProperty("updatedAt", now());

        JsonObject stored = new JsonObject();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            JsonObject entry = new JsonObject();
            if (e.getValue().base() != null) {
                entry.addProperty("base", e.getValue().base());
            }
            entry.addProperty("value", e.getValue().value());
            if (e.getValue().at() != null) {
                entry.addProperty("at", e.getValue().at());
            }
            stored.add(e.getKey(), entry);
        }
        obj.add("entries", stored);

        LedgerStore.ensureDir();
        JsonIo.writeAtomic(ledgerFile, obj);
    }

    private static String now() {
        return Instant.now().toString();
    }

    @Override
    public String toString() {
        return "EditLedger[" + (relPath != null ? relPath : targetFile) + ", " + entries.size() + " pendentes]";
    }
}
