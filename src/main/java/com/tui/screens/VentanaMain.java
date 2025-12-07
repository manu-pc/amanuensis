package com.ejemplo.calc.gui.screens;

import com.ejemplo.calc.gui.screens.calc.VentanaMates;
import com.ejemplo.calc.gui.screens.text.VentanaTexto;
import com.ejemplo.calc.service.CalcClient;
import com.ejemplo.calc.service.TextClient;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
//? pantalla menu en lanterna

public class VentanaMain {

    private final MultiWindowTextGUI textGUI;
    private final CalcClient calcClient;
    private final TextClient textClient;
    private boolean calcAvailable = false;
    private boolean textAvailable = false;
    private Label statusLabel;
    private Panel buttonPanel;

    public VentanaMain(MultiWindowTextGUI textGUI, CalcClient calcClient, TextClient textClient) {
        this.textGUI = textGUI;
        this.calcClient = calcClient;
        this.textClient = textClient;
        checkServices();
    }

    private void checkServices() {
        // comprobar si están activados y funcionan
        try {
            calcAvailable = calcClient.sumar(1, 1) == 2;
        } catch (Exception e) {
            calcAvailable = false;
        }

        // Verificar servicio Texto
        try {
            textAvailable = textClient.contarCaracteres("hola") == 4;

        } catch (Exception e) {
            textAvailable = false;
        }
    }

    private void updateUI() {
        // Actualizar texto de estado
        StringBuilder status = new StringBuilder("Servicios detectados: ");
        if (calcAvailable) {
            status.append("- Calculadora ");
        }
        if (textAvailable) {
            status.append("- Texto ");
        }
        if (!calcAvailable && !textAvailable) {
            status.append("Ningún servidor detectado.");
        }

        statusLabel.setText(status.toString());

        // Limpiar botones anteriores
        buttonPanel.removeAllComponents();

        // Añadir solo servicios disponibles
        if (calcAvailable) {
            buttonPanel.addComponent(new Button("Calculadora", () -> {
                VentanaMates aritmetica = new VentanaMates(textGUI, calcClient);
                aritmetica.show();
            }));
        } else {
            buttonPanel.addComponent(new Label("Calculadora no activada")
                    .setForegroundColor(TextColor.ANSI.RED));
        }

        if (textAvailable) {
            buttonPanel.addComponent(new Button("Analizador de textos", () -> {
                VentanaTexto textWindow = new VentanaTexto(textGUI, textClient);
                textWindow.show();
            }));
        } else {
            buttonPanel.addComponent(new Label("Analizador de textos")
                    .setForegroundColor(TextColor.ANSI.RED));
        }

    }

    public void show() {
        BasicWindow window = new BasicWindow("Servicios SOAP - Menú Principal");
        window.setHints(java.util.Set.of(Window.Hint.CENTERED));

        Panel mainPanel = new Panel();
        mainPanel.setLayoutManager(new GridLayout(1));

        // título
        Label title = new Label("Servicios disponibles:");
        title.setForegroundColor(TextColor.ANSI.BLUE);
        title.setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.CENTER,
                GridLayout.Alignment.CENTER,
                true,
                false
        ));
        mainPanel.addComponent(title);
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        // estado de servicios
        statusLabel = new Label("");
        statusLabel.setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.CENTER,
                GridLayout.Alignment.CENTER,
                true,
                false
        ));
        mainPanel.addComponent(statusLabel);
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        // panel para botones de servicios
        buttonPanel = new Panel();
        buttonPanel.setLayoutManager(new GridLayout(1));
        mainPanel.addComponent(buttonPanel);

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        mainPanel.addComponent(new Button("Salir", window::close));

        window.setComponent(mainPanel);

        // Actualizar UI inicial
        updateUI();

        textGUI.addWindowAndWait(window);
    }
}
