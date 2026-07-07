package com.gui;

import com.git.GitHubAuth;
import com.git.GitHubSession;
import com.git.GitRepoService;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Optional;

/**
 * Lóxica compartida de sincronización para os puntos de pull SEN editor aberto
 * (pantalla inicial e pull periódico): se hai cambios locais sen subir e o
 * remoto tamén avanzou, pregunta antes de subir en vez de pullear en silencio.
 * O editor (LocalView) ten a súa propia variante porque manexa a copia de traballo.
 */
final class GitSync {

    private GitSync() {
    }

    /** True se hai cambios trackeados locais sen subir E o remoto avanzou. Chamar en 2º plano (fai fetch). */
    static boolean divergesFromRemote(GitRepoService repo, String token) {
        try {
            return repo.hasTrackedChanges() && repo.remoteHasNewCommits(token);
        } catch (Exception e) {
            return false;
        }
    }

    /** Mensaxe estándar cando o remoto tamén avanzou (haberá que reconciliar). */
    static final String MSG_DIVERGED =
            "Atopáronse cambios sen subir e o servidor tamén ten cambios novos. Subir estos cambios?";
    /** Mensaxe cando só hai cambios locais (o remoto non trae nada novo). */
    static final String MSG_LOCAL_ONLY =
            "Tes cambios sen subir. Subilos agora?";

    /** Diálogo de confirmación (fío de UI). Se acepta, sobe todos os cambios en 2º plano. */
    static void confirmAndUpload(Stage owner, GitRepoService repo, GitHubSession session,
                                 String message, Runnable onUploaded) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        a.setTitle("Cambios sen subir");
        a.setHeaderText(null);
        if (owner != null) a.initOwner(owner);
        Optional<ButtonType> res = a.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.YES) {
            ProgressDialog dlg = new ProgressDialog(owner, "Subindo cambios",
                    "Subindo os teus cambios ao servidor...");
            dlg.show();
            uploadAll(owner, repo, session, dlg, onUploaded);
        }
    }

    private static void uploadAll(Stage owner, GitRepoService repo, GitHubSession session,
                                  ProgressDialog dlg, Runnable onUploaded) {
        new Thread(() -> {
            // A autoría debe ser sempre a do usuario real: pedila se non a temos.
            GitHubAuth.GitHubUser user = session.getUser();
            if (user == null) {
                try {
                    user = GitHubAuth.fetchUser(session.getToken());
                    session.setUser(user);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        dlg.close();
                        info(owner, "Non se puido verificar a túa conta de GitHub; téntao de novo.");
                    });
                    return;
                }
            }
            GitRepoService.PushOutcome outcome = repo.commitAndPushAllDirty(
                    "Actualización de tradución", user.name(), user.noreplyEmail(), session.getToken());
            Platform.runLater(() -> {
                dlg.close();
                showOutcome(owner, outcome);
                if (onUploaded != null) onUploaded.run();
            });
        }, "git-auto-upload").start();
    }

    private static void showOutcome(Stage owner, GitRepoService.PushOutcome outcome) {
        if (outcome instanceof GitRepoService.PushOutcome.Success) {
            info(owner, "Os teus cambios subíronse correctamente.");
        } else if (outcome instanceof GitRepoService.PushOutcome.Conflict c) {
            String msg = "Algúns cambios entraron en conflito con edicións recentes (" + c.lineRanges()
                    + "). Gardáronse nunha proposta de fusión e non se perderon.";
            if (c.prUrl() != null) {
                msg += "\n" + c.prUrl();
                openInBrowser(c.prUrl());
            }
            info(owner, msg);
        } else if (outcome instanceof GitRepoService.PushOutcome.Failure f) {
            info(owner, "Erro ao subir: " + f.reason());
        }
    }

    private static void info(Stage owner, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        a.setHeaderText(null);
        if (owner != null) a.initOwner(owner);
        a.show();
    }

    // Abrir URL no navegador nun proceso á parte (evitar java.awt.Desktop, que conxela en Linux).
    static void openInBrowser(String url) {
        new Thread(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            try {
                if (os.contains("win")) {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }
            } catch (IOException ignored) {
                // sen abridor: a URL amósase igual no diálogo
            }
        }, "open-browser").start();
    }
}
