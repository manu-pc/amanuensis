package com;

import com.tui.TUI;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public class Main {
    private static final Path CRASH_LOG = Path.of("amanuensis-crash.log");

    public static void main(String[] args) {
        try {
            TUI tui = new TUI();
            tui.start();
        } catch (Exception e) {
            logCrash(e);
            System.exit(1);
        }
    }

    static void logCrash(Exception e) {
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
