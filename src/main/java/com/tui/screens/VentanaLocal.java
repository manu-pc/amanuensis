package com.tui.screens;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.local.FileCopyManager;
import com.local.LocHelper;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.io.IOException;

public class VentanaLocal {

    private final MultiWindowTextGUI textGUI;
    private final LocHelper locHelper;
    private final FileCopyManager fileCopyManager;

    private boolean textoBase = false;
    private int currentIndex = 0;

    // componentes que precisan actualizarse
    private Label indexLabel;
    private Label literalLabel;
    private Label cleanLabel;
    private InterceptingTextBox editBox;
    private Label reappliedLabel;

    // goto
    private GotoTextBox gotoBox;

    public VentanaLocal(String filename, MultiWindowTextGUI textGUI) throws Exception {
        this.textGUI = textGUI;
        this.locHelper = new LocHelper(filename);
        this.fileCopyManager = new FileCopyManager(locHelper, filename);
    }

    public void show() {
        BasicWindow window = new BasicWindow("amanuensis - asistente de localización");

        // layout principal: unha sola columna para que cada elemento ocupe todo o ancho
        Panel root = new Panel();
        root.setLayoutManager(new GridLayout(1));

        // ---------- nav bar (fila de botones) ----------
        Panel navBar = new Panel(new GridLayout(8));
        Button prevBtn = new Button("<< anterior", this::prevLine);
        Button nextBtn = new Button("siguiente >>", this::nextLine);
        Button gotoEnd = new Button("ir a fin", () -> {
            currentIndex = Math.max(0, locHelper.getLineCount() - 1);
            updateView();
        });

        // pequeno panel para goto
        Panel gotoPanel = new Panel(new GridLayout(2));
        gotoBox = new GotoTextBox(new TerminalSize(6, 1));
        Button gotoBtn = new Button("ir", this::gotoFromBox);
        gotoPanel.addComponent(gotoBox);
        gotoPanel.addComponent(gotoBtn);

        // checkbox para texto base
        // checkbox
        TogglingCheckBox baseCheckBox = new TogglingCheckBox("usar texto base");
        baseCheckBox.setChecked(textoBase);

        // índice mostrable
        indexLabel = new Label("");
        indexLabel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.END, GridLayout.Alignment.CENTER, true, false));

        navBar.addComponent(prevBtn);
        navBar.addComponent(nextBtn);
        navBar.addComponent(gotoEnd);
        navBar.addComponent(gotoPanel);
        navBar.addComponent(baseCheckBox);
        navBar.addComponent(new EmptySpace(new TerminalSize(1,1)));
        navBar.addComponent(new EmptySpace(new TerminalSize(1,1)));
        navBar.addComponent(indexLabel);

        root.addComponent(navBar);

        // ---------- liña literal (con indicadores) ----------
        Label litTitle = new Label("liña literal (con indicadores):");
        litTitle.setForegroundColor(TextColor.ANSI.WHITE);
        root.addComponent(litTitle);

        literalLabel = new Label("");
        literalLabel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        literalLabel.setForegroundColor(TextColor.ANSI.MAGENTA);
        root.addComponent(literalLabel);

        // separador
        Label sep1 = new Label("────────────────────────────────────────────────────────");
        sep1.setForegroundColor(TextColor.ANSI.BLACK);
        sep1.setBackgroundColor(TextColor.ANSI.WHITE);
        root.addComponent(sep1);

        // ---------- liña limpa (sen indicadores) ----------
        Label cleanTitle = new Label("liña limpa (sen indicadores):");
        cleanTitle.setForegroundColor(TextColor.ANSI.WHITE);
        root.addComponent(cleanTitle);

        cleanLabel = new Label("");
        cleanLabel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        cleanLabel.setForegroundColor(TextColor.ANSI.CYAN);
        root.addComponent(cleanLabel);

        // separador
        Label sep2 = new Label("────────────────────────────────────────────────────────");
        sep2.setForegroundColor(TextColor.ANSI.BLACK);
        sep2.setBackgroundColor(TextColor.ANSI.WHITE);
        root.addComponent(sep2);

        // ---------- textbox grande ----------
        Label editTitle = new Label("editar (texto limpo):  (enter = gardar, shift+enter = nova liña)");
        editTitle.setForegroundColor(TextColor.ANSI.WHITE);
        root.addComponent(editTitle);

        editBox = new InterceptingTextBox(new TerminalSize(80, 6));
        editBox.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true));
        root.addComponent(editBox);

        // ---------- etiqueta de resultado ----------
        reappliedLabel = new Label("resultado formateado aparecerá aquí");
        reappliedLabel.setForegroundColor(TextColor.ANSI.GREEN);
        reappliedLabel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        root.addComponent(reappliedLabel);

        // separador final
        Label sep3 = new Label("────────────────────────────────────────────────────────");
        sep3.setForegroundColor(TextColor.ANSI.BLACK);
        sep3.setBackgroundColor(TextColor.ANSI.WHITE);
        root.addComponent(sep3);

        // ---------- bottom buttons ----------
        Panel bottom = new Panel(new GridLayout(3));
        Button save = new Button("gardar", this::doSaveFromEditBox);
        Button refresh = new Button("refrescar", this::updateView);
        Button close = new Button("cerrar", () -> {
            try { window.close(); } catch (Exception ignored) {}
        });
        bottom.addComponent(save);
        bottom.addComponent(refresh);
        bottom.addComponent(close);
        root.addComponent(bottom);

        window.setComponent(root);

        // inicializar vista
        updateView();

        textGUI.addWindowAndWait(window);
    }

    // acción central de gardado usada por enter e por botón gardar
    private void doSaveFromEditBox() {
        String newPlain = editBox.getText();
        String formatted = locHelper.reapplyFormatting(currentIndex, newPlain);

        try {
            // actualizar só no ficheiro copia usando fileCopyManager
            fileCopyManager.updateLine(currentIndex, formatted);

            // mostrar resultado formateado na etiqueta informativa (sen tocar literalLabel nin locHelper)
            reappliedLabel.setText("gardado: " + formatted );
            reappliedLabel.setForegroundColor(TextColor.ANSI.GREEN);
        } catch (IOException e) {
            reappliedLabel.setText("erro ao gardar: " + e.getMessage());
            reappliedLabel.setForegroundColor(TextColor.ANSI.RED);
        }
    }

    // avanzar á seguinte liña
    private void nextLine() {
        int max = locHelper.getLineCount();
        if (currentIndex < max - 1) {
            currentIndex++;
            updateView();
        }
    }

    // retroceder á liña anterior
    private void prevLine() {
        if (currentIndex > 0) {
            currentIndex--;
            updateView();
        }
    }

    // version rápida: salta 10 liñas (usada por ctrl+page)
    private void skipForward() {
        int max = locHelper.getLineCount();
        currentIndex = Math.min(max - 1, currentIndex + 10);
        updateView();
    }

    private void skipBackward() {
        currentIndex = Math.max(0, currentIndex - 10);
        updateView();
    }

    // actualiza todas as compoñentes da xanela segundo currentIndex
    private void updateView() {
        int total = locHelper.getLineCount();
        if (total == 0) {
            indexLabel.setText("0/0");
            literalLabel.setText("(ficheiro vacío)");
            cleanLabel.setText("");
            editBox.setText("");
            reappliedLabel.setText("");
            return;
        }

        // actualizar índice
        indexLabel.setText(String.format("%d / %d", currentIndex + 1, total));

        // obter datos do locHelper
        String literal = locHelper.getOriginal(currentIndex);
        String clean = locHelper.stripFormatting(currentIndex);

        // actualizar compoñentes (non cambiamos literalLabel cando se gardará)
        literalLabel.setText(literal);
        cleanLabel.setText(clean);
        // usar o estado actual da checkbox (que foi sincronizada con textoBase)
        if (textoBase) editBox.setText(clean);
        else editBox.setText("");
        reappliedLabel.setText(""); // limpar resultado anterior

        // actualizar goto box
        if (gotoBox != null) gotoBox.setText(String.valueOf(currentIndex + 1));
    }

    // acción do botón/enter do goto
    private void gotoFromBox() {
        String txt = gotoBox.getText().trim();
        if (txt.isEmpty()) {
            reappliedLabel.setText("introduce un número de liña (1 - " + locHelper.getLineCount() + ")");
            reappliedLabel.setForegroundColor(TextColor.ANSI.YELLOW);
            return;
        }
        try {
            int n = Integer.parseInt(txt);
            if (n < 1 || n > locHelper.getLineCount()) {
                reappliedLabel.setText("número fóra de rango: 1 - " + locHelper.getLineCount());
                reappliedLabel.setForegroundColor(TextColor.ANSI.YELLOW);
                return;
            }
            currentIndex = n - 1;
            updateView();
        } catch (NumberFormatException e) {
            reappliedLabel.setText("número non válido");
            reappliedLabel.setForegroundColor(TextColor.ANSI.YELLOW);
        }
    }

    /**
     * textBox que intercepta enter, pageup/pagedown e os combina con ctrl para saltos.
     * se premes enter chama a doSaveFromEditBox().
     */
    private class InterceptingTextBox extends TextBox {
        InterceptingTextBox(TerminalSize size) {
            super(size);
        }

        @Override
        public Interactable.Result handleKeyStroke(KeyStroke key) {
            if (key == null) return super.handleKeyStroke(null);

            KeyType kt = key.getKeyType();

            // enter -> gardar (shift+enter insire newline)
            if (kt == KeyType.Enter) {
                if (key.isShiftDown()) {
                    return super.handleKeyStroke(key); // allow newline
                } else {
                    doSaveFromEditBox();
                    return Interactable.Result.HANDLED;
                }
            }

            // page up / page down -> navegación
            if (kt == KeyType.PageUp) {
                if (key.isCtrlDown()) {
                    skipBackward();
                } else {
                    prevLine();
                }
                return Interactable.Result.HANDLED;
            }
            if (kt == KeyType.PageDown) {
                if (key.isCtrlDown()) {
                    skipForward();
                } else {
                    nextLine();
                }
                return Interactable.Result.HANDLED;
            }

            return super.handleKeyStroke(key);
        }
    }

    // caixa pequena que permite premer enter para saltar a liña
    private class GotoTextBox extends TextBox {
        GotoTextBox(TerminalSize size) {
            super(size);
        }

        @Override
        public Interactable.Result handleKeyStroke(KeyStroke key) {
            if (key == null) return super.handleKeyStroke(null);

            if (key.getKeyType() == KeyType.Enter) {
                gotoFromBox();
                return Interactable.Result.HANDLED;
            }
            return super.handleKeyStroke(key);
        }
    }

    // checkbox que sincroniza o estado con textoBase e actualiza a vista ao cambiar
    private class TogglingCheckBox extends CheckBox {
        TogglingCheckBox(String label) {
            super(label);
        }

        @Override
        public Interactable.Result handleKeyStroke(KeyStroke key) {
            Interactable.Result r = super.handleKeyStroke(key);
            // sincronizar e actualizar vista
            textoBase = this.isChecked();
            updateView();
            return r;
        }
    }

}
