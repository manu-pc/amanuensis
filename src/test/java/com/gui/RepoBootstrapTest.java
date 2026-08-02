package com.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A parte de {@link RepoBootstrap} que se pode probar sen rede: cando é seguro
 * clonar nunha carpeta.
 */
class RepoBootstrapTest {

    @TempDir
    Path work;

    @Test
    void anEmptyFolderIsSafeToCloneInto() {
        assertTrue(RepoBootstrap.isEmptyEnoughToClone(work));
    }

    @Test
    void leftoversFromTheAppItselfDoNotBlockTheClone() throws IOException {
        Files.createDirectories(work.resolve(".amanuensis-update"));
        Files.writeString(work.resolve("amanuensis-crash.log"), "erro");
        Files.writeString(work.resolve("amanuensis.jar.bak"), "versión anterior");
        Files.createDirectories(work.resolve(".config"));
        assertTrue(RepoBootstrap.isEmptyEnoughToClone(work),
                "o que deixa a propia app non conta como «carpeta ocupada»");
    }

    @Test
    void aFolderWithSomebodyElsesFilesIsNeverClonedInto() throws IOException {
        Files.writeString(work.resolve("as-minhas-fotos.txt"), "non me borres");
        assertFalse(RepoBootstrap.isEmptyEnoughToClone(work),
                "clonar aquí mesturaría o repositorio cos ficheiros do usuario");
    }

    @Test
    void theTranslationRepoIsTheOrgOneNotTheOldRedirect() {
        assertTrue(RepoBootstrap.DEFAULT_REPO_URL.contains("Deltarune-en-Galego"),
                RepoBootstrap.DEFAULT_REPO_URL);
    }
}
