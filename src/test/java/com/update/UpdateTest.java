package com.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {

    @TempDir
    Path work;

    // ---------------------------------------------------------------
    // versións
    // ---------------------------------------------------------------

    @Test
    void versionsCompareByNumberNotByText() {
        assertTrue(AppVersion.compare("1.10.0", "1.9.0") > 0, "10 é maior ca 9");
        assertTrue(AppVersion.compare("2.0.0", "1.99.99") > 0);
        assertEquals(0, AppVersion.compare("1.2", "1.2.0"), "os ocos valen 0");
    }

    @Test
    void aQualifierIsEarlierThanTheCleanVersion() {
        assertTrue(AppVersion.compare("1.2.0", "1.2.0-SNAPSHOT") > 0);
        assertTrue(AppVersion.compare("1.2.0", "1.2.0-rc1") > 0);
        assertTrue(AppVersion.compare("1.2.0-rc2", "1.2.0-rc1") > 0);
    }

    @Test
    void aDevelopmentBuildNeverUpdatesAndIsNeverOffered() {
        assertFalse(AppVersion.isNewer("9.9.9", AppVersion.DEV),
                "a máquina que compila non se ofrece a instalar o que acaba de subir");
        assertFalse(AppVersion.isNewer(AppVersion.DEV, "1.0.0"));
        assertTrue(AppVersion.isDev(null));
        assertTrue(AppVersion.isDev(""));
    }

    @Test
    void onlyAStrictlyNewerVersionCounts() {
        assertTrue(AppVersion.isNewer("1.2.1", "1.2.0"));
        assertFalse(AppVersion.isNewer("1.2.0", "1.2.0"));
        assertFalse(AppVersion.isNewer("1.1.0", "1.2.0"), "nunca se ofrece unha versión anterior");
    }

    // ---------------------------------------------------------------
    // manifesto
    // ---------------------------------------------------------------

    private static final String MANIFEST = """
            {
              "version": "1.2.0",
              "notes": "probas",
              "artifacts": {
                "linux":   {"file": "amanuensis.jar",
                            "url": "https://github.com/o/r/releases/download/v1.2.0/amanuensis.jar",
                            "sha256": "AABB", "size": 10},
                "windows": {"file": "amanuensis-windows.jar",
                            "url": "https://github.com/o/r/releases/download/v1.2.0/amanuensis-windows.jar",
                            "sha256": "ccdd", "size": 20}
              }
            }
            """;

    @Test
    void manifestParsesAndNormalisesTheHash() {
        UpdateManifest m = UpdateManifest.parse(MANIFEST);
        assertNotNull(m);
        assertEquals("1.2.0", m.version());
        assertEquals("probas", m.notes());
        assertEquals(2, m.artifacts().size());
        assertEquals("aabb", m.artifacts().get("linux").sha256(), "o hash compárase en minúsculas");
        assertEquals(20, m.artifacts().get("windows").size());
    }

    @Test
    void anArtifactWithoutHashOrSizeIsDiscarded() {
        UpdateManifest m = UpdateManifest.parse("""
                {"version": "1.2.0", "artifacts": {
                   "linux": {"file": "a.jar", "url": "https://github.com/x", "size": 10},
                   "windows": {"file": "b.jar", "url": "https://github.com/y", "sha256": "aa", "size": 0}
                }}
                """);
        assertNotNull(m);
        assertTrue(m.artifacts().isEmpty(), "sen o que verificar non se instala nada");
    }

    @Test
    void brokenJsonGivesNullInsteadOfThrowing() {
        assertNull(UpdateManifest.parse("{isto non é json"));
        assertNull(UpdateManifest.parse("{\"notes\": \"sen versión\"}"));
    }

    @Test
    void manifestComesFromThePublicCodeRepoNotThePrivateTranslationOne() {
        // o repositorio de tradución é privado: raw.githubusercontent devolve 404
        // sen token, así que a app búscase a si mesma no repositorio público
        assertEquals("https://raw.githubusercontent.com/manu-pc/amanuensis/master/update.json",
                UpdateService.manifestUrl());
    }

    @Test
    void manifestUrlCanAlsoBeDerivedFromARemote() {
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/update.json",
                UpdateService.manifestUrl("https://github.com/owner/repo.git", "main"));
        assertEquals("https://raw.githubusercontent.com/owner/repo/master/update.json",
                UpdateService.manifestUrl("git@github.com:owner/repo.git", "master"));
        assertNull(UpdateService.manifestUrl("https://gitlab.com/owner/repo.git", "main"));
    }

    // ---------------------------------------------------------------
    // descarga
    // ---------------------------------------------------------------

    @Test
    void aDownloadFromOutsideGitHubIsRefused() {
        UpdateManifest.Artifact evil = new UpdateManifest.Artifact("linux", "amanuensis.jar",
                "https://exemplo.invalido/amanuensis.jar", "aa", 10);
        IOException e = assertThrows(IOException.class,
                () -> UpdateService.download(evil, work, null));
        assertTrue(e.getMessage().contains("GitHub"), e.getMessage());
        assertFalse(Files.exists(UpdateService.workDir(work)),
                "nin sequera se crea a carpeta de traballo");
    }

    @Test
    void sha256MatchesTheReferenceValue() throws IOException {
        Path f = work.resolve("x.bin");
        Files.writeString(f, "abc", StandardCharsets.UTF_8);
        // SHA-256 coñecido de "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateService.sha256Of(f));
    }

    // ---------------------------------------------------------------
    // instalación
    // ---------------------------------------------------------------

    @Test
    void installReplacesTheJarAndKeepsABackup() throws IOException {
        Path old = work.resolve("amanuensis.jar");
        Path staged = work.resolve("amanuensis.jar.new");
        Files.writeString(old, "vella");
        Files.writeString(staged, "nova");

        UpdateApplier.install(staged, old);

        assertEquals("nova", Files.readString(old));
        assertEquals("vella", Files.readString(work.resolve("amanuensis.jar.bak")),
                "queda a versión anterior por se algo sae mal");
        assertFalse(Files.exists(staged), "a descarga bórrase despois de instalala");
    }

    @Test
    void installWorksWhenThereIsNothingToReplaceYet() throws IOException {
        Path target = work.resolve("amanuensis.jar");
        Path staged = work.resolve("amanuensis.jar.new");
        Files.writeString(staged, "nova");

        UpdateApplier.install(staged, target);

        assertEquals("nova", Files.readString(target));
    }

    @Test
    void waitingOnAProcessThatIsGoneReturnsImmediately() {
        assertTrue(UpdateApplier.waitForExit(-1), "sen pid non hai nada que agardar");
        // un pid que seguro que non existe: o rango de pids remata moito antes
        assertTrue(UpdateApplier.waitForExit(Integer.MAX_VALUE - 1L));
    }

    @Test
    void cleaningDropsStaleDownloadsButKeepsTheLog() throws IOException {
        UpdateService.cleanWorkDir(work); // aínda non existe: non debe fallar

        Path dir = UpdateService.workDir(work);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("amanuensis.jar.new"), "descarga a medias");
        Files.writeString(dir.resolve("applier.log"), "que pasou a última vez");

        UpdateService.cleanWorkDir(work);

        assertFalse(Files.exists(dir.resolve("amanuensis.jar.new")));
        assertTrue(Files.exists(dir.resolve("applier.log")),
                "o rexistro é a única pista se unha actualización falla");
    }

    @Test
    void theJavaBinaryIsResolvedNotAssumedToBeOnThePath() {
        String java = UpdateService.javaBinary();
        assertNotNull(java);
        assertFalse(java.isBlank());
        assertTrue(java.contains("java"), java);
    }
}
