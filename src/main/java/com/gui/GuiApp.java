package com.gui;

import com.AppDir;
import com.git.GitHubSession;
import com.git.GitRepoService;

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

        new MainView(stage).show();
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
        repo.pullIfSafe(session.getToken());
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
    }

    /** Lanzado desde com.Main. */
    public static void launchApp(String[] args) {
        launch(args);
    }
}
