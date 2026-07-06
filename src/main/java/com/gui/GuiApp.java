package com.gui;

import com.git.GitHubSession;
import com.git.GitRepoService;

import javafx.application.Application;
import javafx.stage.Stage;

import java.nio.file.Path;
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

    @Override
    public void start(Stage stage) {
        stage.setTitle("amanuensis");

        gitScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-auto-pull");
            t.setDaemon(true);
            return t;
        });
        gitScheduler.scheduleWithFixedDelay(this::autoPullIfPossible, 5, 5, TimeUnit.MINUTES);

        new MainView(stage).show();
    }

    // pull periódico en segundo plano: nunca toca un ficheiro trackeado con
    // cambios sen subir (ver GitRepoService.pullIfSafe).
    private void autoPullIfPossible() {
        GitHubSession session = GitHubSession.getInstance();
        if (!session.isLoggedIn()) return;

        GitRepoService repo = new GitRepoService(Path.of("lang"));
        if (!repo.isCloned()) return;

        repo.pullIfSafe(session.getToken());
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
