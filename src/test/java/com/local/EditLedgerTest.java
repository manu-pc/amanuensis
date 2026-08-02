package com.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

class EditLedgerTest {

    @TempDir
    Path work;
    private Path repoRoot;
    private Path target;

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty(LedgerStore.DIR_PROPERTY, work.resolve("ledgers").toString());
        repoRoot = Files.createDirectories(work.resolve("repo"));
        target = repoRoot.resolve("lang/chapter1/strings.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "{}");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(LedgerStore.DIR_PROPERTY);
    }

    private EditLedger ledger() throws IOException {
        return EditLedger.openFor(target, repoRoot);
    }

    private static JsonObject json(String... keyValues) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            obj.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return obj;
    }

    @Test
    void relPathIsRelativeToTheRepo() throws IOException {
        assertEquals("lang/chapter1/strings.json", ledger().relPath());
    }

    @Test
    void fileOutsideTheRepoHasNoRelPath() throws IOException {
        Path outside = work.resolve("solto.json");
        Files.writeString(outside, "{}");
        assertNull(EditLedger.openFor(outside, repoRoot).relPath(),
                "un ficheiro fóra do repo pode editarse pero non subirse");
    }

    @Test
    void unchangedValueIsNotRecorded() throws IOException {
        EditLedger ledger = ledger();
        assertFalse(ledger.record("k", "* Igual/", "* Igual/"),
                "gardar sen cambiar nada non pode marcar a clave");
        assertTrue(ledger.isEmpty());
        assertFalse(Files.exists(ledger.ledgerFile()), "nin sequera se crea o ficheiro");
    }

    @Test
    void baseIsCapturedOnceAcrossRepeatedEdits() throws IOException {
        EditLedger ledger = ledger();
        assertTrue(ledger.record("k", "* Orixinal/", "* Primeira/"));
        assertTrue(ledger.record("k", "* Primeira/", "* Segunda/"));
        assertTrue(ledger.record("k", "* Segunda/", "* Terceira/"));

        EditLedger.Entry e = ledger.entry("k");
        assertEquals("* Orixinal/", e.base(), "a base ten que seguir sendo o valor de partida");
        assertEquals("* Terceira/", e.value());
        assertEquals(1, ledger.size());
    }

    @Test
    void typingBackToBaseRemovesTheEntry() throws IOException {
        EditLedger ledger = ledger();
        ledger.record("k", "* Orixinal/", "* Cambiado/");
        assertTrue(ledger.record("k", "* Cambiado/", "* Orixinal/"));
        assertTrue(ledger.isEmpty(), "volver ao texto de partida non deixa nada que subir");
        assertFalse(Files.exists(ledger.ledgerFile()));
    }

    @Test
    void survivesReloadFromDisk() throws IOException {
        EditLedger first = ledger();
        first.record("a", "base-a", "nova-a");
        first.record("b", "base-b", "nova-b");

        EditLedger reopened = ledger();
        assertEquals(2, reopened.size());
        assertEquals("base-a", reopened.entry("a").base());
        assertEquals("nova-b", reopened.entry("b").value());
        assertEquals(List.of("a", "b"), new java.util.ArrayList<>(reopened.keys()));
    }

    @Test
    void removeAndClearDropEntries() throws IOException {
        EditLedger ledger = ledger();
        ledger.record("a", "x", "y");
        ledger.record("b", "x", "y");

        ledger.remove(List.of("a"));
        assertEquals(1, ledger.size());

        ledger.clear();
        assertTrue(ledger.isEmpty());
        assertFalse(Files.exists(ledger.ledgerFile()));
    }

    // ---------- rebase tras un pull ----------

    @Test
    void rebaseDropsEditsThatAlreadyLanded() throws IOException {
        EditLedger ledger = ledger();
        ledger.record("k", "vello", "meu texto");

        // o servidor xa trae exactamente o noso texto (subímolo doutro sitio)
        List<String> clashes = ledger.rebaseAgainst(json("k", "meu texto"));

        assertTrue(clashes.isEmpty());
        assertTrue(ledger.isEmpty(), "xa non hai nada pendente");
    }

    @Test
    void rebaseKeepsEditsWhenTheRemoteDidNotTouchTheKey() throws IOException {
        EditLedger ledger = ledger();
        ledger.record("k", "vello", "meu texto");

        List<String> clashes = ledger.rebaseAgainst(json("k", "vello", "outra", "cambiada"));

        assertTrue(clashes.isEmpty());
        assertEquals("meu texto", ledger.entry("k").value());
        assertEquals("vello", ledger.entry("k").base());
    }

    @Test
    void rebaseReportsClashAndAdoptsTheRemoteValueAsNewBase() throws IOException {
        EditLedger ledger = ledger();
        ledger.record("k", "vello", "meu texto");

        List<String> clashes = ledger.rebaseAgainst(json("k", "texto doutra persoa"));

        assertEquals(List.of("k"), clashes);
        assertEquals("meu texto", ledger.entry("k").value(), "a nosa edición segue pendente");
        assertEquals("texto doutra persoa", ledger.entry("k").base(),
                "a nova base é o valor do servidor, así que o próximo push dará conflito de verdade");
    }

    @Test
    void rebaseTreatsAKeyDeletedUpstreamAsAClash() throws IOException {
        EditLedger ledger = ledger();
        ledger.record("k", "vello", "meu texto");

        assertEquals(List.of("k"), ledger.rebaseAgainst(json()));
        assertNull(ledger.entry("k").base());
    }

    // ---------- LedgerStore ----------

    @Test
    void storeFindsPendingEditsOfTheRepo() throws IOException {
        assertFalse(LedgerStore.hasPendingEdits(repoRoot));

        EditLedger ledger = ledger();
        ledger.record("k", "vello", "novo");

        assertTrue(LedgerStore.hasPendingEdits(repoRoot));
        assertEquals(1, LedgerStore.pendingCount(repoRoot));
        assertEquals(java.util.Set.of("lang/chapter1/strings.json"),
                LedgerStore.allEdits(repoRoot).keySet());
        assertEquals("novo",
                LedgerStore.allEdits(repoRoot).get("lang/chapter1/strings.json").get("k").value());
    }

    @Test
    void storeIgnoresLedgersOfOtherRepos() throws IOException {
        ledger().record("k", "vello", "novo");
        Path otherRepo = Files.createDirectories(work.resolve("outro-repo"));
        assertFalse(LedgerStore.hasPendingEdits(otherRepo));
    }

    @Test
    void twoFilesWithTheSameNameGetSeparateLedgers() throws IOException {
        Path second = repoRoot.resolve("lang/chapter2/strings.json");
        Files.createDirectories(second.getParent());
        Files.writeString(second, "{}");

        EditLedger first = ledger();
        EditLedger other = EditLedger.openFor(second, repoRoot);
        assertFalse(first.ledgerFile().equals(other.ledgerFile()));

        first.record("k", "a", "b");
        other.record("k", "c", "d");
        assertEquals(2, LedgerStore.allEdits(repoRoot).size());
    }

    @Test
    void corruptLedgerFileIsIgnoredInsteadOfBreakingStartup() throws IOException {
        Files.createDirectories(LedgerStore.dir());
        Files.writeString(LedgerStore.dir().resolve("roto.ledger.json"), "{ non é json");
        assertTrue(LedgerStore.loadAll().isEmpty());
    }
}
