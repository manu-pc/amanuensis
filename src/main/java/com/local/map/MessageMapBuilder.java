package com.local.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.local.JsonIo;

/**
 * Constrúe o {@link MessageMap} a partir dos {@code strings.json} <b>en inglés</b>.
 *
 * <h2>Por que hai que aliñar código e non textos</h2>
 * Cada capítulo é unha compilación distinta do xogo, así que o número de liña
 * que levan as claves ({@code …_gml_240_0}) se despraza. Comparar só claves dá
 * falsos positivos (763 claves comúns entre ch4 e ch5 son mensaxes distintas) e
 * falsos negativos (ch1 recompilouse enteiro: 1290 coincidencias por clave fronte
 * a ~3500 reais). Comparar só textos é moito peor: 1861 textos aparecen en
 * mensaxes que non teñen nada que ver ({@code " "} en 437 sitios).
 *
 * <h2>Algoritmo</h2>
 * Un {@code strings.json} é un volcado do código fonte na súa orde. Entre
 * compilacións, o código dun mesmo {@code (entidade, evento)} cambia por
 * insercións e borrados: exactamente o que resolve un diff. Entón:
 * <ol>
 * <li>agrúpanse as claves por bloque {@code (entidade, evento)} e ordénanse por
 * (liña, índice);</li>
 * <li>por cada par de ficheiros e cada bloque común, faise a
 * <b>subsecuencia común máis longa</b> dos <i>valores</i>: cada parella aliñada é
 * a mesma mensaxe. Ao estar limitado ao bloque, un {@code "Check"} de
 * {@code obj_shop1} nunca se aliña co de {@code scr_text};</li>
 * <li>segunda pasada polo mesmo par: clave idéntica <b>e</b> valor idéntico
 * tamén é parella. Recupera as reordenacións que a LCS descarta e cubre as ~30
 * claves por ficheiro que non seguen o formato;</li>
 * <li>union-find sobre todas as parellas: cada compoñente é unha mensaxe do xogo.</li>
 * </ol>
 *
 * Ao rematar compróbase a invariante que fai fiable todo isto: <b>ningún grupo
 * pode conter dous textos distintos</b>. Sobre os cinco capítulos reais dá 66937
 * entradas → 39948 mensaxes (9402 grupos repartidos entre capítulos) con cero
 * violacións.
 *
 * Execútase fóra da app (ver {@code scripts/build-message-map.sh}); a app só le
 * o resultado.
 */
public final class MessageMapBuilder {

    /** Un ficheiro de traducións xa lido: ruta relativa ao repo + valores en orde. */
    public record FileData(String relPath, Map<String, String> values) {
    }

    /** Que fixo a construción, para poder ensinalo por consola. */
    public record Stats(int files, int entries, int groups, int multiChapterGroups,
            int ambiguousGroups, int conflicts) {
    }

    private static final Pattern CHAPTER = Pattern.compile("chapter(\\d+)");
    /** Por riba disto, a matriz da LCS non compensa: úsase só o aliñamento por áncoras. */
    private static final long MAX_DP_CELLS = 64_000_000L;

    private MessageMapBuilder() {
    }

    // ---------------------------------------------------------------
    // construción
    // ---------------------------------------------------------------

    /**
     * Constrúe o mapa. A orde da lista <b>é</b> a orde de propagación: o primeiro
     * ficheiro é o capítulo máis antigo.
     */
    public static MessageMap build(List<FileData> files) {
        return build(files, new int[1]);
    }

