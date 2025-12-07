package tui;
import java.io.IOException;

import com.ejemplo.calc.gui.screens.VentanaMain;
import com.ejemplo.calc.service.CalcClient;
import com.ejemplo.calc.service.TextClient;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

public class TUI {
    private LocHelper locHelper;
    private MultiWindowTextGUI textGUI;
    private Screen screen;

    private boolean calcConnected = false;
    private boolean textConnected = false;

    public boolean connectToCalc() {
        if (!calcConnected) {
            try {
                this.calcClient = new CalcClient();
                calcConnected = true;
                return true;
            } catch (Exception e) {
                System.err.println("No se puede conectar al servicio Calculadora: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    public boolean connectToText() {
        if (!textConnected) {
            try {
                this.textoClient = new TextClient();
                textConnected = true;
                return true;
            } catch (Exception e) {
                System.err.println("No se puede conectar al servicio Analizador de Textos: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    public TUI() {
        try {
            initGUI();
            // NO inicializar los clientes aquí - se hará cuando sean necesarios
        } catch (Exception e) {
            System.err.println("Error en lanterna!" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initGUI() throws IOException {
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        this.screen = new TerminalScreen(terminal);
        screen.startScreen();
        this.textGUI = new MultiWindowTextGUI(screen);
    }

    public void start() {

        boolean calcSuccess = connectToCalc();
        boolean textSuccess = connectToText();

        if (!calcSuccess && !textSuccess) {
            return;
        }

        VentanaMain mainScreen = new VentanaMain(textGUI, calcClient, textoClient);
        mainScreen.show();
    }

    // Getters para los clientes (pueden ser null)
    public CalcClient getCalcClient() { return calcClient; }
    public TextClient getTextClient() { return textoClient; }
    public boolean isCalcConnected() { return calcConnected; }
    public boolean isTextConnected() { return textConnected; }

    public void shutdown() {
        try {
            if (screen != null) {
                screen.stopScreen();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SOAPGUI gui = new SOAPGUI();
        try {
            gui.start();
        } finally { // se ejecuta siempre
            gui.shutdown();
        }
    }
}