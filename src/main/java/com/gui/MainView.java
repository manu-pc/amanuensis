package com.gui;

import com.AppDir;
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
import javafx.scene.control.ProgressBar;
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
    private Thread loginThread;
    private volatile boolean loginCancelled;

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
        // A carpeta base (a do jar) contén .git e a subcarpeta lang/: as operacións
        // git apuntan á base, pero o navegador de ficheiros lista lang/. Resólvese
        // dende a localización do jar (non o cwd) para funcionar tamén con dobre clic.
        Path repoRoot = AppDir.base();
        Path langDir = AppDir.lang();
        GitRepoService gitRepo = new GitRepoService(repoRoot);
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
                    // fallou a consulta do usuario (p.ex. sen rede). A sesión segue válida:
                    // amosar "conectado" en vez de deixar o botón preso en "cargando...".
                    Platform.runLater(() -> btn.setText("pechar sesión"));
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
        loginCancelled = false;
        statusLabel.setText("iniciando sesión...");
        loginThread = new Thread(() -> {
            try {
                GitHubAuth.DeviceCode code = GitHubAuth.requestDeviceCode();
                if (loginCancelled) return;
                Platform.runLater(() -> showDeviceCodeDialog(code, btn, statusLabel));
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
                    // se o usuario cancelou, o interrupt provoca unha excepción esperada: non é erro
                    if (!loginCancelled) statusLabel.setText("erro no login: " + ex.getMessage());
                    btn.setDisable(false);
                });
            }
        }, "github-login");
        loginThread.start();
    }

    /** Aborta un login en curso: pecha o diálogo e detén o sondeo a GitHub. */
    private void cancelLogin(Button btn, Label statusLabel) {
        loginCancelled = true;
        if (loginThread != null) loginThread.interrupt();
        closeDeviceDialogIfOpen();
        statusLabel.setText("login cancelado");
        btn.setDisable(false);
    }

    private void showDeviceCodeDialog(GitHubAuth.DeviceCode code, Button loginBtn, Label statusLabel) {
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
        Button cancelBtn = new Button("cancelar");
        cancelBtn.setOnAction(e -> cancelLogin(loginBtn, statusLabel));

        Label urlLabel = new Label(code.verificationUri());
        urlLabel.setWrapText(true);
        Label waiting = new Label("agardando confirmación no navegador...");
        waiting.setTextFill(Color.TEAL);

        VBox box = new VBox(12, info, codeLabel, new HBox(8, openBtn, copyBtn, cancelBtn), urlLabel, waiting);
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.CENTER_LEFT);

        // pechar a xanela (X) equivale a cancelar o login
        deviceDialog.setOnCloseRequest(e -> cancelLogin(loginBtn, statusLabel));
        deviceDialog.setScene(new Scene(box, 380, 260));
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
        ProgressBar cloneProgress = new ProgressBar(0);
        cloneProgress.setPrefWidth(220);
        cloneProgress.setVisible(false);

        cloneBtn.setOnAction(e -> {
            cloneBtn.setDisable(true);
            cloneProgress.setVisible(true);
            cloneProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            cloneStatus.setText("descargando...");
            new Thread(() -> {
                try {
                    String token = GitHubSession.getInstance().getToken();
                    gitRepo.cloneRepo(GitRepoService.DEFAULT_REMOTE, token, (task, pct) ->
                            Platform.runLater(() -> {
                                cloneProgress.setProgress(pct < 0
                                        ? ProgressBar.INDETERMINATE_PROGRESS : pct / 100.0);
                                cloneStatus.setText("descargando: " + task
                                        + (pct >= 0 ? " (" + pct + "%)" : ""));
                            }));
                    Platform.runLater(this::show);
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        cloneProgress.setVisible(false);
                        cloneStatus.setText("erro ao descargar: " + ex.getMessage());
                        cloneBtn.setDisable(false);
                    });
                }
            }, "git-clone").start();
        });

        HBox cloneBar = new HBox(8, cloneBtn, cloneProgress, cloneStatus);
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
        String token = GitHubSession.getInstance().getToken();
        Runnable rescan = () -> langList.setItems(FXCollections.observableArrayList(scanLangJsons(langDir)));
        new Thread(() -> {
            boolean dirty;
            try {
                dirty = gitRepo.hasTrackedChanges();
            } catch (Exception e) {
                dirty = false;
            }
            if (dirty) {
                // cambios locais sen subir: segundo o estado do remoto, ofrecer subir
                // (reconciliando se o remoto avanzou) ou avisar de que non se puido contactar.
                GitRepoService.RemoteState remote = gitRepo.checkRemoteAdvance(token);
                Platform.runLater(() -> {
                    pullNow.setDisable(false);
                    // o diálogo de confirmación + a barra de progreso comunican o estado;
                    // limpar a etiqueta para non deixar texto enganoso detrás.
                    syncLabel.setText("");
                    switch (remote) {
                        case AHEAD -> GitSync.confirmAndUpload(stage, gitRepo, GitHubSession.getInstance(),
                                GitSync.MSG_DIVERGED, rescan);
                        case NOT_AHEAD -> GitSync.confirmAndUpload(stage, gitRepo, GitHubSession.getInstance(),
                                GitSync.MSG_LOCAL_ONLY, rescan);
                        case UNAVAILABLE -> syncLabel.setText(
                                "non se puido contactar co servidor; téntao máis tarde");
                    }
                });
                return;
            }
            // árbore limpa: pull seguro normal
            GitRepoService.PullOutcome outcome = gitRepo.pullIfSafe(token);
            Platform.runLater(() -> {
                pullNow.setDisable(false);
                syncLabel.setText(switch (outcome) {
                    case UP_TO_DATE -> "actualizado (sen cambios novos)";
                    case UPDATED -> "actualizado con cambios novos";
                    case SKIPPED_DIRTY -> "hai cambios sen subir; non se actualizou";
                    case FAILED -> "erro ao actualizar";
                });
                if (outcome == GitRepoService.PullOutcome.UPDATED) {
                    rescan.run();
                }
            });
        }, "git-autopull").start();
    }

    /**
     * Busca recursivamente todos os .json baixo lang/ (en calquera subcarpeta),
     * excluíndo as copias de traballo (*.copy*.json). Devolve rutas relativas á
     * carpeta base (ex: lang/chapter1/strings.json), lexibles e listas para
     * abrir con tryOpen() (que as resolve dende a base).
     */
    private List<String> scanLangJsons(Path langDir) {
        Path base = AppDir.base();
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
                .map(p -> base.relativize(p).toString())
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

        // Resolver os nomes relativos dende a carpeta base (a do jar), non o cwd,
        // para que funcione igual lanzando por terminal ou por dobre clic.
        Path filePath = Path.of(filename);
        if (!filePath.isAbsolute()) {
            filePath = AppDir.base().resolve(filePath);
        }
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
            LocalView local = new LocalView(filePath.toString(), stage);
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