    static MessageMap build(List<FileData> files, int[] conflictsOut) {
        int n = files.size();
        List<Map<String, List<Slot>>> blocks = new ArrayList<>(n);
        for (FileData f : files) {
            blocks.add(blocksOf(f));
        }

        UnionFind uf = new UnionFind();
        for (int i = 0; i < n; i++) {
            for (String key : files.get(i).values().keySet()) {
                uf.find(new Member(i, key));
            }
        }

        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                alignFiles(files, blocks, a, b, uf);
            }
        }

        return collect(files, uf, conflictsOut);
    }

    /** Claves dun ficheiro agrupadas por bloque de código e ordenadas coma o fonte. */
    private static Map<String, List<Slot>> blocksOf(FileData file) {
        Map<String, List<Slot>> out = new HashMap<>();
        for (Map.Entry<String, String> e : file.values().entrySet()) {
            MessageKey parsed = MessageKey.parse(e.getKey());
            if (parsed == null) {
                continue; // claves opacas: só por igualdade exacta (segunda pasada)
            }
            out.computeIfAbsent(parsed.block(), k -> new ArrayList<>())
                    .add(new Slot(parsed, e.getKey(), e.getValue()));
        }
        for (List<Slot> slots : out.values()) {
            slots.sort(Comparator.comparing(Slot::parsed));
        }
        return out;
    }

    private static void alignFiles(List<FileData> files, List<Map<String, List<Slot>>> blocks,
            int a, int b, UnionFind uf) {
        Map<String, List<Slot>> ba = blocks.get(a);
        Map<String, List<Slot>> bb = blocks.get(b);

        // 1) aliñamento de código dentro de cada bloque común
        for (Map.Entry<String, List<Slot>> e : ba.entrySet()) {
            List<Slot> sb = bb.get(e.getKey());
            if (sb == null) {
                continue;
            }
            List<Slot> sa = e.getValue();
            for (int[] pair : alignSlots(sa, sb)) {
                uf.union(new Member(a, sa.get(pair[0]).key()),
                        new Member(b, sb.get(pair[1]).key()));
            }
        }

        // 2) mesma clave e mesmo valor: reordenacións que a LCS descarta, e claves opacas
        Map<String, String> va = files.get(a).values();
        Map<String, String> vb = files.get(b).values();
        Map<String, String> small = va.size() <= vb.size() ? va : vb;
        Map<String, String> big = small == va ? vb : va;
        for (Map.Entry<String, String> e : small.entrySet()) {
            String other = big.get(e.getKey());
            if (other != null && other.equals(e.getValue())) {
                uf.union(new Member(a, e.getKey()), new Member(b, e.getKey()));
            }
        }
    }

    /** Parellas (índice en a, índice en b) de valores iguais, respectando a orde. */
    static List<int[]> alignSlots(List<Slot> a, List<Slot> b) {
        List<String> va = a.stream().map(Slot::value).toList();
        List<String> vb = b.stream().map(Slot::value).toList();
        return lcsPairs(va, vb);
    }

    /**
     * Subsecuencia común máis longa de dúas listas, como parellas de índices.
     *
     * Recórtanse primeiro prefixo e sufixo comúns (o caso habitual: o bloque case
     * non cambiou), e só o miolo vai á programación dinámica. Se aínda así a
     * matriz é desproporcionada, cáese a {@link #anchorPairs}, que só aliña os
     * valores que aparecen unha soa vez nos dous lados — menos parellas, pero
     * ningunha inventada.
     */
    static List<int[]> lcsPairs(List<String> a, List<String> b) {
        List<int[]> out = new ArrayList<>();
        int n = a.size();
        int m = b.size();

        int pre = 0;
        while (pre < n && pre < m && Objects.equals(a.get(pre), b.get(pre))) {
            out.add(new int[] { pre, pre });
            pre++;
        }
        int suf = 0;
        while (suf < n - pre && suf < m - pre
                && Objects.equals(a.get(n - 1 - suf), b.get(m - 1 - suf))) {
            suf++;
        }

        int an = n - suf - pre;
        int bn = m - suf - pre;
        if (an > 0 && bn > 0) {
            List<String> ma = a.subList(pre, n - suf);
            List<String> mb = b.subList(pre, m - suf);
            List<int[]> mid = (long) an * bn > MAX_DP_CELLS ? anchorPairs(ma, mb) : dpPairs(ma, mb);
            for (int[] p : mid) {
                out.add(new int[] { p[0] + pre, p[1] + pre });
            }
        }

        for (int i = suf; i > 0; i--) {
            out.add(new int[] { n - i, m - i });
        }
        out.sort(Comparator.comparingInt(p -> p[0]));
        return out;
    }

    private static List<int[]> dpPairs(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] len = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            String ai = a.get(i);
            int[] cur = len[i];
            int[] next = len[i + 1];
            for (int j = m - 1; j >= 0; j--) {
                cur[j] = Objects.equals(ai, b.get(j))
                        ? next[j + 1] + 1
                        : Math.max(next[j], cur[j + 1]);
            }
        }
        List<int[]> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (Objects.equals(a.get(i), b.get(j))) {
                out.add(new int[] { i, j });
                i++;
                j++;
            } else if (len[i + 1][j] >= len[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return out;
    }

    /** Só valores únicos nos dous lados, en orde crecente: aliñamento conservador. */
    private static List<int[]> anchorPairs(List<String> a, List<String> b) {
        Map<String, Integer> ca = count(a);
        Map<String, Integer> cb = count(b);
        Map<String, Integer> posB = new HashMap<>();
        for (int j = 0; j < b.size(); j++) {
            posB.putIfAbsent(b.get(j), j);
        }
        List<int[]> out = new ArrayList<>();
        int lastJ = -1;
        for (int i = 0; i < a.size(); i++) {
            String v = a.get(i);
            if (ca.getOrDefault(v, 0) != 1 || cb.getOrDefault(v, 0) != 1) {
                continue;
            }
            int j = posB.get(v);
            if (j > lastJ) {
                out.add(new int[] { i, j });
                lastJ = j;
            }
        }
        return out;
    }

    private static Map<String, Integer> count(List<String> xs) {
        Map<String, Integer> c = new HashMap<>();
        for (String x : xs) {
            c.merge(x, 1, Integer::sum);
        }
        return c;
    }

    /** Compoñentes → grupos, descartando os dun só membro e verificando a invariante. */
    private static MessageMap collect(List<FileData> files, UnionFind uf, int[] conflictsOut) {
        Map<Member, List<Member>> comps = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            for (String key : files.get(i).values().keySet()) {
                Member m = new Member(i, key);
                comps.computeIfAbsent(uf.find(m), r -> new ArrayList<>()).add(m);
            }
        }

        int conflicts = 0;
        List<MessageMap.Group> groups = new ArrayList<>();
        for (List<Member> members : comps.values()) {
            if (members.size() < 2) {
                continue;
            }
            members.sort(Comparator.<Member>comparingInt(Member::file).thenComparing(Member::key));

            Set<String> distinct = new LinkedHashSet<>();
            for (Member m : members) {
                distinct.add(files.get(m.file()).values().get(m.key()));
            }
            if (distinct.size() > 1) {
                // non debería pasar nunca: dous textos distintos na mesma mensaxe
                // significaría que o aliñamento uniu cousas que non van xuntas
                conflicts++;
                continue;
            }

            Set<Integer> seenFiles = new HashSet<>();
            boolean ambiguous = false;
            List<MessageMap.Member> out = new ArrayList<>(members.size());
            for (Member m : members) {
                ambiguous |= !seenFiles.add(m.file());
                out.add(new MessageMap.Member(files.get(m.file()).relPath(), m.key(), m.file()));
            }
            groups.add(new MessageMap.Group(distinct.iterator().next(), List.copyOf(out), ambiguous));
        }

        groups.sort(Comparator.<MessageMap.Group>comparingInt(g -> g.members().get(0).fileIndex())
                .thenComparing(g -> g.members().get(0).key()));

        conflictsOut[0] = conflicts;
        return MessageMap.of(files.stream().map(FileData::relPath).toList(), groups);
    }

    // ---------------------------------------------------------------
    // descubrimento de ficheiros
    // ---------------------------------------------------------------

    /**
     * Os {@code strings.json} de {@code lang/}, ordenados por capítulo (o
     * {@code lang/strings.json} da raíz, que é o menú de selección de capítulo,
     * vai primeiro). Ignóranse as copias vellas {@code *.copy*.json} e os
     * {@code chapter_settings.json}, que non levan texto traducible por clave.
     */
    public static List<Path> discover(Path repoRoot) throws IOException {
        Path lang = repoRoot.resolve("lang");
        if (!Files.isDirectory(lang)) {
            return List.of();
        }
        List<Path> found;
        try (var stream = Files.walk(lang)) {
            found = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("strings.json"))
                    .toList());
        }
        found.sort(Comparator.comparingInt(MessageMapBuilder::chapterOf)
                .thenComparing(Path::toString));
        return found;
    }

    static int chapterOf(Path file) {
        Matcher m = CHAPTER.matcher(file.toString());
        int last = 0;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        return last;
    }

    /** Le un ficheiro quedándose só cos valores de texto, na orde do ficheiro. */
    public static FileData read(Path repoRoot, Path file) throws IOException {
        JsonObject obj = JsonIo.read(file);
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                values.put(e.getKey(), v.getAsString());
            }
        }
        String rel = MessageMap.normalize(repoRoot.relativize(file).toString());
        return new FileData(rel, values);
    }

    // ---------------------------------------------------------------
    // main
    // ---------------------------------------------------------------

    /** {@code MessageMapBuilder <raíz-do-repo>} — escribe {@code message-map.json}. */
    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        List<Path> files = discover(repoRoot);
        if (files.isEmpty()) {
            System.err.println("non atopei ningún lang/**/strings.json en " + repoRoot);
            System.exit(1);
        }

        List<FileData> data = new ArrayList<>();
        for (Path f : files) {
            FileData fd = read(repoRoot, f);
            data.add(fd);
            System.out.printf("  %-34s %6d cadeas%n", fd.relPath(), fd.values().size());
        }

        int[] conflicts = new int[1];
        long t0 = System.currentTimeMillis();
        MessageMap map = build(data, conflicts);
        long ms = System.currentTimeMillis() - t0;

        int entries = data.stream().mapToInt(d -> d.values().size()).sum();
        int inGroups = map.groups().stream().mapToInt(g -> g.members().size()).sum();
        long ambiguous = map.groups().stream().filter(MessageMap.Group::ambiguous).count();
        Map<Integer, Integer> sizes = new TreeMap<>();
        for (MessageMap.Group g : map.groups()) {
            sizes.merge(g.members().size(), 1, Integer::sum);
        }

        map.write(repoRoot, Instant.now().toString());

        System.out.println();
        System.out.printf("entradas totais       %d%n", entries);
        System.out.printf("mensaxes repetidas    %d grupos (%d entradas, %d únicas)%n",
                map.groups().size(), inGroups, entries - inGroups + map.groups().size());
        System.out.printf("grupos ambiguos       %d (dúas claves do mesmo ficheiro)%n", ambiguous);
        System.out.printf("conflitos de texto    %d %s%n", conflicts[0],
                conflicts[0] == 0 ? "(invariante intacta)" : "*** REVISAR ***");
        System.out.printf("tamaños de grupo      %s%n", sizes);
        System.out.printf("escrito en            %s (%d ms)%n", MessageMap.fileIn(repoRoot), ms);
    }

    // ---------------------------------------------------------------
    // internos
    // ---------------------------------------------------------------

    record Slot(MessageKey parsed, String key, String value) {
    }

    private record Member(int file, String key) {
    }

    /** Union-find con compresión de camiños; as parellas chegan sen orde ningunha. */
    private static final class UnionFind {
        private final Map<Member, Member> parent = new HashMap<>();

        Member find(Member x) {
            Member root = x;
            Member p;
            // iterativo a propósito: as cadeas poden ser longas (decenas de miles
            // de nós) e a recursión desbordaría a pila
            while (!(p = parent.computeIfAbsent(root, k -> k)).equals(root)) {
                root = p;
            }
            Member cur = x;
            while (!cur.equals(root)) {
                Member next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        void union(Member a, Member b) {
            Member ra = find(a);
            Member rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }
    }
}
