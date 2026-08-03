package com.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.local.EditLedger;
import com.local.LedgerStore;

/**
 * A primeira execución real: o tradutor baixa **só o .jar**, déixao nunha carpeta
 * súa e pulsa «descargar proxecto de tradución».
 *
 * <p>
 * Esa carpeta nunca está baleira — dentro está o propio .jar —, así que
 * {@code git clone} non vale: JGit rexéitaa con «destination path already exists
 * and is not an empty directory». O camiño alternativo existía, pero rematara nun
 * reset MIXED pensado para adoptar unha árbore que xa estaba no disco (un .zip
 * descomprimido); cunha carpeta que só ten o .jar iso deixaba o repositorio con
 * todo o proxecto marcado como borrado e nada que abrir.
 */
class FirstRunCloneIntegrationTest {

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

    /** Repo bare cun capítulo dentro de lang/, coma o de verdade. */
    private String createRemote() throws Exception {
        Path bareDir = tmp.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDir.toFile()).call()) {
            // só crear o bare
        }

        Path seedDir = tmp.resolve("seed");
        try (Git seed = Git.init().setDirectory(seedDir.toFile()).setInitialBranch("main").call()) {
            Path strings = seedDir.resolve("lang/chapter1/strings.json");
            Files.createDirectories(strings.getParent());
            Files.writeString(strings, "{\"saudo\":\"Hello\"}", StandardCharsets.UTF_8);
            Files.writeString(seedDir.resolve("README.md"), "proxecto", StandardCharsets.UTF_8);

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

    /** Coma o anterior, pero con varios commits para que a profundidade importe. */
    private String createRemoteWithHistory(int commits) throws Exception {
        Path bareDir = tmp.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDir.toFile()).call()) {
            // só crear o bare
        }

        Path seedDir = tmp.resolve("seed");
        try (Git seed = Git.init().setDirectory(seedDir.toFile()).setInitialBranch("main").call()) {
            Path strings = seedDir.resolve("lang/chapter1/strings.json");
            Files.createDirectories(strings.getParent());
            for (int i = 0; i < commits; i++) {
                Files.writeString(strings, "{\"saudo\":\"v" + i + "\"}", StandardCharsets.UTF_8);
                seed.add().addFilepattern(".").call();
                seed.commit().setMessage("c" + i).call();
            }
            seed.remoteAdd().setName("origin").setUri(new URIish(bareDir.toUri().toString())).call();
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("main:refs/heads/main")).call();
        }
        try (Git bare = Git.open(bareDir.toFile())) {
            bare.getRepository().updateRef(Constants.HEAD).link("refs/heads/main");
        }
        return bareDir.toUri().toString();
    }

    /** Unha rama vella no remoto, coas cousas pesadas que xa non están en main. */
    private void addSideBranch(String remoteUri, String name, String heavyFile) throws Exception {
        try (Git seed = Git.open(tmp.resolve("seed").toFile())) {
            seed.checkout().setCreateBranch(true).setName(name).call();
            Files.write(seed.getRepository().getWorkTree().toPath().resolve(heavyFile),
                    new byte[512 * 1024]);
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("restos").call();
            seed.push().setRemote(remoteUri)
                    .setRefSpecs(new RefSpec(name + ":refs/heads/" + name)).call();
            seed.checkout().setName("main").call();
        }
    }

    /** Substitúe o historial do remoto por outro sen relación (coma unha purga). */
    private void rewriteRemoteHistory(String remoteUri, String content) throws Exception {
        Path other = tmp.resolve("reescrito");
        try (Git g = Git.init().setDirectory(other.toFile()).setInitialBranch("main").call()) {
            Path strings = other.resolve("lang/chapter1/strings.json");
            Files.createDirectories(strings.getParent());
            Files.writeString(strings, content, StandardCharsets.UTF_8);
            g.add().addFilepattern(".").call();
            g.commit().setMessage("historial purgado").call();
            g.push().setRemote(remoteUri).setForce(true)
                    .setRefSpecs(new RefSpec("main:refs/heads/main")).call();
        }
    }

    /** Outra persoa sobe un cambio ao remoto. */
    private void pushOneMoreCommit(String remoteUri, String content) throws Exception {
        try (Git seed = Git.open(tmp.resolve("seed").toFile())) {
            Files.writeString(seed.getRepository().getWorkTree().toPath()
                    .resolve("lang/chapter1/strings.json"), content, StandardCharsets.UTF_8);
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("outra persoa").call();
            seed.push().setRemote(remoteUri)
                    .setRefSpecs(new RefSpec("main:refs/heads/main")).call();
        }
    }

    private static int countCommits(Git git) throws Exception {
        int n = 0;
        for (Object ignored : git.log().add(git.getRepository().resolve("HEAD")).call()) {
            n++;
        }
        return n;
    }

    /** A carpeta do tradutor: nada dentro agás o .jar que acaba de descargar. */
    private Path folderWithJarOnly() throws Exception {
        Path base = tmp.resolve("carpeta-do-tradutor");
        Files.createDirectories(base);
        Files.writeString(base.resolve("amanuensis.jar"), "finxo ser un jar", StandardCharsets.UTF_8);
        return base;
    }

    @Test
    void downloadingIntoTheFolderThatHoldsTheJarBringsTheWholeProject() throws Exception {
        String remote = createRemote();
        Path base = folderWithJarOnly();

        new GitRepoService(base).cloneRepo(remote, null);

        assertTrue(Files.isRegularFile(base.resolve("lang/chapter1/strings.json")),
                "o capítulo ten que estar no disco, non só no índice");
        assertEquals("{\"saudo\":\"Hello\"}",
                Files.readString(base.resolve("lang/chapter1/strings.json")));
        assertTrue(Files.isRegularFile(base.resolve("README.md")));
        assertEquals("finxo ser un jar", Files.readString(base.resolve("amanuensis.jar")),
                "o .jar en execución non se toca");

        try (Git git = Git.open(base.toFile())) {
            Status st = git.status().call();
            assertTrue(st.getMissing().isEmpty(),
                    "nada pode figurar como borrado: iso bloquearía o primeiro pull");
            assertTrue(st.getModified().isEmpty());
            assertEquals(Set.of("amanuensis.jar"), st.getUntracked(),
                    "o único que sobra na carpeta é o .jar, que non está no repo");
            assertEquals("main", git.getRepository().getBranch());
        }
    }

    @Test
    void aSecondDownloadAfterAFailedOneDoesNotDieOnTheLeftoverGitFolder() throws Exception {
        String remote = createRemote();
        Path base = folderWithJarOnly();
        GitRepoService repo = new GitRepoService(base);

        // primeiro intento que deixa un .git a medias: init sen remoto nin HEAD útil
        try (Git ignored = Git.init().setDirectory(base.toFile()).call()) {
            // só deixar o .git aí
        }
        assertTrue(repo.isCloned(), "hai .git, aínda que non sirva para nada");

        repo.cloneRepo(remote, null);

        assertTrue(Files.isRegularFile(base.resolve("lang/chapter1/strings.json")));
        try (Git git = Git.open(base.toFile())) {
            assertTrue(git.status().call().getMissing().isEmpty());
        }
    }

    @Test
    void anAlreadyDownloadedProjectIsNotOverwrittenBySomeoneElsesMistake() throws Exception {
        String remote = createRemote();
        Path base = folderWithJarOnly();
        GitRepoService repo = new GitRepoService(base);
        repo.cloneRepo(remote, null);

        Path strings = base.resolve("lang/chapter1/strings.json");
        Files.writeString(strings, "{\"saudo\":\"Ola\"}", StandardCharsets.UTF_8);

        repo.cloneRepo(remote, null);

        assertEquals("{\"saudo\":\"Ola\"}", Files.readString(strings),
                "unha tradución sen subir non se pisa por volver pulsar o botón");
    }

    /**
     * A descarga inicial baixa **só o último commit**. O historial do proxecto
     * pesa 222 MB (ficheiros que xa nin están nel: .zip, .dll, .jar, .mp4,
     * sprites, sons) fronte aos 9 MB de texto que se traduce; un clon superficial
     * deixa a descarga en 1,6 MB de git. A app nunca le o historial.
     */
    @Test
    void theDownloadOnlyBringsTheLastCommit() throws Exception {
        String remote = createRemoteWithHistory(12);
        Path base = folderWithJarOnly();

        new GitRepoService(base).cloneRepo(remote, null);

        try (Git git = Git.open(base.toFile())) {
            assertFalse(git.getRepository().getObjectDatabase().getShallowCommits().isEmpty(),
                    "o clon ten que quedar superficial");
            assertEquals(1, countCommits(git), "un só commit, non os 12");
        }
        assertEquals("{\"saudo\":\"v11\"}",
                Files.readString(base.resolve("lang/chapter1/strings.json")),
                "e aínda así o contido é o último");
    }

    /**
     * O repositorio de verdade ten ramas vellas ({@code amanuensis-conflito-*},
     * {@code c4trad/*}) anteriores a quitar os sprites e os sons: só a súa punta xa
     * pesa 224 MB cada unha. Baixar «un commit de cada rama» custaba 141 MB dos
     * 222 MB totais — o clon superficial non servía de nada. Só se pide a rama de
     * traballo, e os fetch seguintes tampouco poden ir buscar as outras.
     */
    @Test
    void theOldHeavyBranchesAreNeverDownloaded() throws Exception {
        String remote = createRemoteWithHistory(4);
        addSideBranch(remote, "amanuensis-conflito-alguen", "restos-pesados.bin");
        Path base = folderWithJarOnly();

        new GitRepoService(base).cloneRepo(remote, null);

        try (Git git = Git.open(base.toFile())) {
            assertEquals(List.of("refs/remotes/origin/main"),
                    git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
                            .stream().map(Ref::getName).toList());
            assertNull(git.getRepository().resolve("refs/remotes/origin/amanuensis-conflito-alguen"));
            assertEquals("+refs/heads/main:refs/remotes/origin/main",
                    git.getRepository().getConfig().getString("remote", "origin", "fetch"),
                    "e un fetch posterior tampouco pode traelas");
        }
    }

    /** Un clon superficial ten que seguir traendo o traballo dos demais. */
    @Test
    void laterChangesStillArriveOnTopOfAShallowDownload() throws Exception {
        String remote = createRemoteWithHistory(12);
        Path base = folderWithJarOnly();
        GitRepoService repo = new GitRepoService(base);
        repo.cloneRepo(remote, null);

        pushOneMoreCommit(remote, "{\"saudo\":\"traducido por outra persoa\"}");

        assertEquals(GitRepoService.PullOutcome.UPDATED, repo.pullIfSafe(null));
        assertEquals("{\"saudo\":\"traducido por outra persoa\"}",
                Files.readString(base.resolve("lang/chapter1/strings.json")));

        try (Git git = Git.open(base.toFile())) {
            assertFalse(git.getRepository().getObjectDatabase().getShallowCommits().isEmpty(),
                    "e segue sen baixar o historial");
        }
    }

    /**
     * O historial do servidor reescribiuse (purgáronse 200 MB de sprites, sons e
     * .zip que xa non estaban no proxecto). Os clons que xa existían quedan cun
     * historial que non é antepasado de nada, e iso saía como {@code DIVERGED}: o
     * tradutor deixaba de recibir traballo dos demais **para sempre**, sen erro
     * ningún. Sen nada pendente que perder, o clon adopta o historial novo.
     */
    @Test
    void aRewrittenServerHistoryIsAdoptedInsteadOfFreezingTheClone() throws Exception {
        String remote = createRemoteWithHistory(4);
        Path base = folderWithJarOnly();
        GitRepoService repo = new GitRepoService(base);
        repo.cloneRepo(remote, null);

        rewriteRemoteHistory(remote, "{\"saudo\":\"historial novo\"}");

        assertEquals(GitRepoService.PullOutcome.UPDATED, repo.pullIfSafe(null));
        assertEquals("{\"saudo\":\"historial novo\"}",
                Files.readString(base.resolve("lang/chapter1/strings.json")));
    }

    /** Pero se hai traballo sen subir, non se pisa: reconcíliase coma sempre. */
    @Test
    void pendingWorkStillBlocksAdoptingARewrittenHistory() throws Exception {
        String remote = createRemoteWithHistory(4);
        Path base = folderWithJarOnly();
        GitRepoService repo = new GitRepoService(base);
        repo.cloneRepo(remote, null);

        EditLedger ledger = EditLedger.openFor(base.resolve("lang/chapter1/strings.json"), base);
        ledger.record("saudo", "{\"saudo\":\"v3\"}", "traducido pero sen subir");

        rewriteRemoteHistory(remote, "{\"saudo\":\"historial novo\"}");

        assertEquals(GitRepoService.PullOutcome.DIVERGED, repo.pullIfSafe(null));
    }

    /**
     * O botón «descargar proxecto de tradución» e o arranque automático teñen que
     * ir ao mesmo sitio. Non o facían: cando o proxecto pasou á organización,
     * {@code DEFAULT_REMOTE} quedou apuntando á conta persoal, así que descargar a
     * man fallaba (404 -> «invalid remote: origin») mentres o arranque funcionaba.
     */
    @Test
    void theButtonAndTheAutomaticStartupPointAtTheSameRepository() {
        assertEquals(com.gui.RepoBootstrap.DEFAULT_REPO_URL, GitRepoService.DEFAULT_REMOTE);
        assertTrue(GitRepoService.DEFAULT_REMOTE.contains("Deltarune-en-Galego"),
                GitRepoService.DEFAULT_REMOTE);
    }

    @Test
    void aRepositoryThatIsNotThereSaysSoInsteadOfTalkingAboutRemotes() throws Exception {
        Path base = folderWithJarOnly();
        String missing = tmp.resolve("non-existe.git").toUri().toString();

        IOException e = assertThrows(IOException.class,
                () -> new GitRepoService(base).cloneRepo(missing, null));

        assertTrue(e.getMessage().contains("non ten acceso"), e.getMessage());
        assertFalse(e.getMessage().contains("invalid remote"), e.getMessage());
    }

    @Test
    void aTrulyEmptyFolderStillGoesThroughAPlainClone() throws Exception {
        String remote = createRemote();
        Path base = tmp.resolve("baleira");
        Files.createDirectories(base);

        new GitRepoService(base).cloneRepo(remote, null);

        assertTrue(Files.isRegularFile(base.resolve("lang/chapter1/strings.json")));
        assertFalse(Files.exists(base.resolve("amanuensis.jar")));
    }
}
