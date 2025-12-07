package com.tui;
import java.io.IOException;

import com.tui.screens.VentanaMain;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;


//? manager xeral da TUI usando lanterna. só crea a VentanaMain
public class TUI {
    private MultiWindowTextGUI textGUI;


    public TUI() {
        try {
            initGUI();
        } catch (Exception e) {
            System.err.println("Error en lanterna!" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initGUI() throws IOException {
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        this.textGUI = new MultiWindowTextGUI(screen);
    }

    public void start() {
        VentanaMain mainScreen = new VentanaMain(textGUI);
        mainScreen.show();
    }


}