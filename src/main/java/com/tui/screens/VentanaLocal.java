package com.tui.screens;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.local.FileCopyManager;
import com.local.LocHelper;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class VentanaLocal {

    private final MultiWindowTextGUI textGUI;
    private final LocHelper locHelper;
    private final FileCopyManager fileCopyManager;

    private boolean textoBase = false;
    private boolean autoAdvance = true;  // Auto-avanzar á seguinte liña despois de gardar
    private int currentIndex = 0;

    // componentes que precisan actualizarse
    private Label indexLabel;
    private Label keyLabel;      // Mostra o JSON key actual
    private Label infoLabel;     // Mostra info sobre marcadores
    private TextBox literalBox;  // TextBox para multi-liña
    private TextBox cleanBox;    // TextBox para multi-liña
    private InterceptingTextBox editBox;
    private Label previewLabel;  // Mostra preview do resultado
    private Label reappliedLabel;
    private Label timeLabel;

    // goto e search
    private GotoTextBox gotoBox;
    private SearchTextBox searchBox;
    private String lastSearchTerm = "";
    private List<Integer> searchResults = new ArrayList<>();
    private int searchResultIndex = -1;

    public VentanaLocal(String filename, MultiWindowTextGUI textGUI) throws IOException {
        this.textGUI = textGUI;
        this.locHelper = new LocHelper(filename);
        this.fileCopyManager = new FileCopyManager(locHelper, filename);
    }

    /**
     * Formatea o texto literal para mostrar os marcadores de newline de forma visual.
     * Substitúe &, #, \n por versións con cor e indicadores visuais.
     */
    private String formatLiteralForDisplay(String literal) {
        // Mostrar o texto tal cal pero con saltos de liña reais onde hai marcadores
        // para que se vexa en múltiples liñas
        return literal
                .replace("&", "⏎&\n")      // & con símbolo e newline
                .replace("#", "⏎#\n")      // # con símbolo e newline
                .replace("\n", "⏎\\n\n");  // \n literal con símbolo
    }

    /**
     * Busca un termo en todas as liñas (texto orixinal, limpo e clave).
     */
    private void doSearch(String term) {
        if (term == null || term.trim().isEmpty()) {
            searchResults.clear();
            searchResultIndex = -1;
            return;
        }

        String searchLower = term.toLowerCase();
        lastSearchTerm = term;
        searchResults.clear();

        for (int i = 0; i < locHelper.getLineCount(); i++) {
            String original = locHelper.getOriginal(i).toLowerCase();
            String key = locHelper.getKey(i).toLowerCase();
            String clean = locHelper.stripFormatting(i).toLowerCase();

            if (original.contains(searchLower) || key.contains(searchLower) || clean.contains(searchLower)) {
                searchResults.add(i);
            }
        }

        if (!searchResults.isEmpty()) {
            // Buscar o resultado máis próximo ao índice actual
            searchResultIndex = 0;
            for (int i = 0; i < searchResults.size(); i++) {
                if (searchResults.get(i) >= currentIndex) {
                    searchResultIndex = i;
                    break;
                }
            }
            gotoSearchResult();
        } else {
            reappliedLabel.setText("non se atopou: \"" + term + "\"");
            reappliedLabel.setForegroundColor(TextColor.ANSI.YELLOW);
        }
    }

    private void gotoSearchResult() {
        if (searchResults.isEmpty()) return;

        currentIndex = searchResults.get(searchResultIndex);
        updateView();
        reappliedLabel.setText(String.format("resultado %d/%d para \"%s\"",
                searchResultIndex + 1, searchResults.size(), lastSearchTerm));
        reappliedLabel.setForegroundColor(TextColor.ANSI.CYAN);
    }

    private void nextSearchResult() {
        if (searchResults.isEmpty()) return;
        searchResultIndex = (searchResultIndex + 1) % searchResults.size();
        gotoSearchResult();
    }

    private void prevSearchResult() {
        if (searchResults.isEmpty()) return;
        searchResultIndex = (searchResultIndex - 1 + searchResults.size()) % searchResults.size();
        gotoSearchResult();
    }

    public void show() {
        BasicWindow window = new BasicWindow("amanuensis - asistente de localización");

        // layout principal: unha sola columna para que cada elemento ocupe todo o ancho
        Panel root = new Panel();
        root.setLayoutManager(new GridLayout(1));

        // ---------- nav bar (fila de botones) ----------
        Panel navBar = new Panel(new GridLayout(9));
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
        timeLabel = new Label("");
        timeLabel.setForegroundColor(TextColor.ANSI.CYAN);
        navBar.addComponent(timeLabel);
        navBar.addComponent(indexLabel);

        root.addComponent(navBar);

        // ---------- search bar ----------
        Panel searchBar = new Panel(new GridLayout(5));
        Label searchLabel = new Label("buscar:");
        searchBox = new SearchTextBox(new TerminalSize(30, 1));
        Button searchBtn = new Button("buscar", () -> doSearch(searchBox.getText()));
        Button prevResultBtn = new Button("< ant", this::prevSearchResult);
        Button nextResultBtn = new Button("seg >", this::nextSearchResult);

        searchBar.addComponent(searchLabel);
        searchBar.addComponent(searchBox);
        searchBar.addComponent(searchBtn);
        searchBar.addComponent(prevResultBtn);
        searchBar.addComponent(nextResultBtn);

        root.addComponent(searchBar);

        // ---------- key label e info (mostra o ID e información sobre marcadores) ----------
        Panel keyInfoPanel = new Panel(new GridLayout(2));
        keyLabel = new Label("");
        keyLabel.setForegroundColor(TextColor.ANSI.YELLOW);
        infoLabel = new Label("");
        infoLabel.setForegroundColor(TextColor.ANSI.CYAN);
        keyInfoPanel.addComponent(keyLabel);
        keyInfoPanel.addComponent(infoLabel);
        root.addComponent(keyInfoPanel);

        // ---------- liña literal (con indicadores) ----------
        Label litTitle = new Label("liña literal (con indicadores):  [⏎ = newline marker]");
        litTitle.setForegroundColor(TextColor.ANSI.WHITE);
        root.addComponent(litTitle);

        // Usar TextBox en modo lectura para soportar multi-liña
        literalBox = new TextBox(new TerminalSize(80, 4), TextBox.Style.MULTI_LINE);
        literalBox.setReadOnly(true);
        literalBox.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, false));
        root.addComponent(literalBox);

        // separador
        Label sep1 = new Label("────────────────────────────────────────────────────────────────────────────────");
        sep1.setForegroundColor(TextColor.ANSI.BLACK);
        sep1.setBackgroundColor(TextColor.ANSI.WHITE);
        root.addComponent(sep1);

        // ---------- liña limpa (sen indicadores) ----------
        Label cleanTitle = new Label("liña limpa (sen indicadores):");
        cleanTitle.setForegroundColor(TextColor.ANSI.WHITE);
        root.addComponent(cleanTitle);

        // TextBox en modo lectura para o texto limpo
        cleanBox = new TextBox(new TerminalSize(80, 4), TextBox.Style.MULTI_LINE);
        cleanBox.setReadOnly(true);
        cleanBox.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, false));
        root.addComponent(cleanBox);

        // separador
        Label sep2 = new Label("────────────────────────────────────────────────────────────────────────────────");
        sep2.setForegroundColor(TextColor.ANSI.BLACK);
        sep2.setBackgroundColor(TextColor.ANSI.WHITE);
        root.addComponent(sep2);

        // ---------- textbox grande para edición ----------
        Label editTitle = new Label("editar (texto limpo):  [Enter = gardar] [Shift+Tab = nova liña]");
        editTitle.setForegroundColor(TextColor.ANSI.WHITE);
        root.addComponent(editTitle);

        editBox = new InterceptingTextBox(new TerminalSize(80, 6));
        editBox.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true));
        root.addComponent(editBox);

        // ---------- preview do resultado formateado ----------
        Label previewTitle = new Label("preview (resultado formateado):");
        previewTitle.setForegroundColor(TextColor.ANSI.WHITE);
        root.addComponent(previewTitle);

        previewLabel = new Label("");
        previewLabel.setForegroundColor(TextColor.ANSI.MAGENTA);
        previewLabel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        root.addComponent(previewLabel);

        // separador
        Label sep3 = new Label("────────────────────────────────────────────────────────────────────────────────");
        sep3.setForegroundColor(TextColor.ANSI.BLACK);
        sep3.setBackgroundColor(TextColor.ANSI.WHITE);
        root.addComponent(sep3);

        // ---------- etiqueta de estado ----------
        reappliedLabel = new Label("listo");
        reappliedLabel.setForegroundColor(TextColor.ANSI.GREEN);
        reappliedLabel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING, true, false));
        root.addComponent(reappliedLabel);

        // ---------- bottom buttons ----------
        Panel bottom = new Panel(new GridLayout(6));
        Button save = new Button("gardar", this::doSaveFromEditBox);
        Button saveChanges = new Button("gardar cambios", this::doSaveChanges);
        Button refresh = new Button("refrescar", this::updateView);

        // checkbox para auto-avance
        CheckBox autoAdvanceCheck = new CheckBox("auto-avanzar") {
            @Override
            public Interactable.Result handleKeyStroke(KeyStroke key) {
                Interactable.Result r = super.handleKeyStroke(key);
                autoAdvance = this.isChecked();
                return r;
            }
        };
        autoAdvanceCheck.setChecked(autoAdvance);

        Button close = new Button("cerrar", () -> {
            try { window.close(); } catch (Exception ignored) {}
        });
        bottom.addComponent(save);
        bottom.addComponent(saveChanges);
        bottom.addComponent(refresh);
        bottom.addComponent(autoAdvanceCheck);
        bottom.addComponent(new EmptySpace(new TerminalSize(1,1)));
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

        // Se o texto está baleiro e non é o texto base, non gardar
        if (newPlain.isEmpty() && !textoBase) {
            reappliedLabel.setText("introduce texto antes de gardar");
            reappliedLabel.setForegroundColor(TextColor.ANSI.YELLOW);
            return;
        }

        String formatted = locHelper.reapplyFormatting(currentIndex, newPlain);

        try {
            // actualizar só no ficheiro copia usando fileCopyManager
            fileCopyManager.updateLine(currentIndex, formatted);

            // mostrar resultado formateado na etiqueta informativa
            reappliedLabel.setText("gardado [" + (currentIndex + 1) + "]: " + (formatted.length() > 60 ? formatted.substring(0, 57) + "..." : formatted));
            reappliedLabel.setForegroundColor(TextColor.ANSI.GREEN);

            // auto-avanzar se está habilitado e non estamos na última liña
            if (autoAdvance && currentIndex < locHelper.getLineCount() - 1) {
                String savedMsg = reappliedLabel.getText();
                currentIndex++;
                updateView();
                reappliedLabel.setText(savedMsg);
            }
        } catch (IOException e) {
            reappliedLabel.setText("erro ao gardar: " + e.getMessage());
            reappliedLabel.setForegroundColor(TextColor.ANSI.RED);
        }
    }

    // garda o contido do ficheiro copia no ficheiro orixinal e actualiza a memoria local
    private void doSaveChanges() {
        try {
            fileCopyManager.saveToOriginal();
            updateView();
            reappliedLabel.setText("cambios gardados no ficheiro orixinal");
            reappliedLabel.setForegroundColor(TextColor.ANSI.GREEN);
        } catch (IOException e) {
            reappliedLabel.setText("erro ao gardar cambios: " + e.getMessage());
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
            keyLabel.setText("");
            infoLabel.setText("");
            literalBox.setText("(ficheiro vacío)");
            cleanBox.setText("");
            editBox.setText("");
            previewLabel.setText("");
            reappliedLabel.setText("");
            return;
        }

        // actualizar hora e índice
        timeLabel.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        indexLabel.setText(String.format("%d / %d", currentIndex + 1, total));

        // obter datos do locHelper
        String key = locHelper.getKey(currentIndex);
        String literal = locHelper.getOriginal(currentIndex);
        String clean = locHelper.stripFormatting(currentIndex);

        // actualizar key label
        keyLabel.setText("key: " + key);

        // actualizar info label con información sobre marcadores
        int newlineCount = locHelper.countNewlines(currentIndex);
        boolean hasColor = locHelper.hasColorMarkers(currentIndex);
        boolean hasPause = locHelper.hasPauseMarkers(currentIndex);
        boolean hasTilde = locHelper.hasTildeMarkers(currentIndex);
        boolean hasBackslashO = locHelper.hasBackslashOMarkers(currentIndex);
        boolean hasBackslashI = locHelper.hasBackslashIMarkers(currentIndex);
        StringBuilder info = new StringBuilder();
        info.append("[").append(newlineCount).append(" liñas]");
        if (hasColor) info.append(" [cor *]");
        if (hasTilde) info.append(" [efecto ~]");
        if (hasBackslashO) info.append(" [\\O @]");
        if (hasBackslashI) info.append(" [\\I $]");
        if (hasPause) info.append(" [pausa]");
        infoLabel.setText(info.toString());

        // formatear literal para mostrar marcadores de newline visualmente
        String formattedLiteral = formatLiteralForDisplay(literal);

        // actualizar compoñentes
        literalBox.setText(formattedLiteral);
        cleanBox.setText(clean);

        // usar o estado actual da checkbox para pre-poboar o editor
        if (textoBase) {
            editBox.setText(clean);
        } else {
            editBox.setText("");
        }

        // actualizar preview
        updatePreview();

        reappliedLabel.setText("listo");
        reappliedLabel.setForegroundColor(TextColor.ANSI.GREEN);

        // actualizar goto box
        if (gotoBox != null) gotoBox.setText(String.valueOf(currentIndex + 1));
    }

    // actualiza o preview do resultado formateado
    private void updatePreview() {
        String newPlain = editBox.getText();
        if (newPlain.isEmpty()) {
            previewLabel.setText("(introduce texto para ver preview)");
            return;
        }
        String formatted = locHelper.reapplyFormatting(currentIndex, newPlain);
        // Truncar se é moi longo
        if (formatted.length() > 100) {
            formatted = formatted.substring(0, 97) + "...";
        }
        previewLabel.setText(formatted);
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
            super(size, Style.MULTI_LINE);  // Importante: modo multi-liña
        }

        @Override
        public Interactable.Result handleKeyStroke(KeyStroke key) {
            if (key == null) return super.handleKeyStroke(null);

            KeyType kt = key.getKeyType();

            // Shift+Tab = inserir newline (delegando ao TextBox nativo)
            if (kt == KeyType.ReverseTab) {
                return super.handleKeyStroke(new KeyStroke(KeyType.Enter));
            }

            // Enter -> gardar
            if (kt == KeyType.Enter) {
                doSaveFromEditBox();
                return Interactable.Result.HANDLED;
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

    // caixa de busca: enter executa a busca, F3 vai ao seguinte resultado
    private class SearchTextBox extends TextBox {
        SearchTextBox(TerminalSize size) {
            super(size);
        }

        @Override
        public Interactable.Result handleKeyStroke(KeyStroke key) {
            if (key == null) return super.handleKeyStroke(null);

            KeyType kt = key.getKeyType();

            // Enter -> buscar
            if (kt == KeyType.Enter) {
                doSearch(getText());
                return Interactable.Result.HANDLED;
            }

            // F3 -> seguinte resultado
            if (kt == KeyType.F3) {
                if (key.isShiftDown()) {
                    prevSearchResult();
                } else {
                    nextSearchResult();
                }
                return Interactable.Result.HANDLED;
            }

            return super.handleKeyStroke(key);
        }
    }
}
