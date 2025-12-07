package com;

import com.tui.TUI;

public class Main {
    public static void main(String[] args) {
        TUI tui = new TUI();
        try {
            tui.start();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
