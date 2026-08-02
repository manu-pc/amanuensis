package com.gui;

import com.AppDir;
import com.git.GitHubSession;
import com.git.GitRepoService;
import com.update.UpdateService;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Punto de entrada de JavaFX. Só crea o Stage principal e mostra a
 * pantalla inicial (MainView), que á súa vez abre o editor (LocalView).
 *
 * Nota: esta clase é a única que estende Application. O verdadeiro main
 * (com.Main) NON a estende, para que o fat-jar arranque sen o erro
 * "JavaFX runtime components are missing".
 */
public class GuiApp extends Application {

    private ScheduledExecutorService gitScheduler;
    private Stage stage;
    private volatile boolean syncPromptPending;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("amanuensis");
        loadAppIcons(stage);

        gitScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-auto-pull");
            t.setDaemon(true);
            return t;
        });
        gitScheduler.scheduleWithFixedDelay(this::autoPullIfPossible, 5, 5, TimeUnit.MINUTES);

        // restos dunha actualización anterior (descarga a medias, jar xa instalado):
        // bórranse aquí, cando xa non hai ninguén usándoos
        UpdateService.cleanWorkDir(AppDir.base());
        // busca de versións novas da propia app; non precisa login nin repo escrito
        UpdateUi.start(stage);

        new MainView(stage).show();

        // deixar a carpeta no estado correcto (clonar se está baleira, poñerse ao
        // día e limpar restos vellos se xa hai clon). Vai despois de amosar a
        // pantalla e nun fío aparte porque pode ter que clonar 60 MB.
        bootstrapRepo();
    }

    /**
     * Posta a punto da carpeta ao arrancar (ver {@link RepoBootstrap}).
     *
     * Se hai traballo sen subir non se avanza: ofrécese subilo co fluxo de sempre,
     * que é o único que sabe reconciliar clave a clave. Se se clonou de cero,
     * recárgase a pantalla inicial para que apareza a lista de ficheiros.
     */
    private void bootstrapRepo() {
        Thread t = new Thread(() -> {
            GitHubSession session = GitHubSession.getInstance();
            RepoBootstrap.Report report = RepoBootstrap.repair(session.getToken());
            Platform.runLater(() -> {
                switch (report.outcome()) {
                    case CLONED -> new MainView(stage).show();
                    case PENDING_UPLOAD -> {
                        if (session.isLoggedIn() && !syncPromptPending) {
                            syncPromptPending = true;
                            try {
                                GitRepoService repo = new GitRepoService(AppDir.base());
                                GitSync.confirmAndUpload(stage, repo, session,
                                        GitSync.MSG_LOCAL_ONLY, null);
                            } finally {
                                syncPromptPending = false;
                            }
                        }
                    }
                    case SYNCED -> {
                        if (!report.removed().isEmpty()) {
                            new MainView(stage).show(); // a lista pode ter cambiado
                        }
                    }
                    case NEEDS_LOGIN -> {
                        // a carpeta está baleira e o repositorio é privado: en canto
                        // haxa sesión, clónase sen que o tradutor teña que pulsar nada
                        MainView.onLogin(this::bootstrapRepo);
                    }
                    default -> {
                        // NOT_A_CLONE / OFFLINE: séguese traballando co que haxa
                    }
                }
            });
        }, "repo-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    // pull periódico en segundo plano. Se hai cambios locais sen subir E o remoto
    // avanzou, pregunta antes de subir; se non, fai un pull seguro (nunca toca un
    // ficheiro trackeado con cambios sen subir, ver GitRepoService.pullIfSafe).
    private void autoPullIfPossible() {
        GitHubSession session = GitHubSession.getInstance();
        if (!session.isLoggedIn()) return;

        // O repositorio git é a carpeta base (a do jar), que contén .git e lang/.
        GitRepoService repo = new GitRepoService(AppDir.base());
        if (!repo.isCloned()) return;

        if (GitSync.divergesFromRemote(repo, session.getToken())) {
            if (syncPromptPending) return; // non amontoar diálogos entre ticks
            syncPromptPending = true;
            Platform.runLater(() -> {
                try {
                    GitSync.confirmAndUpload(stage, repo, session, GitSync.MSG_DIVERGED, null);
                } finally {
                    syncPromptPending = false;
                }
            });
            return;
        }
        // Un pull seguro (só fast-forward). Se o local diverxe (commits sen subir +
        // remoto avanzado), pregunta antes de reconciliar e subir, coma no caso dirty.
        if (repo.pullIfSafe(session.getToken()) == GitRepoService.PullOutcome.DIVERGED) {
            if (syncPromptPending) return;
            syncPromptPending = true;
            Platform.runLater(() -> {
                try {
                    GitSync.confirmAndUpload(stage, repo, session, GitSync.MSG_DIVERGED, null);
                } finally {
                    syncPromptPending = false;
                }
            });
        }
    }

    // Icona da app: varios tamaños empaquetados no jar; JavaFX escolle o mellor
    // para a barra de tarefas/xanela. Todas as xanelas comparten este Stage.
    private void loadAppIcons(Stage stage) {
        for (int size : new int[]{16, 32, 64, 128, 256}) {
            try (InputStream in = getClass().getResourceAsStream("/icons/logo-" + size + ".png")) {
                if (in != null) stage.getIcons().add(new Image(in));
            } catch (Exception ignored) {
                // sen icona nese tamaño: JavaFX usa os que si carguen
            }
        }
    }

    @Override
    public void stop() {
        if (gitScheduler != null) gitScheduler.shutdownNow();
        UpdateUi.stop();
    }

    /** Lanzado desde com.Main. */
    public static void launchApp(String[] args) {
        launch(args);
    }
}
