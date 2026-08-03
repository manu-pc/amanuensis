package com.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
