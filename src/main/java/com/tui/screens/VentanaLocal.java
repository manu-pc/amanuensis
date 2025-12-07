package com.ejemplo.calc.gui.screens.text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.ejemplo.calc.service.TextClient;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;

//? pantalla para reemplazar palabras en el texto
class VentanaLocal {

    private final MultiWindowTextGUI textGUI;
    private final TextClient textClient;

    public VentanaLocal(MultiWindowTextGUI textGUI, TextClient textClient) {
        this.textGUI = textGUI;
        this.textClient = textClient;
    }

    public void show() {
        BasicWindow window = new BasicWindow("Reemplazar Palabras");

        Panel panel = new Panel();
        panel.setLayoutManager(new GridLayout(2));

        TextBox fileNameField = new TextBox();
        TextBox textoField = new TextBox("", TextBox.Style.MULTI_LINE);
        textoField.setPreferredSize(new TerminalSize(60, 10));
        TextBox palabraOriginalField = new TextBox();
        TextBox palabraNuevaField = new TextBox();

        // campo para nombre de archivo
        panel.addComponent(new Label("Nombre de archivo:")
                .setLayoutData(GridLayout.createLayoutData(
                        GridLayout.Alignment.BEGINNING,
                        GridLayout.Alignment.CENTER,
                        false, false, 2, 1
                )));

        fileNameField.setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.FILL,
                GridLayout.Alignment.CENTER,
                true, false, 2, 1
        ));
        panel.addComponent(fileNameField);

        // botón cargar archivo
        panel.addComponent(new Button("Cargar Archivo", () -> {
            try {
                String fileName = fileNameField.getText().trim();
                if (!fileName.isEmpty()) {
                    String content = new String(Files.readAllBytes(Paths.get(fileName)));
                    textoField.setText(content);
                } else {
                    // No hay campo de resultado, podrías mostrar un mensaje temporal
                }
            } catch (IOException e) {
                // No hay campo de resultado, el error se perdería
            } catch (Exception e) {
                // No hay campo de resultado, el error se perdería
            }
        }).setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.CENTER,
                GridLayout.Alignment.CENTER,
                true, false, 2, 1
        )));

        // campo de texto
        panel.addComponent(new Label("Texto:")
                .setLayoutData(GridLayout.createLayoutData(
                        GridLayout.Alignment.BEGINNING,
                        GridLayout.Alignment.CENTER,
                        false, false, 2, 1
                )));

        textoField.setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.FILL,
                GridLayout.Alignment.CENTER,
                true, false, 2, 1
        ));
        panel.addComponent(textoField);

        // campos para reemplazo
        panel.addComponent(new Label("Palabra a reemplazar:"));
        panel.addComponent(palabraOriginalField);

        panel.addComponent(new Label("Nueva palabra:"));
        panel.addComponent(palabraNuevaField);

        panel.addComponent(new EmptySpace(TerminalSize.ONE));
        panel.addComponent(new EmptySpace(TerminalSize.ONE));

        // Botón de reemplazo
        panel.addComponent(new Button("Reemplazar", () -> {
            try {
                String original = palabraOriginalField.getText().trim();
                String nueva = palabraNuevaField.getText();
                if (original.isEmpty()) {
                    return; // No hay campo de resultado para mostrar mensaje
                }
                String resultado = textClient.reemplazarPalabra(textoField.getText(), original, nueva);
                textoField.setText(resultado); // Sobrescribe el campo de entrada
            } catch (Exception e) {
                // No hay campo de resultado, el error se perdería
            }
        }).setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.CENTER,
                GridLayout.Alignment.CENTER,
                true, false, 2, 1
        )));

        panel.addComponent(new EmptySpace(TerminalSize.ONE));
        panel.addComponent(new Button("Volver", window::close));

        window.setComponent(panel);
        textGUI.addWindowAndWait(window);
    }
}