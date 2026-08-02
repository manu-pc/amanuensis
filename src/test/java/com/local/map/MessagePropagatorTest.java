package com.local.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.local.EditLedger;
import com.local.JsonIo;
import com.local.LedgerStore;
import com.local.LocHelper;
import com.local.TranslationStore;

/**
 * Propagación entre capítulos, incluída a que fai o editor ao gardar unha liña.
 */
class MessagePropagatorTest {

    @TempDir
    Path work;
    private Path repoRoot;

    private static final String KEY = "obj_a_slash_Step_0_gml_10_0";
    private static final String KEY_CH2 = "obj_a_slash_Step_0_gml_14_0";
    private static final String EN = "* Hello./%";

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty(LedgerStore.DIR_PROPERTY, work.resolve("ledgers").toString());
        repoRoot = Files.createDirectories(work.resolve("repo"));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(LedgerStore.DIR_PROPERTY);
    }

    // ---------------------------------------------------------------
    // utilidades
    // ---------------------------------------------------------------

    private Path writeFile(String rel, String... keyValues) throws IOException {
        Path f = repoRoot.resolve(rel);
        Files.createDirectories(f.getParent());
        JsonObject obj = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            obj.addProperty(keyValues[i], keyValues[i + 1]);
        }
        JsonIo.writeAtomic(f, obj);
        return f;
    }

    private String valueOf(String rel, String key) throws IOException {
        return JsonIo.stringOrNull(JsonIo.read(repoRoot.resolve(rel)), key);
    }

    private static MessageMapBuilder.FileData data(String rel, String... keyValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return new MessageMapBuilder.FileData(rel, values);
    }

    /** Tres capítulos coa mesma mensaxe, coa clave desprazada en ch2 e ch3. */
    private MessageMap threeChapters() throws IOException {
        writeFile("lang/chapter1/strings.json", KEY, EN);
        writeFile("lang/chapter2/strings.json", KEY_CH2, EN);
        writeFile("lang/chapter3/strings.json", KEY, EN);
        return MessageMapBuilder.build(List.of(
                data("lang/chapter1/strings.json", KEY, EN),
                data("lang/chapter2/strings.json", KEY_CH2, EN),
                data("lang/chapter3/strings.json", KEY, EN)));
    }

    // ---------------------------------------------------------------
    // propagador
    // ---------------------------------------------------------------

    @Test
    void writesTheOtherChaptersAndRecordsThemInTheirLedgers() throws IOException {
        MessageMap map = threeChapters();

        MessagePropagator.Result r = MessagePropagator.propagate(repoRoot, map,
                "lang/chapter1/strings.json", KEY, "* Ola./%",
                MessagePropagator.defaultLedgers(repoRoot));

        assertEquals(2, r.written().size());
        assertTrue(r.overwritten().isEmpty(), "ningunha estaba traducida");
        assertEquals("* Ola./%", valueOf("lang/chapter2/strings.json", KEY_CH2));
        assertEquals("* Ola./%", valueOf("lang/chapter3/strings.json", KEY));

        // e queda pendente de subir, coa base correcta para a reconciliación
        EditLedger ch2 = EditLedger.openFor(repoRoot.resolve("lang/chapter2/strings.json"), repoRoot);
        assertEquals("* Ola./%", ch2.entry(KEY_CH2).value());
        assertEquals(EN, ch2.entry(KEY_CH2).base());
    }

    @Test
    void reportsWhichOnesHadATranslationOfTheirOwn() throws IOException {
        MessageMap map = threeChapters();
        // alguén traducira ch3 doutra maneira
        writeFile("lang/chapter3/strings.json", KEY, "* Boas./%");

        MessagePropagator.Result r = MessagePropagator.propagate(repoRoot, map,
                "lang/chapter1/strings.json", KEY, "* Ola./%",
                MessagePropagator.defaultLedgers(repoRoot));

        assertEquals(2, r.written().size());
        assertEquals(1, r.overwritten().size());
        assertEquals("lang/chapter3/strings.json", r.overwritten().get(0).relPath());
        assertEquals("* Boas./%", r.overwritten().get(0).previous());
        // sobrescríbese igual: é a decisión do proxecto
        assertEquals("* Ola./%", valueOf("lang/chapter3/strings.json", KEY));
    }

    @Test
    void aStaleMapIsReportedInsteadOfCreatingKeys() throws IOException {
        MessageMap map = threeChapters();
        writeFile("lang/chapter2/strings.json", "outra_clave", EN); // KEY_CH2 xa non está

        MessagePropagator.Result r = MessagePropagator.propagate(repoRoot, map,
                "lang/chapter1/strings.json", KEY, "* Ola./%",
                MessagePropagator.defaultLedgers(repoRoot));

        assertEquals(1, r.written().size());
        assertEquals(1, r.missing().size());
        assertFalse(JsonIo.read(repoRoot.resolve("lang/chapter2/strings.json")).has(KEY_CH2));
    }

    @Test
    void aLineOutsideTheMapPropagatesNothing() throws IOException {
        MessageMap map = threeChapters();
        MessagePropagator.Result r = MessagePropagator.propagate(repoRoot, map,
                "lang/chapter1/strings.json", "clave_que_non_esta", "x",
                MessagePropagator.defaultLedgers(repoRoot));
        assertTrue(r.isEmpty());
    }

    @Test
    void withoutAMapNothingIsTouched() throws IOException {
        threeChapters();
        MessagePropagator.Result r = MessagePropagator.propagate(repoRoot, MessageMap.empty(),
                "lang/chapter1/strings.json", KEY, "* Ola./%",
                MessagePropagator.defaultLedgers(repoRoot));
        assertTrue(r.isEmpty());
        assertEquals(EN, valueOf("lang/chapter2/strings.json", KEY_CH2));
    }

    // ---------------------------------------------------------------
    // integración co editor
    // ---------------------------------------------------------------

    @Test
    void savingInTheEditorPropagatesAndKeepsOneLedgerPerFile() throws IOException {
        MessageMap map = threeChapters();
        Path ch1 = repoRoot.resolve("lang/chapter1/strings.json");

        LocHelper loc = new LocHelper(ch1.toString());
        EditLedger ledger = EditLedger.openFor(ch1, repoRoot);
        TranslationStore store = new TranslationStore(loc, ch1, ledger, repoRoot, map);

        assertTrue(store.save(0, "* Ola./%"));

        assertEquals("* Ola./%", valueOf("lang/chapter1/strings.json", KEY));
        assertEquals("* Ola./%", valueOf("lang/chapter2/strings.json", KEY_CH2));
        assertEquals(2, store.lastPropagation().written().size());
        // o rexistro do propio ficheiro é o que xa tiña aberto o editor: unha soa
        // entrada, non unha por cada escritor
        assertEquals(1, ledger.size());
        assertEquals("* Ola./%", ledger.entry(KEY).value());
    }

    @Test
    void theEditorCanSeeWhereTheLineCameFrom() throws IOException {
        MessageMap map = threeChapters();
        Path ch3 = repoRoot.resolve("lang/chapter3/strings.json");

        LocHelper loc = new LocHelper(ch3.toString());
        TranslationStore store = new TranslationStore(loc, ch3,
                EditLedger.openFor(ch3, repoRoot), repoRoot, map);

        assertNotNull(store.groupOf(0));
        assertEquals(List.of("lang/chapter1/strings.json", "lang/chapter2/strings.json"),
                store.earlierOccurrences(0).stream()
                        .map(MessageMap.Member::relPath).toList());
    }

    @Test
    void aDuplicateInsideTheSameFileIsUpdatedInMemoryToo() throws IOException {
        // mensaxe duplicada dentro do capítulo: gardar unha ten que actualizar a
        // outra tamén na lista en memoria, senón o editor ensinaría o texto vello
        // ch1 leva as dúas copias, ch2 quedou coa primeira e ch3 coa segunda: así
        // as dúas claves de ch1 acaban no mesmo grupo (grupo ambiguo)
        String k2 = "obj_a_slash_Step_0_gml_11_0";
        writeFile("lang/chapter1/strings.json", KEY, EN, k2, EN);
        writeFile("lang/chapter2/strings.json", KEY, EN);
        writeFile("lang/chapter3/strings.json", k2, EN);
        MessageMap map = MessageMapBuilder.build(List.of(
                data("lang/chapter1/strings.json", KEY, EN, k2, EN),
                data("lang/chapter2/strings.json", KEY, EN),
                data("lang/chapter3/strings.json", k2, EN)));
        assertTrue(map.find("lang/chapter1/strings.json", KEY).ambiguous());

        Path ch1 = repoRoot.resolve("lang/chapter1/strings.json");
        LocHelper loc = new LocHelper(ch1.toString());
        TranslationStore store = new TranslationStore(loc, ch1,
                EditLedger.openFor(ch1, repoRoot), repoRoot, map);

        assertTrue(store.save(0, "* Ola./%"));

        assertEquals("* Ola./%", loc.getOriginal(0));
        assertEquals("* Ola./%", loc.getOriginal(1));
        assertEquals("* Ola./%", valueOf("lang/chapter1/strings.json", k2));
    }

    // ---------------------------------------------------------------
    // propagación masiva
    // ---------------------------------------------------------------

    @Test
    void bulkTakesTheEarliestTranslatedOneNotTheEarliestOne() throws IOException {
        MessageMap map = threeChapters();
        // ch1 sen traducir, ch2 traducido: o bo é o de ch2
        writeFile("lang/chapter2/strings.json", KEY_CH2, "* Ola./%");

        PropagateChapters.Report report = PropagateChapters.run(repoRoot, map, true, false);

        assertEquals(2, report.changes().size());
        assertEquals("* Ola./%", valueOf("lang/chapter1/strings.json", KEY));
        assertEquals("* Ola./%", valueOf("lang/chapter3/strings.json", KEY));
        assertEquals(0, report.overwritten());
    }

    @Test
    void bulkSkipsGroupsNobodyTranslatedYet() throws IOException {
        MessageMap map = threeChapters();
        PropagateChapters.Report report = PropagateChapters.run(repoRoot, map, true, false);
        assertEquals(0, report.groupsWithSource());
        assertTrue(report.changes().isEmpty());
        assertEquals(EN, valueOf("lang/chapter2/strings.json", KEY_CH2));
    }

    @Test
    void bulkDryRunTouchesNothing() throws IOException {
        MessageMap map = threeChapters();
        writeFile("lang/chapter1/strings.json", KEY, "* Ola./%");

        PropagateChapters.Report report = PropagateChapters.run(repoRoot, map, false, false);

        assertEquals(2, report.changes().size());
        assertEquals(EN, valueOf("lang/chapter2/strings.json", KEY_CH2), "a simulación non escribe");
        assertFalse(LedgerStore.hasPendingEdits(repoRoot));
    }

    @Test
    void bulkForceFirstUsesChapterOneEvenIfUntranslated() throws IOException {
        MessageMap map = threeChapters();
        writeFile("lang/chapter2/strings.json", KEY_CH2, "* Ola./%");

        PropagateChapters.Report report = PropagateChapters.run(repoRoot, map, true, true);

        assertEquals(EN, valueOf("lang/chapter2/strings.json", KEY_CH2), "volveu ao inglés de ch1");
        assertEquals(1, report.overwritten());
    }

    @Test
    void bulkChangesAreQueuedForTheNextPush() throws IOException {
        MessageMap map = threeChapters();
        writeFile("lang/chapter1/strings.json", KEY, "* Ola./%");

        PropagateChapters.run(repoRoot, map, true, false);

        Map<String, EditLedger> ledgers = LedgerStore.ledgersFor(repoRoot);
        assertEquals(2, ledgers.size());
        assertEquals(EN, ledgers.get("lang/chapter2/strings.json").entry(KEY_CH2).base());
    }
}
