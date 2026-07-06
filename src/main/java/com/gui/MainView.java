package com.gui;

import com.git.GitHubAuth;
import com.git.GitHubSession;
import com.git.GitRepoService;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pantalla inicial: pide (ou permite examinar) un ficheiro .json de localización.
 * Ao aceptar un ficheiro válido, dá paso ao editor (LocalView) na mesma xanela.
 *
 * Equivalente á antiga VentanaMain da TUI, agora cun FileChooser real.
 */
public class MainView {

    private final Stage stage;
    private Stage deviceDialog;

    public MainView(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        Label title = new Label("Introduce o nome do ficheiro (.json) ou examínao:");

        TextField input = new TextField();
        input.setPromptText("ex: dialogos_es.json");
        input.setPrefColumnCount(32);

        Button browse = new Button("examinar…");

        HBox inputRow = new HBox(8, input, browse);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);

        Label errorLabel = new Label("");
        errorLabel.setTextFill(Color.CRIMSON);
        errorLabel.setWrapText(true);

        Button accept = new Button("aceptar");
        accept.setDefaultButton(true);
        Button cancel = new Button("cancelar");
        HBox buttons = new HBox(8, accept, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        // ---- accións ----
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Escoller ficheiro de localización");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Ficheiros JSON", "*.json"));
            File chosen = fc.showOpenDialog(stage);
            if (chosen != null) {
                input.setText(chosen.getAbsolutePath());
            }
        });

        accept.setOnAction(e -> tryOpen(input.getText(), errorLabel));
        input.setOnAction(e -> tryOpen(input.getText(), errorLabel));
        cancel.setOnAction(e -> stage.close());

        HBox gitBar = buildGitBar();

        VBox root = new VBox(12, gitBar, title, inputRow, errorLabel);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER_LEFT);

        // ---- selector de lang/ (repositorio de tradución) ----
        Path langDir = Path.of("lang");
        GitRepoService gitRepo = new GitRepoService(langDir);
        boolean cloned = gitRepo.isCloned();
        boolean hasLang = cloned && Files.isDirectory(langDir);

        if (!cloned) {
            root.getChildren().addAll(buildCloneSection(gitRepo));
        } else if (hasLang) {
            Label langTitle = new Label("…ou escolle directamente un .json de lang/:");
            ListView<String> langList = new ListView<>(
                    FXCollections.observableArrayList(scanLangJsons(langDir)));
            langList.setPrefHeight(220);
            VBox.setVgrow(langList, Priority.ALWAYS);

            Runnable openSelected = () -> {
                String sel = langList.getSelectionModel().getSelectedItem();
                if (sel != null) tryOpen(sel, errorLabel);
            };
            langList.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2) openSelected.run();
            });
            Button openLang = new Button("abrir seleccionado");
            openLang.setOnAction(ev -> openSelected.run());

            Button pullNow = new Button("actualizar agora");
            Label syncLabel = new Label("");
            syncLabel.setTextFill(Color.TEAL);
            pullNow.setOnAction(ev -> doPull(gitRepo, langDir, langList, syncLabel, pullNow));
            HBox langButtons = new HBox(8, openLang, pullNow, syncLabel);
            langButtons.setAlignment(Pos.CENTER_LEFT);

            root.getChildren().addAll(langTitle, langList, langButtons);
            doPull(gitRepo, langDir, langList, syncLabel, pullNow);
        }

        root.getChildren().add(buttons);

        Scene scene = new Scene(root, 600, hasLang ? 480 : 260);
        stage.setScene(scene);
        stage.show();
        input.requestFocus();
    }

    // ---------------------------------------------------------------
    // integración con GitHub
    // ---------------------------------------------------------------

    private HBox buildGitBar() {
        Label statusLabel = new Label("");
        statusLabel.setTextFill(Color.TEAL);
        Button loginBtn = new Button();
        refreshLoginButton(loginBtn, statusLabel);
        loginBtn.setOnAction(e -> onLoginLogout(loginBtn, statusLabel));

        HBox bar = new HBox(10, loginBtn, statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void refreshLoginButton(Button btn, Label statusLabel) {
        GitHubSession session = GitHubSession.getInstance();
        if (!session.isLoggedIn()) {
            btn.setText("iniciar sesión en GitHub");
            return;
        }
        if (session.getUser() == null) {
            btn.setText("conectado (cargando...)");
            new Thread(() -> {
                try {
                    GitHubAuth.GitHubUser user = GitHubAuth.fetchUser(session.getToken());
                    session.setUser(user);
                    Platform.runLater(() -> refreshLoginButton(btn, statusLabel));
                } catch (Exception ignored) {
                    // se falla, o botón quédase como "conectado (cargando...)"; a sesión segue válida
                }
            }, "github-user-fetch").start();
            return;
        }
        btn.setText("pechar sesión (" + session.getUser().login() + ")");
    }

    private void onLoginLogout(Button btn, Label statusLabel) {
        GitHubSession session = GitHubSession.getInstance();
        if (session.isLoggedIn()) {
            session.logout();
            refreshLoginButton(btn, statusLabel);
            statusLabel.setText("sesión pechada");
            return;
        }
        startDeviceLogin(btn, statusLabel);
    }

    private void startDeviceLogin(Button btn, Label statusLabel) {
        btn.setDisable(true);
        statusLabel.setText("iniciando sesión...");
        new Thread(() -> {
            try {
                GitHubAuth.DeviceCode code = GitHubAuth.requestDeviceCode();
                Platform.runLater(() -> showDeviceCodeDialog(code));
                String token = GitHubAuth.pollForToken(code);
                GitHubAuth.GitHubUser user = GitHubAuth.fetchUser(token);
                GitHubSession.getInstance().login(token, user);
                Platform.runLater(() -> {
                    closeDeviceDialogIfOpen();
                    refreshLoginButton(btn, statusLabel);
                    statusLabel.setText("conectado como " + user.login());
                    btn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    closeDeviceDialogIfOpen();
                    statusLabel.setText("erro no login: " + ex.getMessage());
                    btn.setDisable(false);
                });
            }
        }, "github-login").start();
    }

    private void showDeviceCodeDialog(GitHubAuth.DeviceCode code) {
        deviceDialog = new Stage();
        deviceDialog.initOwner(stage);
        deviceDialog.setTitle("Iniciar sesión en GitHub");

        Label info = new Label("Introduce este código en GitHub para conectar Amanuensis:");
        info.setWrapText(true);
        Label codeLabel = new Label(code.userCode());
        codeLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        Button openBtn = new Button("abrir GitHub");
        openBtn.setOnAction(e -> openInBrowser(code.verificationUri()));
        Button copyBtn = new Button("copiar código");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(code.userCode());
            Clipboard.getSystemClipboard().setContent(content);
        });

        Label urlLabel = new Label(code.verificationUri());
        urlLabel.setWrapText(true);
        Label waiting = new Label("agardando confirmación no navegador...");
        waiting.setTextFill(Color.TEAL);

        VBox box = new VBox(12, info, codeLabel, new HBox(8, openBtn, copyBtn), urlLabel, waiting);
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.CENTER_LEFT);

        deviceDialog.setScene(new Scene(box, 380, 240));
        deviceDialog.show();
    }

    // Non usar java.awt.Desktop: inicializa o toolkit AWT dende o fío de JavaFX
    // e en Linux isto pode conxelar a app enteira. Lanzar o abridor do sistema
    // como proceso á parte é máis seguro e non bloquea nada se falla.
    private void openInBrowser(String url) {
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
                // se non hai abridor dispoñible, o usuario pode ir manualmente á URL amosada
            }
        }, "open-browser").start();
    }

    private void closeDeviceDialogIfOpen() {
        if (deviceDialog != null) {
            deviceDialog.close();
            deviceDialog = null;
        }
    }

    private List<javafx.scene.Node> buildCloneSection(GitRepoService gitRepo) {
        Label cloneTitle = new Label("O proxecto de tradución aínda non está descargado nesta carpeta:");
        cloneTitle.setWrapText(true);
        Button cloneBtn = new Button("descargar proxecto de tradución");
        Label cloneStatus = new Label("");
        cloneStatus.setTextFill(Color.TEAL);

        cloneBtn.setOnAction(e -> {
            cloneBtn.setDisable(true);
            cloneStatus.setText("descargando...");
            new Thread(() -> {
                try {
                    String token = GitHubSession.getInstance().getToken();
                    gitRepo.cloneRepo(GitRepoService.DEFAULT_REMOTE, token);
                    Platform.runLater(this::show);
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        cloneStatus.setText("erro ao descargar: " + ex.getMessage());
                        cloneBtn.setDisable(false);
                    });
                }
            }, "git-clone").start();
        });

        HBox cloneBar = new HBox(8, cloneBtn, cloneStatus);
        cloneBar.setAlignment(Pos.CENTER_LEFT);
        return List.of(cloneTitle, cloneBar);
    }

    private void doPull(GitRepoService gitRepo, Path langDir, ListView<String> langList,
                         Label syncLabel, Button pullNow) {
        if (!GitHubSession.getInstance().isLoggedIn()) {
            syncLabel.setText("inicia sesión para actualizar automaticamente");
            return;
        }
        pullNow.setDisable(true);
        syncLabel.setText("actualizando...");
        new Thread(() -> {
            GitRepoService.PullOutcome outcome = gitRepo.pullIfSafe(GitHubSession.getInstance().getToken());
            Platform.runLater(() -> {
                pullNow.setDisable(false);
                syncLabel.setText(switch (outcome) {
                    case UP_TO_DATE -> "actualizado (sen cambios novos)";
                    case UPDATED -> "actualizado con cambios novos";
                    case SKIPPED_DIRTY -> "hai cambios sen subir; non se actualizou";
                    case FAILED -> "erro ao actualizar";
                });
                // se houbo cambios novos, a lista xa amosada quedara desactualizada
                if (outcome == GitRepoService.PullOutcome.UPDATED) {
                    langList.setItems(FXCollections.observableArrayList(scanLangJsons(langDir)));
                }
            });
        }, "git-autopull").start();
    }

    /**
     * Busca recursivamente todos os .json baixo lang/ (en calquera subcarpeta),
     * excluíndo as copias de traballo (*.copy*.json). Devolve rutas relativas
     * ordenadas, listas para abrir con tryOpen().
     */
    private List<String> scanLangJsons(Path langDir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(langDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    // Excluír copias de traballo e ficheiros de metadatos (non traducibles).
                    return name.endsWith(".json")
                            && !name.contains(".copy.")
                            && !name.equals("chapter_settings.json");
                })
                .map(Path::toString)
                .sorted()
                .forEach(out::add);
        } catch (IOException e) {
            // se non se pode percorrer a carpeta, devólvese o que se levase
        }
        return out;
    }

    private void tryOpen(String raw, Label errorLabel) {
        String filename = raw == null ? "" : raw.trim();
        if (filename.isEmpty()) {
            errorLabel.setText("o nome non pode estar baleiro");
            return;
        }
        if (!filename.toLowerCase().endsWith(".json")) {
            errorLabel.setText("o ficheiro debe ter extensión .json");
            return;
        }

        Path filePath = Path.of(filename);
        if (!Files.exists(filePath)) {
            errorLabel.setText("o ficheiro non existe: " + filename);
            return;
        }
        if (!Files.isReadable(filePath)) {
            errorLabel.setText("o ficheiro non se pode ler: " + filename);
            return;
        }

        errorLabel.setText("");
        try {
            LocalView local = new LocalView(filename, stage);
            local.show();
        } catch (IOException ex) {
            errorLabel.setText("erro de E/S: " + ex.getMessage());
        } catch (com.google.gson.JsonSyntaxException ex) {
            errorLabel.setText("JSON non válido: " + ex.getMessage());
        } catch (Exception ex) {
            errorLabel.setText("erro inesperado: " + ex.getClass().getSimpleName()
                    + " - " + ex.getMessage());
        }
    }
}
