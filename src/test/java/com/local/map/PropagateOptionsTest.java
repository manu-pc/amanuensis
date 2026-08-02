package com.local.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.local.JsonIo;
import com.local.LedgerStore;

/**
 * As opcións que fan falta para montar un capítulo novo sen tocar os xa revisados:
 * escribir só nun ficheiro, e escribir sen deixar rastro no rexistro de edicións.
 */
class PropagateOptionsTest {

    @TempDir
    Path work;
    private Path repoRoot;

    private static final String KEY = "obj_a_slash_Step_0_gml_10_0";
    private static final String EN = "* Hello./%";
    private static final String GL = "* Ola./%";

    private static final String CH1 = "lang/chapter1/strings.json";
    private static final String CH2 = "lang/chapter2/strings.json";
    private static final String CH5 = "lang/chapter5/strings.json";

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty(LedgerStore.DIR_PROPERTY, work.resolve("ledgers").toString());
        repoRoot = Files.createDirectories(work.resolve("repo"));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(LedgerStore.DIR_PROPERTY);
    }

    private void writeFile(String rel, String... keyValues) throws IOException {
        Path f = repoRoot.resolve(rel);
        Files.createDirectories(f.getParent());
        JsonObject obj = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            obj.addProperty(keyValues[i], keyValues[i + 1]);
        }
        JsonIo.writeAtomic(f, obj);
    }

    private String valueOf(String rel) throws IOException {
        return JsonIo.stringOrNull(JsonIo.read(repoRoot.resolve(rel)), KEY);
    }

    private static MessageMapBuilder.FileData data(String rel, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY, value);
        return new MessageMapBuilder.FileData(rel, values);
    }

    /**
     * ch1 traducido, ch2 traducido doutra maneira, ch5 aínda en inglés: o mesmo
     * estado real do proxecto ao empezar o capítulo 5.
     */
    private MessageMap threeChapters() throws IOException {
        writeFile(CH1, KEY, GL);
        writeFile(CH2, KEY, "* Boas./%");
        writeFile(CH5, KEY, EN);
        // o mapa constrúese sobre o inglés, que é como se xera de verdade
        return MessageMapBuilder.build(List.of(data(CH1, EN), data(CH2, EN), data(CH5, EN)));
    }

    @Test
    void onlyWritesTheChapterYouAskFor() throws IOException {
        MessageMap map = threeChapters();

        PropagateChapters.Report report = PropagateChapters.run(repoRoot, map,
                new PropagateChapters.Options(true, false, Set.of(CH5), true));

        assertEquals(GL, valueOf(CH5), "capítulo 5 colle a tradución máis antiga");
        assertEquals("* Boas./%", valueOf(CH2), "capítulo 2 non se toca");
        assertEquals(1, report.changes().size());
        assertEquals(CH5, report.changes().get(0).relPath());
    }

    @Test
    void withoutTheFlagEveryChapterIsRewritten() throws IOException {
        MessageMap map = threeChapters();

        PropagateChapters.run(repoRoot, map, new PropagateChapters.Options(true, false, null, true));

        assertEquals(GL, valueOf(CH5));
        assertEquals(GL, valueOf(CH2), "sen --so, ch2 tamén se iguala a ch1");
    }

    @Test
    void withoutLedgerNothingIsQueuedForPush() throws IOException {
        MessageMap map = threeChapters();

        PropagateChapters.run(repoRoot, map,
                new PropagateChapters.Options(true, false, Set.of(CH5), false));

        assertEquals(GL, valueOf(CH5), "o ficheiro si se escribe");
        assertFalse(LedgerStore.hasPendingEdits(repoRoot),
                "sen rexistro: o commit hai que facelo a man");
    }

    @Test
    void withLedgerTheChangeIsQueuedForPush() throws IOException {
        MessageMap map = threeChapters();

        PropagateChapters.run(repoRoot, map,
                new PropagateChapters.Options(true, false, Set.of(CH5), true));

        assertTrue(LedgerStore.hasPendingEdits(repoRoot));
        assertEquals(EN, LedgerStore.ledgersFor(repoRoot).get(CH5).entry(KEY).base());
    }

    @Test
    void aDryRunWithOnlyStillReportsWhatItWouldDo() throws IOException {
        MessageMap map = threeChapters();

        PropagateChapters.Report report = PropagateChapters.run(repoRoot, map,
                new PropagateChapters.Options(false, false, Set.of(CH5), true));

        assertEquals(1, report.changes().size());
        assertEquals(EN, valueOf(CH5), "a simulación non escribe");
    }
}
