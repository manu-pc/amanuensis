package com.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
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
 * Emulación de dous usuarios reais editando e subindo cambios ao mesmo repositorio
 * git ao mesmo tempo, sen mock ningún: un repo "remoto" bare local fai de GitHub, e
 * dous clons independentes (con os seus propios EditLedger, coma dous ordenadores
 * distintos) editan e suben coa mesma API que usa a app (GitRepoService).
 *
 * O bug real que motivou isto: o usuario 2 subía DESPOIS do usuario 1 e a subida do
 * usuario 2 devolvía ao inglés a liña que acababa de traducir o usuario 1, porque o
 * "meu cambio" do usuario 2 comparábase contra a árbore de traballo (que aínda tiña
 * TODAS as claves en inglés, incluída a que tocara o usuario 1) en vez de contra o
 * seu propio rexistro de edicións por clave.
 */
class ConcurrentUsersIntegrationTest {

    @TempDir
    Path tmp;

    private String prevLedgerDir;

    @BeforeEach
    void isolateLedgerStore() {
        // Os rexistros de edicións viven fóra do repo (~/.amanuensis/ledgers por
        // defecto); illar en /tmp para non tocar rexistros reais nin cruzarse entre
        // execucións do test.
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

    private static final String REL_FILE = "lang/chapter1/strings.json";

    /** Crea o repo "remoto" (bare) cun commit inicial con dúas claves en inglés. */
    private String createRemoteWithSeedCommit() throws Exception {
        Path bareDir = tmp.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDir.toFile()).call()) {
            // nada máis: só crear o bare
        }

        Path seedDir = tmp.resolve("seed");
        try (Git seed = Git.init().setDirectory(seedDir.toFile()).setInitialBranch("main").call()) {
            Path stringsFile = seedDir.resolve(REL_FILE);
            Files.createDirectories(stringsFile.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("key1", "Hello");
            obj.addProperty("key2", "World");
            JsonIo.writeAtomic(stringsFile, obj);

            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("seed").call();
            seed.remoteAdd().setName("origin").setUri(new URIish(bareDir.toUri().toString())).call();
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("main:refs/heads/main")).call();
        }

        try (Git bare = Git.open(bareDir.toFile())) {
            // O HEAD simbólico do bare é o que GitRepoService pregunta para saber a
            // rama por defecto (remoteDefaultBranch): ten que apuntar a "main".
            bare.getRepository().updateRef(Constants.HEAD).link("refs/heads/main");
        }

