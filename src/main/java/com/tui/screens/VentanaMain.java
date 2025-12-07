package com.tui.screens;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.TextColor;

import java.util.List;

//? clase coa ventana inicial do codigo, que só pide un nome de json
// probablemente podería ser máis bonita
public class VentanaMain {

    private final MultiWindowTextGUI textGUI;

    public VentanaMain(MultiWindowTextGUI textGUI) {
        this.textGUI = textGUI;
    }

    public void show() {
        BasicWindow window = new BasicWindow("amanuensis");
        window.setHints(List.of(Window.Hint.CENTERED));

        // panel principal
        Panel root = new Panel(new GridLayout(1));
        root.setLayoutData(BorderLayout.Location.CENTER);

        // titulo
        Label title = new Label("Introduce el nombre del archivo (.json)");
        title.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        root.addComponent(title);

        // input
        TextBox input = new TextBox(new TerminalSize(40, 1));
        input.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        root.addComponent(input);

        // etiqueta de error (invisible por defecto)
        Label errorLabel = new Label("");
        errorLabel.setForegroundColor(TextColor.ANSI.RED);
        errorLabel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        root.addComponent(errorLabel);

        Panel buttons = new Panel(new GridLayout(2));
        buttons.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.END, GridLayout.Alignment.CENTER, true, false));

        Button accept = new Button("aceptar", () -> {
            String filename = input.getText() != null ? input.getText().trim() : "";
            if (filename.isEmpty()) {
                errorLabel.setText("el nombre no puede estar vacío");
                return;
            }
            if (!filename.toLowerCase().endsWith(".json")) {
                errorLabel.setText("el archivo debe tener extensión .json");
                return;
            }

            errorLabel.setText("");

            // crear e mostrar ventana local
            try {
                VentanaLocal ventanaLocal = new VentanaLocal(filename, textGUI);
                // cerrar ventana actual antes de abrir la siguiente
                window.close();
                ventanaLocal.show();
            } catch (Exception e) {
                errorLabel.setText("error al abrir la ventana local: " + e.getMessage());
            }
        });

        Button cancel = new Button("cancelar", window::close);

        buttons.addComponent(accept);
        buttons.addComponent(cancel);

        // espacio para separar
        root.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        root.addComponent(buttons);

        window.setComponent(root);

        textGUI.addWindowAndWait(window);
    }
}
