package com;

import com.gui.GuiApp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public class Main {
    private static final Path CRASH_LOG = Path.of("amanuensis-crash.log");

    public static void main(String[] args) {
        try {
            // GuiApp é a única clase que estende Application; lánzase desde aquí
            // (un main que NON estende Application) para que o fat-jar arranque ben.
            GuiApp.launchApp(args);
        } catch (Throwable e) {
            logCrash(e);
            System.exit(1);
        }
    }

    static void logCrash(Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            sw.write("[" + LocalDateTime.now() + "] CRASH:\n");
            e.printStackTrace(new PrintWriter(sw));
            sw.write("\n");
            Files.writeString(CRASH_LOG, sw.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // último recurso: se non podemos escribir o log, non hai nada que facer
        }
    }
}
