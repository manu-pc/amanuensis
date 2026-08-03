package com.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.glossary.Glossary;
import com.glossary.XlsxReader;
import com.local.LedgerStore;

/**
 * O glosario compartido, movéndose entre dous tradutores de verdade: un repo bare
 * local fai de GitHub e dous clons independentes usan a mesma API ca a app.
 *
 * <p>
 * Cobre os dous fallos que motivaron este traballo:
 * <ol>
 * <li>na raíz do repositorio o ficheiro chegaba <b>unha soa vez</b>, ao clonar,
 * porque {@code resetLangTo} só saca ao disco {@code lang/}; dentro de lang/ ten
 * que actualizarse coma calquera outro ficheiro;</li>
 * <li>gardar o .xlsx sen tocar nada cambia os seus bytes (LibreOffice reescribe o
 * ZIP), e iso deixaba a árbore «sucia» para sempre, o que pararía todos os pull.</li>
 * </ol>
 */
class GlossarySyncIntegrationTest {

    @TempDir
    Path tmp;

    private String prevLedgerDir;

    @BeforeEach
    void isolateLedgerStore() {
        prevLedgerDir = System.getProperty(LedgerStore.DIR_PROPERTY);
        System.setProperty(LedgerStore.DIR_PROPERTY, tmp.resolve("ledgers").toString());
    }

    @AfterEach
    void restoreLedgerStore() {
        if (prevLedgerDir != null) {
            System.setProperty(LedgerStore.DIR_PROPERTY, prevLedgerDir);
        } else {
            System.clearProperty(LedgerStore.DIR_PROPERTY);
        }
    }

    // ---------------------------------------------------------------
    // andamio
    // ---------------------------------------------------------------

