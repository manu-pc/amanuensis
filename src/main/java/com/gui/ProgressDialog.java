package com.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Diálogo modal cunha barra de progreso indeterminada e unha mensaxe, para
 * amosar mentres unha operación de rede (subir/actualizar) está en marcha.
 * Non se pode pechar a man: ábrese con show(), péchase por código con close().
 * Todos os métodos deben chamarse no fío de UI de JavaFX.
 */
final class ProgressDialog {

    private final Stage stage;
    private final Label label;

    ProgressDialog(Stage owner, String title, String message) {
        stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(title);
        stage.setResizable(false);

        label = new Label(message);
        label.setWrapText(true);

        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(280);
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        VBox box = new VBox(14, label, bar);
        box.setPadding(new Insets(18));
        box.setAlignment(Pos.CENTER_LEFT);

        stage.setScene(new Scene(box, 340, 120));
        // impedir que o usuario o peche mentres a operación segue en marcha
        stage.setOnCloseRequest(e -> e.consume());
    }

    void setMessage(String message) {
        label.setText(message);
    }

    void show() {
        stage.show();
    }

    void close() {
        stage.close();
    }
}
