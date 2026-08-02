package com.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.AppDir;
import com.git.GitRepoService;
import com.local.LedgerStore;

/**
 * Deixa a carpeta de traballo no estado correcto ao arrancar.
 *
 * O tradutor baixa un só ficheiro (o jar) e execútao onde queira. A partir de aí
 * non ten que tocar git nin GitHub para nada:
 * <ul>
 * <li><b>carpeta baleira</b> → clónase o repositorio de tradución enteiro;</li>
 * <li><b>carpeta cun clon</b> → póñese ao día e bórranse os restos que xa non
 * pintan nada (o SDK de JavaFX que se trackeaba, os jars vellos, as copias
 * {@code .copy.json} do sistema antigo).</li>
 * </ul>
 *
 * <h2>O traballo sen subir mándase primeiro</h2>
 * Poñerse ao día nunca pisa traducións: se hai edicións pendentes no rexistro,
 * primeiro se soben ({@link GitSync}) e só despois se avanza. Por iso
 * {@link #repair} devolve {@link Outcome#PENDING_UPLOAD} en vez de tirar para
 * diante: quen chama enséñao e deixa que o fluxo normal de subida faga o seu
 * traballo.
 *
 * <h2>Que se borra e que non</h2>
 * Só se borra o que está na lista de {@link #OBSOLETE} <b>e</b> xa non existe no
 * commit actual: así, o día que un deses ficheiros volva ao repositorio, deixa de
 * borrarse só. E nunca se toca o jar en execución, que adoita ser precisamente
 * {@code amanuensis.jar} na raíz: substituílo é traballo de
 * {@link com.update.UpdateApplier}, non deste código.
 */
public final class RepoBootstrap {

    /**
     * Repositorio de tradución que se clona cando a carpeta está baleira.
     *
     * É <b>privado</b>, así que clonar precisa o token da sesión: por iso o
     * arranque non pode montar todo el só antes de iniciar sesión, e devolve
     * {@link Outcome#NEEDS_LOGIN} para que a interface pida a sesión e volva
     * chamar aquí en canto a teña.
     */
    public static final String DEFAULT_REPO_URL =
            "https://github.com/Deltarune-en-Galego/deltarune-en-galego-DEV";

    /**
     * Restos de etapas anteriores do proxecto. Bórranse só se xa non están no
     * commit actual, así que esta lista pode quedar aquí para sempre sen risco.
     */
    private static final List<String> OBSOLETE = List.of(
            "lib",                      // SDK de JavaFX trackeado: o fat-jar xa trae os nativos
            "amanuensis.jar",           // os jars publícanse como release, non no repositorio
            "amanuensis-windows.jar");

    public enum Outcome {
        /** Non había clon e clonouse. */
        CLONED,
        /** Púxose ao día (ou xa o estaba). */
        SYNCED,
        /** Hai edicións sen subir: hai que subilas antes de avanzar. */
        PENDING_UPLOAD,
        /** A carpeta ten cousas pero non é un clon: non se toca nada. */
        NOT_A_CLONE,
        /** Hai que clonar pero aínda non hai sesión (o repositorio é privado). */
        NEEDS_LOGIN,
        /** Non se puido falar co remoto. */
        OFFLINE
    }

    /** Que pasou, para poder contarllo ao usuario. */
    public record Report(Outcome outcome, List<String> removed, String detail) {
    }

    private RepoBootstrap() {
    }

    /**
     * Comproba e arranxa a carpeta base. Non fai nada que poida perder traballo.
     *
     * @param token token de GitHub, ou null. Sen el pódese poñer ao día un clon
     *              que xa exista, pero non clonar de cero: o repositorio é privado
     */
    public static Report repair(String token) {
        Path base = AppDir.base();
        GitRepoService repo = new GitRepoService(base);

        if (!repo.isCloned()) {
            if (!isEmptyEnoughToClone(base)) {
                return new Report(Outcome.NOT_A_CLONE, List.of(),
                        "a carpeta ten ficheiros pero non é un clon do repositorio");
            }
            if (token == null || token.isBlank()) {
                return new Report(Outcome.NEEDS_LOGIN, List.of(),
                        "o repositorio de tradución é privado: fai falta iniciar sesión");
            }
            try {
                repo.cloneRepo(DEFAULT_REPO_URL, token);
                return new Report(Outcome.CLONED, List.of(), DEFAULT_REPO_URL);
            } catch (Exception e) {
                return new Report(Outcome.OFFLINE, List.of(),
                        "non se puido clonar: " + e.getMessage());
            }
        }

        // hai traballo sen subir: primeiro sóbese, despois xa se avanzará
        if (LedgerStore.hasPendingEdits(base)) {
            return new Report(Outcome.PENDING_UPLOAD, List.of(),
                    LedgerStore.pendingCount(base) + " liñas sen subir");
        }

        GitRepoService.PullOutcome pull = repo.pullIfSafe(token);
        if (pull == GitRepoService.PullOutcome.FAILED) {
            return new Report(Outcome.OFFLINE, List.of(), "non se puido contactar co remoto");
        }

        List<String> removed = removeObsolete(base, repo);
        return new Report(Outcome.SYNCED, removed, pull.name());
    }

    /**
     * Unha carpeta serve para clonar se non ten nada dentro agás o propio jar (e o
     * que este deixa ao seu carón). Así, deixar o jar nunha carpeta baleira e
     * facer dobre clic monta todo só, pero nunca se clona enriba dos ficheiros
     * doutra cousa.
     */
    static boolean isEmptyEnoughToClone(Path base) {
        Path jar = AppDir.runningJar();
        try (var stream = Files.list(base)) {
            return stream.noneMatch(p -> {
                String name = p.getFileName().toString();
                if (jar != null && p.equals(jar)) {
                    return false;
                }
                return !name.startsWith(".")
                        && !name.equals(com.update.UpdateService.WORK_DIR)
                        && !name.equals("amanuensis-crash.log")
                        && !name.endsWith(".bak");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Borra os restos obsoletos e as copias de traballo do sistema antigo.
     *
     * @return rutas borradas, para poder dicilo
     */
    static List<String> removeObsolete(Path base, GitRepoService repo) {
        List<String> removed = new ArrayList<>();
        Path runningJar = AppDir.runningJar();

        for (String name : OBSOLETE) {
            Path p = base.resolve(name);
            if (!Files.exists(p)) {
                continue;
            }
            if (runningJar != null && p.toAbsolutePath().equals(runningJar)) {
                continue; // o jar en execución substitúeo o actualizador, non isto
            }
            if (repo.existsInHead(name)) {
                continue; // volveu ao repositorio: xa non é un resto
            }
            if (deleteRecursively(p)) {
                removed.add(name);
            }
        }

        removed.addAll(removeStaleCopies(AppDir.lang()));
        return removed;
    }

    /** As copias {@code *.copy*.json} que deixaba o sistema anterior ao rexistro. */
    private static List<String> removeStaleCopies(Path langDir) {
        List<String> removed = new ArrayList<>();
        if (!Files.isDirectory(langDir)) {
            return removed;
        }
        try (var stream = Files.walk(langDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (name.contains(".copy") && name.endsWith(".json") && deleteRecursively(p)) {
                    removed.add(langDir.getParent().relativize(p).toString());
                }
            }
        } catch (IOException ignored) {
            // sen permisos ou carpeta cambiando por debaixo: non é crítico
        }
        return removed;
    }

    private static boolean deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    // fillos antes ca pais
                    for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
            return !Files.exists(path);
        } catch (IOException e) {
            return false;
        }
    }
}