    /** Libro dunha folla, unha fila, cos textos dados. */
    private static byte[] book(long zipTime, String... values) {
        StringBuilder shared = new StringBuilder("<?xml version=\"1.0\"?><sst>");
        for (String v : values) {
            shared.append("<si><t>").append(v).append("</t></si>");
        }
        shared.append("</sst>");

        StringBuilder sheet = new StringBuilder(
                "<?xml version=\"1.0\"?><worksheet><sheetData><row r=\"1\">");
        for (int i = 0; i < values.length; i++) {
            sheet.append("<c r=\"").append((char) ('A' + i)).append("1\" t=\"s\"><v>")
                    .append(i).append("</v></c>");
        }
        sheet.append("</row></sheetData></worksheet>");

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml", "<?xml version=\"1.0\"?><workbook><sheets>"
                + "<sheet name=\"CAPITULO 1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships>"
                + "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        parts.put("xl/sharedStrings.xml", shared.toString());
        parts.put("xl/worksheets/sheet1.xml", sheet.toString());

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                for (Map.Entry<String, String> e : parts.entrySet()) {
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setTime(zipTime);
                    zos.putNextEntry(entry);
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Repo bare cun commit inicial que xa trae o glosario dentro de lang/. */
    private String createRemoteWithGlossary() throws Exception {
        Path bareDir = tmp.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDir.toFile()).call()) {
            // só crear o bare
        }

        Path seedDir = tmp.resolve("seed");
        try (Git seed = Git.init().setDirectory(seedDir.toFile()).setInitialBranch("main").call()) {
            Path g = seedDir.resolve(Glossary.REL_PATH);
            Files.createDirectories(g.getParent());
            Files.write(g, book(0L, "Bandage", "Venda"));

            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("seed").call();
            seed.remoteAdd().setName("origin").setUri(new URIish(bareDir.toUri().toString())).call();
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("main:refs/heads/main")).call();
        }
        try (Git bare = Git.open(bareDir.toFile())) {
            bare.getRepository().updateRef(Constants.HEAD).link("refs/heads/main");
        }
        return bareDir.toUri().toString();
    }

    // ---------------------------------------------------------------
    // probas
    // ---------------------------------------------------------------

    @Test
    void aGlossaryEditReachesTheOtherTranslator() throws Exception {
        String remote = createRemoteWithGlossary();

        Path user1Dir = tmp.resolve("user1");
        Path user2Dir = tmp.resolve("user2");
        GitRepoService repo1 = new GitRepoService(user1Dir);
        GitRepoService repo2 = new GitRepoService(user2Dir);
        repo1.cloneRepo(remote, null);
        repo2.cloneRepo(remote, null);

        // O usuario 1 abre o glosario, corrixe un termo e garda.
        byte[] edited = book(0L, "Bandage", "Vendaxe");
        Files.write(user1Dir.resolve(Glossary.REL_PATH), edited);

        GitRepoService.PushOutcome out = repo1.commitAndPushAllDirty(
                "glosario", "User One", "u1@example.com", null);
        assertInstanceOf(GitRepoService.PushOutcome.Success.class, out);

        // O usuario 2 fai "ver cambios": ten que chegarlle ao disco. Antes non
        // chegaba nunca, porque o ficheiro estaba fóra de lang/.
        assertEquals(GitRepoService.PullOutcome.UPDATED, repo2.pullIfSafe(null));

        byte[] got = Files.readAllBytes(user2Dir.resolve(Glossary.REL_PATH));
        assertEquals(XlsxReader.digest(edited), XlsxReader.digest(got),
                "o glosario corrixido ten que chegar ao outro tradutor");
        assertEquals("Vendaxe", XlsxReader.read(got).cells().get(1).value());
    }

    @Test
    void savingTheGlossaryWithoutChangingAnythingDoesNotBlockPulls() throws Exception {
        String remote = createRemoteWithGlossary();

        Path userDir = tmp.resolve("user");
        GitRepoService repo = new GitRepoService(userDir);
        repo.cloneRepo(remote, null);

        Path local = userDir.resolve(Glossary.REL_PATH);
        byte[] before = Files.readAllBytes(local);

        // Abrir e gardar sen tocar nada: mesmas celas, ZIP reescrito. É o que fai
        // LibreOffice, e o que deixaba a árbore sucia para sempre.
        Files.write(local, book(1_700_000_000_000L, "Bandage", "Venda"));
        byte[] after = Files.readAllBytes(local);

        assertFalse(java.util.Arrays.equals(before, after),
                "os bytes teñen que cambiar, se non a proba non demostra nada");
        assertFalse(repo.hasTrackedChanges(),
                "gardar sen cambiar nada non pode contar como cambio pendente");
        assertNotEquals(GitRepoService.PullOutcome.SKIPPED_DIRTY, repo.pullIfSafe(null),
                "e polo tanto non pode bloquear as actualizacións");
    }

    @Test
    void aRealEditIsStillSeenAsPending() throws Exception {
        String remote = createRemoteWithGlossary();
        Path userDir = tmp.resolve("user");
        GitRepoService repo = new GitRepoService(userDir);
        repo.cloneRepo(remote, null);

        Files.write(userDir.resolve(Glossary.REL_PATH), book(0L, "Bandage", "Vendaxe"));
        assertTrue(repo.hasTrackedChanges(), "cambiar unha cela si é un cambio pendente");
    }

    @Test
    void twoPeopleEditingTheGlossaryAtOnceGoToAConflictBranchInsteadOfOverwriting() throws Exception {
        String remote = createRemoteWithGlossary();

        Path user1Dir = tmp.resolve("user1");
        Path user2Dir = tmp.resolve("user2");
        GitRepoService repo1 = new GitRepoService(user1Dir);
        GitRepoService repo2 = new GitRepoService(user2Dir);
        repo1.cloneRepo(remote, null);
        repo2.cloneRepo(remote, null);

        byte[] mine = book(0L, "Bandage", "Vendaxe");
        byte[] theirs = book(0L, "Bandage", "Apósito");
        Files.write(user1Dir.resolve(Glossary.REL_PATH), mine);
        Files.write(user2Dir.resolve(Glossary.REL_PATH), theirs);

        assertInstanceOf(GitRepoService.PushOutcome.Success.class,
                repo1.commitAndPushFile(Glossary.REL_PATH, "glosario",
                        "User One", "u1@example.com", null));

        GitRepoService.PushOutcome out2 = repo2.commitAndPushFile(Glossary.REL_PATH, "glosario",
                "User Two", "u2@example.com", null);
        GitRepoService.PushOutcome.Conflict c = assertInstanceOf(
                GitRepoService.PushOutcome.Conflict.class, out2,
                "un libro de cálculo non se pode fusionar: ten que quedar para revisar");
        assertTrue(c.fallbackBranch().startsWith("amanuensis-conflito-"), c.fallbackBranch());

        // O traballo do usuario 2 non se perde: queda na rama de conflito.
        try (Git bare = Git.open(Path.of(java.net.URI.create(remote)).toFile())) {
            var ref = bare.getRepository().findRef("refs/heads/" + c.fallbackBranch());
            assertTrue(ref != null, "a rama de conflito ten que existir no remoto");
        }
        // ...e a copia local pasa a ser a do servidor, non unha mestura inventada.
        assertArrayEquals(mine, Files.readAllBytes(user2Dir.resolve(Glossary.REL_PATH)),
                "tras o conflito o disco queda coa versión que xa está no servidor");
    }

    @Test
    void pushingAnUnchangedGlossaryDoesNothing() throws Exception {
        String remote = createRemoteWithGlossary();
        Path userDir = tmp.resolve("user");
        GitRepoService repo = new GitRepoService(userDir);
        repo.cloneRepo(remote, null);

        assertInstanceOf(GitRepoService.PushOutcome.Success.class,
                repo.commitAndPushFile(Glossary.REL_PATH, "glosario",
                        "User One", "u1@example.com", null));

        try (Git git = Git.open(userDir.toFile())) {
            assertEquals(1, count(git.log().call()), "sen cambios non se crea ningún commit");
        }
    }

    private static int count(Iterable<?> it) {
        int n = 0;
        for (Object ignored : it) {
            n++;
        }
        return n;
    }
}