        return bareDir.toUri().toString();
    }

    private static int lineIndexOfKey(LocHelper lh, String key) {
        for (int i = 0; i < lh.getLineCount(); i++) {
            if (lh.getKey(i).equals(key)) {
                return i;
            }
        }
        throw new IllegalArgumentException("clave non atopada: " + key);
    }

    private JsonObject readRemoteFinalContent(String remoteUri) throws Exception {
        Path checkDir = tmp.resolve("check");
        GitRepoService checker = new GitRepoService(checkDir);
        checker.cloneRepo(remoteUri, null);
        return JsonIo.read(checkDir.resolve(REL_FILE));
    }

    @Test
    void secondUserPushAfterFirstDoesNotRevertFirstUsersTranslation() throws Exception {
        String remoteUri = createRemoteWithSeedCommit();

        Path user1Dir = tmp.resolve("user1");
        Path user2Dir = tmp.resolve("user2");
        GitRepoService repo1 = new GitRepoService(user1Dir);
        GitRepoService repo2 = new GitRepoService(user2Dir);
        // Os dous clonan ANTES de que ninguén edite nada: coma dous tradutores que
        // abriron a app o mesmo día.
        repo1.cloneRepo(remoteUri, null);
        repo2.cloneRepo(remoteUri, null);

        Path file1 = user1Dir.resolve(REL_FILE);
        Path file2 = user2Dir.resolve(REL_FILE);

        // Usuario 1 traduce "key1" e garda (queda no seu rexistro de edicións).
        LocHelper lh1 = new LocHelper(file1.toString());
        EditLedger ledger1 = EditLedger.openFor(file1, user1Dir);
        TranslationStore store1 = new TranslationStore(lh1, file1, ledger1);
        assertTrue(store1.save(lineIndexOfKey(lh1, "key1"), "Ola"));

        // Usuario 2 traduce "key2" SEN ter visto aínda o cambio do usuario 1 (o seu
        // clon segue no commit semente): coma dous ordenadores distintos á vez.
        LocHelper lh2 = new LocHelper(file2.toString());
        EditLedger ledger2 = EditLedger.openFor(file2, user2Dir);
        TranslationStore store2 = new TranslationStore(lh2, file2, ledger2);
        assertTrue(store2.save(lineIndexOfKey(lh2, "key2"), "Mundo"));

        // Usuario 1 sobe primeiro: sen conflito, avanza o remoto linealmente.
        GitRepoService.PushOutcome out1 = repo1.commitAndPushKeys(Path.of(REL_FILE),
                KeyMerge.fromLedger(ledger1.entries()), "traducion de user1",
                "User One", "u1@example.com", null);
        assertInstanceOf(GitRepoService.PushOutcome.Success.class, out1);

        // Usuario 2 sobe despois, cun clon local desactualizado (aínda ten "key1" en
        // inglés no disco, porque nunca fixo pull do cambio do usuario 1). Este é
        // exactamente o escenario que revertía a tradución do usuario 1.
        GitRepoService.PushOutcome out2 = repo2.commitAndPushKeys(Path.of(REL_FILE),
                KeyMerge.fromLedger(ledger2.entries()), "traducion de user2",
                "User Two", "u2@example.com", null);
        assertInstanceOf(GitRepoService.PushOutcome.Success.class, out2,
                "edicións en claves distintas non deberían entrar en conflito");

        JsonObject finalRemote = readRemoteFinalContent(remoteUri);
        assertEquals("Ola", JsonIo.stringOrNull(finalRemote, "key1"),
                "a tradución do usuario 1 non se pode perder cando sobe o usuario 2");
        assertEquals("Mundo", JsonIo.stringOrNull(finalRemote, "key2"));
    }

    @Test
    void sameKeyEditedByBothUsersGoesToConflictInsteadOfSilentlyOverwriting() throws Exception {
        String remoteUri = createRemoteWithSeedCommit();

        Path user1Dir = tmp.resolve("user1");
        Path user2Dir = tmp.resolve("user2");
        GitRepoService repo1 = new GitRepoService(user1Dir);
        GitRepoService repo2 = new GitRepoService(user2Dir);
        repo1.cloneRepo(remoteUri, null);
        repo2.cloneRepo(remoteUri, null);

        Path file1 = user1Dir.resolve(REL_FILE);
        Path file2 = user2Dir.resolve(REL_FILE);

        LocHelper lh1 = new LocHelper(file1.toString());
        EditLedger ledger1 = EditLedger.openFor(file1, user1Dir);
        TranslationStore store1 = new TranslationStore(lh1, file1, ledger1);
        assertTrue(store1.save(lineIndexOfKey(lh1, "key1"), "Ola"));

        // Os dous traducen a MESMA clave, dende a mesma base ("Hello"), con textos
        // distintos: iso si é un conflito real.
        LocHelper lh2 = new LocHelper(file2.toString());
        EditLedger ledger2 = EditLedger.openFor(file2, user2Dir);
        TranslationStore store2 = new TranslationStore(lh2, file2, ledger2);
        assertTrue(store2.save(lineIndexOfKey(lh2, "key1"), "Bo día"));

        GitRepoService.PushOutcome out1 = repo1.commitAndPushKeys(Path.of(REL_FILE),
                KeyMerge.fromLedger(ledger1.entries()), "traducion de user1",
                "User One", "u1@example.com", null);
        assertInstanceOf(GitRepoService.PushOutcome.Success.class, out1);

        GitRepoService.PushOutcome out2 = repo2.commitAndPushKeys(Path.of(REL_FILE),
                KeyMerge.fromLedger(ledger2.entries()), "traducion de user2",
                "User Two", "u2@example.com", null);
        assertInstanceOf(GitRepoService.PushOutcome.Conflict.class, out2);

        // O remoto ten que quedar coa versión do usuario 1 (a que xa estaba
        // publicada), NUNCA revertida a "Hello" nin sobrescrita en silencio pola do
        // usuario 2.
        JsonObject finalRemote = readRemoteFinalContent(remoteUri);
        assertEquals("Ola", JsonIo.stringOrNull(finalRemote, "key1"));
    }
}
