package com.gui;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Punto de entrada de JavaFX. Só crea o Stage principal e mostra a
 * pantalla inicial (MainView), que á súa vez abre o editor (LocalView).
 *
 * Nota: esta clase é a única que estende Application. O verdadeiro main
 * (com.Main) NON a estende, para que o fat-jar arranque sen o erro
 * "JavaFX runtime components are missing".
 */
public class GuiApp extends Application {

    @Override
    public void start(Stage stage) {
        stage.setTitle("amanuensis");
        new MainView(stage).show();
    }

    /** Lanzado desde com.Main. */
    public static void launchApp(String[] args) {
        launch(args);
    }
}
