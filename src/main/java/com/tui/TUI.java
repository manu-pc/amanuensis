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
    private Screen screen;
    private MultiWindowTextGUI textGUI;

    public TUI() throws IOException {
        initGUI();
    }

    private void initGUI() throws IOException {
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        this.screen = new TerminalScreen(terminal);
        screen.startScreen();
        this.textGUI = new MultiWindowTextGUI(screen);
    }

    public void start() {
        try {
            VentanaMain mainScreen = new VentanaMain(textGUI);
            mainScreen.show();
        } finally {
            // restaurar o terminal ao saír para que os erros sexan visibles
            try {
                screen.stopScreen();
            } catch (IOException ignored) {}
        }
    }
}