package com.gui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
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

        VBox root = new VBox(12, title, inputRow, errorLabel);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER_LEFT);

        // ---- selector de lang/ (se existe a carpeta) ----
        Path langDir = Path.of("lang");
        boolean hasLang = Files.isDirectory(langDir);
        if (hasLang) {
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

            HBox langButtons = new HBox(8, openLang);
            langButtons.setAlignment(Pos.CENTER_LEFT);

            root.getChildren().addAll(langTitle, langList, langButtons);
        }

        root.getChildren().add(buttons);

        Scene scene = new Scene(root, 600, hasLang ? 480 : 200);
        stage.setScene(scene);
        stage.show();
        input.requestFocus();
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
