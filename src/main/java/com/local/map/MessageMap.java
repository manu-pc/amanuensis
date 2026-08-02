package com.local.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.local.JsonIo;

/**
 * Mapa de mensaxes repetidas entre capítulos: que claves de que ficheiros son,
 * <b>a mesma mensaxe do xogo</b>.
 *
 * Vive nun ficheiro do repositorio ({@link #FILE_NAME}, na raíz, ao carón de
 * {@code .git}) que se xera fóra da app con {@link MessageMapBuilder}. A app só
 * o le: se non está, todo segue funcionando sen propagación nin avisos
 * ({@link #isEmpty()}).
 *
 * <h2>Por que non abonda con comparar textos</h2>
 * As claves teñen a forma {@code <obxecto>_slash_<evento>_gml_<liña>_<índice>} e
 * a parte {@code <liña>} é o número de liña do GML descompilado <b>desa
 * compilación</b>. Entre capítulos móvese, así que:
 * <ul>
 * <li>a mesma clave pode ser unha mensaxe distinta (763 casos entre ch4 e ch5);</li>
 * <li>a mesma mensaxe pode ter claves distintas (ch1 fronte a ch2: 2250);</li>
 * <li>e o mesmo texto aparece en sitios que non teñen nada que ver
 * ({@code " "} en 437 lugares, {@code "Check"} en 68).</li>
 * </ul>
 * O agrupamento faino {@link MessageMapBuilder} aliñando o código, non o texto.
 *
 * <h2>Formato</h2>
 * <pre>
 * {
 *   "version": 1,
 *   "generatedAt": "2026-08-02T…",
 *   "files": ["lang/strings.json", "lang/chapter1/strings.json", …],
 *   "groups": [
 *     {"base": "* Hello./%", "ambiguous": false,
 *      "keys": {"1": ["obj_x_slash_…_gml_10_0"], "2": ["obj_x_slash_…_gml_14_0"]}}
 *   ]
 * }
 * </pre>
 * {@code files} está ordenado por capítulo, e as claves de {@code keys} son
 * índices dentro dese array: a orde do array <b>é</b> a orde de propagación.
 * {@code base} é o texto inglés orixinal (idéntico en todos os membros por
 * construción), que é o que permite distinguir «aínda sen traducir» de
 * «traducido doutra maneira». Só se gardan grupos con dous ou máis membros.
 */
public final class MessageMap {

    public static final String FILE_NAME = "message-map.json";
    public static final int VERSION = 1;

    /** Un membro dun grupo: ficheiro (ruta relativa ao repo) e clave. */
    public record Member(String relPath, String key, int fileIndex) {
    }

    /**
     * Unha mensaxe do xogo e todos os sitios onde aparece.
     *
     * @param base      texto inglés orixinal común a todos os membros
     * @param members   membros ordenados por capítulo (o primeiro é o máis antigo)
     * @param ambiguous o grupo contén dúas claves do mesmo ficheiro (a mensaxe está
     *                  duplicada dentro dun capítulo); o texto segue sendo o mesmo,
     *                  pero a correspondencia clave a clave non é única
     */
    public record Group(String base, List<Member> members, boolean ambiguous) {

        /** Membros distintos do indicado. */
        public List<Member> others(String relPath, String key) {
            List<Member> out = new ArrayList<>();
            for (Member m : members) {
                if (!(m.relPath().equals(relPath) && m.key().equals(key))) {
                    out.add(m);
                }
            }
            return out;
        }

        /** Membros de ficheiros anteriores ao indicado (capítulos previos). */
        public List<Member> before(String relPath) {
            int idx = -1;
            for (Member m : members) {
                if (m.relPath().equals(relPath)) {
                    idx = m.fileIndex();
                    break;
                }
            }
            if (idx < 0) {
                return List.of();
            }
            List<Member> out = new ArrayList<>();
            for (Member m : members) {
                if (m.fileIndex() < idx) {
                    out.add(m);
                }
            }
            return out;
        }
    }

    private final List<String> files;
    private final List<Group> groups;
    private final Map<String, Group> byMember; // "relPath|key" -> grupo

    private MessageMap(List<String> files, List<Group> groups) {
        this.files = List.copyOf(files);
        this.groups = List.copyOf(groups);
        Map<String, Group> index = new HashMap<>();
        for (Group g : groups) {
            for (Member m : g.members()) {
                index.put(memberId(m.relPath(), m.key()), g);
            }
        }
        this.byMember = Collections.unmodifiableMap(index);
    }

