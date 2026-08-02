package com.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import com.AppDir;
import com.git.GitRepoService;
import com.update.AppVersion;
import com.update.UpdateService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Actualización automática da aplicación, vista dende a interface.
 *
 * O tradutor non ten que saber que existe GitHub: a app mira soa se hai unha
 * versión nova, pregunta, descarga e reiníciase.
 *
 * <h2>Cando NON se pregunta</h2>
 * Nunca mentres haxa texto sen gardar no editor. Reiniciar é seguro para o
 * traballo xa gardado — os rexistros de edicións viven fóra do repositorio, en
 * {@code ~/.amanuensis/ledgers/}, e sobreviven a calquera reinicio — pero o que
 * estea a medio escribir na caixa de edición perderíase. Por iso
 * {@link #setBusyEditing} deixa que {@link LocalView} bloquee o aviso, e este
 * volve saír só quando a caixa queda limpa.
 *
 * Tampouco se pregunta dúas veces pola mesma versión na mesma sesión, para que a
 * comprobación periódica non se converta nunha molestia.
 */
public final class UpdateUi {

    /** Cada canto se mira se hai versión nova (a comprobación é un GET pequeno). */
    private static final long CHECK_MINUTES = 30;

    private static volatile BooleanSupplier busyEditing = () -> false;
    private static volatile String declinedVersion;
    private static volatile boolean dialogOpen;
    private static ScheduledExecutorService scheduler;

    private UpdateUi() {
    }

    /**
     * Rexistra unha condición que impide amosar o aviso (texto sen gardar no
     * editor). {@link LocalView} chámaa ao abrir e pásalle {@code null} ao pechar.
     */
    public static void setBusyEditing(BooleanSupplier busy) {
        busyEditing = busy == null ? () -> false : busy;
    }

    /**
     * Arranca a comprobación periódica. Faise nun fío propio en segundo plano e
     * nunca bloquea a interface; se non hai rede, simplemente non atopa nada.
     */
    public static void start(Stage stage) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "update-check");
            t.setDaemon(true);
            return t;
        });
        // a primeira comprobación tarda un pouco: que a app abra canto antes
        scheduler.scheduleWithFixedDelay(() -> checkInBackground(stage),
                20, CHECK_MINUTES * 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Comprobación manual (botón «buscar actualizacións»). */
    public static void checkNow(Stage stage, Runnable ifNothing) {
        Thread t = new Thread(() -> {
            UpdateService.Available available = look();
            Platform.runLater(() -> {
                if (available == null) {
                    if (ifNothing != null) {
                        ifNothing.run();
                    }
                } else {
                    prompt(stage, available);
                }
            });
        }, "update-check-manual");
        t.setDaemon(true);
        t.start();
    }

    private static void checkInBackground(Stage stage) {
        UpdateService.Available available = look();
        if (available == null || available.version().equals(declinedVersion)) {
            return;
        }
        Platform.runLater(() -> {
            if (dialogOpen || busyEditing.getAsBoolean()) {
                return; // xa se volverá preguntar no seguinte ciclo
            }
            prompt(stage, available);
        });
    }

    /** Busca sen tocar a interface. Devolve null se non hai nada que facer. */
    private static UpdateService.Available look() {
        try {
            if (AppDir.runningJar() == null) {
                return null; // en desenvolvemento non hai jar que substituír
            }
            GitRepoService repo = new GitRepoService(AppDir.base());
            if (!repo.isCloned()) {
                return null;
            }
            return UpdateService.check(repo.originUrl(), repo.currentBranch());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // diálogo
    // ---------------------------------------------------------------

    private static void prompt(Stage owner, UpdateService.Available available) {
        if (dialogOpen) {
            return;
        }
        dialogOpen = true;

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("actualización dispoñible");

        Label header = new Label("Hai unha versión nova de Amanuensis: "
                + available.version() + "\n(tes a " + AppVersion.current() + ")");
        header.setWrapText(true);

        VBox box = new VBox(12, header);
        if (available.notes() != null && !available.notes().isBlank()) {
            Label notes = new Label(available.notes());
            notes.setWrapText(true);
            box.getChildren().add(notes);
        }

        Label detail = new Label("Descárgase e a aplicación reiníciase soa. "
                + "O traballo gardado non se perde.");
        detail.setWrapText(true);
        box.getChildren().add(detail);

        Button yes = new Button("actualizar e reiniciar");
        Button no = new Button("máis tarde");
        yes.setDefaultButton(true);
        no.setCancelButton(true);

        yes.setOnAction(e -> {
            dialog.close();
            dialogOpen = false;
            downloadAndApply(owner, available);
        });
        no.setOnAction(e -> {
            declinedVersion = available.version(); // non insistir con esta versión
            dialog.close();
            dialogOpen = false;
        });
        dialog.setOnCloseRequest(e -> {
            declinedVersion = available.version();
            dialogOpen = false;
        });

        box.getChildren().add(new HBox(8, yes, no));
        box.setPadding(new Insets(18));
        dialog.setScene(new Scene(box, 420, 220));
        dialog.show();
    }

    private static void downloadAndApply(Stage owner, UpdateService.Available available) {
        Path base = AppDir.base();
        Path target = AppDir.runningJar();
        if (target == null) {
            return;
        }

        ProgressDialog progress = new ProgressDialog(owner, "actualizando",
                "descargando a versión " + available.version() + "…");
        progress.show();

        Thread t = new Thread(() -> {
            try {
                Path staged = UpdateService.download(available.artifact(), base,
                        (done, total) -> {
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            Platform.runLater(() -> progress.setMessage(
                                    "descargando a versión " + available.version()
                                            + "…  " + pct + "%"));
                        });

                Platform.runLater(() -> {
                    progress.setMessage("instalando e reiniciando…");
                    try {
                        // a partir de aquí xa non se pode volver atrás: o instalador
                        // está agardando a que esta JVM morra para tocar o ficheiro
                        UpdateService.applyAndRestart(staged, target);
                        Platform.exit();
                        // Platform.exit non mata os fíos non-daemon que poida haber
                        Runtime.getRuntime().halt(0);
                    } catch (IOException e) {
                        progress.close();
                        error(owner, "Non se puido lanzar o instalador:\n" + e.getMessage()
                                + "\n\nA versión que tes segue funcionando.");
                    }
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    progress.close();
                    error(owner, "Non se puido descargar a actualización:\n" + e.getMessage()
                            + "\n\nA versión que tes segue funcionando.");
                });
            }
        }, "update-download");
        t.setDaemon(true);
        t.start();
    }

    private static void error(Stage owner, String message) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("actualización");

        Label label = new Label(message);
        label.setWrapText(true);
        Button ok = new Button("de acordo");
        ok.setDefaultButton(true);
        ok.setOnAction(e -> dialog.close());

        VBox box = new VBox(14, label, ok);
        box.setPadding(new Insets(18));
        dialog.setScene(new Scene(box, 400, 190));
        dialog.show();
    }
}