    public static MessageMap of(List<String> files, List<Group> groups) {
        return new MessageMap(files, groups);
    }

    /** Mapa baleiro: sen ficheiro de mapa non hai propagación, pero a app funciona. */
    public static MessageMap empty() {
        return new MessageMap(List.of(), List.of());
    }

    public static Path fileIn(Path repoRoot) {
        return repoRoot.resolve(FILE_NAME);
    }

    /**
     * Le o mapa da raíz do repositorio. Nunca lanza: un mapa ausente, ilexible ou
     * dunha versión futura devolve {@link #empty()}, porque quedar sen propagación
     * é moito mellor que non poder abrir o editor.
     */
    public static MessageMap load(Path repoRoot) {
        if (repoRoot == null) {
            return empty();
        }
        Path file = fileIn(repoRoot);
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        try {
            return parse(JsonIo.read(file));
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    static MessageMap parse(JsonObject obj) {
        if (obj.has("version") && obj.get("version").getAsInt() > VERSION) {
            return empty();
        }
        List<String> files = new ArrayList<>();
        for (JsonElement e : obj.getAsJsonArray("files")) {
            files.add(e.getAsString());
        }
        List<Group> groups = new ArrayList<>();
        for (JsonElement ge : obj.getAsJsonArray("groups")) {
            JsonObject g = ge.getAsJsonObject();
            String base = JsonIo.stringOrNull(g, "base");
            boolean ambiguous = g.has("ambiguous") && g.get("ambiguous").getAsBoolean();
            List<Member> members = new ArrayList<>();
            JsonObject keys = g.getAsJsonObject("keys");
            List<Integer> indexes = new ArrayList<>();
            for (String idx : keys.keySet()) {
                indexes.add(Integer.parseInt(idx));
            }
            Collections.sort(indexes); // orde de capítulo, non a do JSON
            for (int idx : indexes) {
                if (idx < 0 || idx >= files.size()) {
                    continue; // mapa incoherente: ignórase ese membro
                }
                for (JsonElement k : keys.getAsJsonArray(String.valueOf(idx))) {
                    members.add(new Member(files.get(idx), k.getAsString(), idx));
                }
            }
            if (members.size() > 1) {
                groups.add(new Group(base, List.copyOf(members), ambiguous));
            }
        }
        return new MessageMap(files, groups);
    }

    /** Serializa co mesmo formato que le {@link #parse}. */
    public JsonObject toJson(String generatedAt) {
        JsonObject obj = new JsonObject();
        obj.addProperty("version", VERSION);
        if (generatedAt != null) {
            obj.addProperty("generatedAt", generatedAt);
        }
        JsonArray filesArr = new JsonArray();
        files.forEach(filesArr::add);
        obj.add("files", filesArr);

        JsonArray groupsArr = new JsonArray();
        for (Group g : groups) {
            JsonObject go = new JsonObject();
            if (g.base() != null) {
                go.addProperty("base", g.base());
            }
            if (g.ambiguous()) {
                go.addProperty("ambiguous", true);
            }
            Map<Integer, JsonArray> keys = new LinkedHashMap<>();
            for (Member m : g.members()) {
                keys.computeIfAbsent(m.fileIndex(), i -> new JsonArray()).add(m.key());
            }
            JsonObject ko = new JsonObject();
            keys.forEach((idx, arr) -> ko.add(String.valueOf(idx), arr));
            go.add("keys", ko);
            groupsArr.add(go);
        }
        obj.add("groups", groupsArr);
        return obj;
    }

    public void write(Path repoRoot, String generatedAt) throws IOException {
        JsonIo.writeAtomic(fileIn(repoRoot), toJson(generatedAt));
    }

    /** Ficheiros cubertos, en orde de capítulo (rutas relativas ao repo). */
    public List<String> files() {
        return files;
    }

    public List<Group> groups() {
        return groups;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** Grupo ao que pertence esa clave dese ficheiro, ou null se non se repite. */
    public Group find(String relPath, String key) {
        if (relPath == null || key == null) {
            return null;
        }
        return byMember.get(memberId(normalize(relPath), key));
    }

    private static String memberId(String relPath, String key) {
        return relPath + "|" + key;
    }

    /** As rutas do mapa gárdanse con '/' aínda que se xerase noutro sistema. */
    public static String normalize(String relPath) {
        return relPath.replace('\\', '/');
    }
}
