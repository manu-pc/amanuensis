package com.gui;

import com.git.GitHubSession;
import com.git.GitRepoService;
import com.local.FileCopyManager;
import com.local.HunspellChecker;
import com.local.LocHelper;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.fxmisc.richtext.InlineCssTextArea;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Editor principal de localización. Porte da antiga VentanaLocal da TUI a
 * JavaFX, engadindo subliñado ortográfico en liña (squiggles vermellas) co
 * editor enriquecido de RichTextFX e suxestións con clic dereito / F5.
 *
 * Mantén os mesmos controis de teclado que a TUI:
 *   AvPáx/RePáx = seguinte/anterior liña  (Ctrl = salto de 10)
 *   Enter       = gardar (+ auto-avance; opcionalmente revisión guiada antes)
 *   Shift+Tab   = inserir salto de liña
 *   F5          = revisión ortográfica guiada (palabra a palabra)
 *   F3 / Shift+F3 = seguinte / anterior resultado de busca
 */
public class LocalView {

    // accións posibles do panel de corrección ortográfica guiada.
    // (visible no paquete para poder dirixir o fluxo desde tests)
    enum CorrAction { REPLACE, IGNORE, IGNORE_ALL, ADD_DICT, CANCEL }

    // estilo das squiggles: subliñado vermello a trazos baixo a palabra
    private static final String SQUIGGLE_CSS =
            "-rtfx-underline-color: red; -rtfx-underline-width: 1.5; -rtfx-underline-dash-array: 2 2;";

    private final Stage stage;
    private final LocHelper locHelper;
    private final FileCopyManager fileCopyManager;
    private final String filename;
    private final HunspellChecker spellChecker;

    // executor dun só fío para non lanzar hunspell no fío de UI
    private final ExecutorService spellExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spell-check");
        t.setDaemon(true);
        return t;
    });

    // executor dun só fío para commit+push en segundo plano
    private final ExecutorService gitExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "git-push");
        t.setDaemon(true);
        return t;
    });

    private final GitRepoService gitRepoService = new GitRepoService(Path.of("lang"));
    // ruta relativa dentro de lang/, ou null se o ficheiro aberto non pertence ao repositorio
    private final Path relativeInRepo;
    // liñas (índice 1-based, coma na UI) gardadas nesta sesión, pendentes de subir
    private final SortedSet<Integer> editedLines = new TreeSet<>();

    private boolean textoBase = false;
    private boolean autoAdvance = true;
    private int currentIndex = 0;

    // compoñentes que precisan actualizarse
    private Label indexLabel;
    private Label keyLabel;
    private Label infoLabel;
    private TextArea literalArea;     // lectura: liña literal con indicadores ⏎
    private TextArea cleanArea;       // lectura: liña limpa
    private InlineCssTextArea editArea; // editor con subliñado ortográfico
    private Label spellLabel;
    private Label previewLabel;
    private Label statusLabel;        // antiga reappliedLabel
    private Label timeLabel;
    private TextField gotoField;
    private TextField searchField;

    // erros ortográficos da última comprobación
    private List<String> lastMisspelled = Collections.emptyList();
    private List<HunspellChecker.Region> lastRegions = Collections.emptyList();
    private ContextMenu suggestionsMenu;

    // palabras que o usuario decidiu ignorar ("ignorar todas") ou engadir ao
    // dicionario durante esta sesión: déixanse de marcar e de suxerir.
    private final Set<String> ignoredWords = new HashSet<>();
    private boolean spellOnEnter = false;  // revisar automaticamente ao premer Enter

    // estado da revisión guiada en curso
    private int corrSearchFrom = 0;
    private boolean corrSaveOnComplete = false;
    private HunspellChecker.Region corrTarget;
    private Stage corrDialog;

    // busca
    private String lastSearchTerm = "";
    private final List<Integer> searchResults = new ArrayList<>();
    private int searchResultIndex = -1;

    public LocalView(String filename, Stage stage) throws IOException {
        this.stage = stage;
        this.filename = filename;
        this.locHelper = new LocHelper(filename);
        this.fileCopyManager = new FileCopyManager(locHelper, filename);
        this.spellChecker = new HunspellChecker();
        this.relativeInRepo = computeRelativeInRepo(filename);
    }

    // devolve a ruta relativa dentro de lang/ se filename está baixo esa carpeta, ou null
    private static Path computeRelativeInRepo(String filename) {
        try {
            Path abs = Path.of(filename).toAbsolutePath().normalize();
            Path repoAbs = Path.of("lang").toAbsolutePath().normalize();
            if (abs.startsWith(repoAbs) && !abs.equals(repoAbs)) {
                return repoAbs.relativize(abs);
            }
        } catch (Exception ignored) {
            // rutas raras (ex: noutra unidade en Windows): trátase coma "fóra do repo"
        }
        return null;
    }

    public void show() {
        VBox root = new VBox(8);
        root.setPadding(new Insets(10));

        root.getChildren().add(buildNavBar());
        root.getChildren().add(buildSearchBar());
        root.getChildren().add(buildKeyInfo());

        // ---- liña literal (con indicadores) ----
        Label litTitle = title("liña literal (con indicadores):  [⏎ = marcador de salto de liña]");
        literalArea = readOnlyArea(3);
        root.getChildren().addAll(litTitle, literalArea);
        root.getChildren().add(new javafx.scene.control.Separator());

        // ---- liña limpa ----
        Label cleanTitle = title("liña limpa (sen indicadores):");
        cleanArea = readOnlyArea(3);
        root.getChildren().addAll(cleanTitle, cleanArea);
        root.getChildren().add(new javafx.scene.control.Separator());

        // ---- editor ----
        String editHint = spellChecker.isAvailable()
                ? "editar (texto limpo):  [Enter = gardar] [Shift+Tab = salto de liña] [F5 = suxestión] [clic dereito sobre palabra]"
                : "editar (texto limpo):  [Enter = gardar] [Shift+Tab = salto de liña]";
        Label editTitle = title(editHint);
        editArea = new InlineCssTextArea();
        editArea.setWrapText(true);
        editArea.setPrefHeight(140);
        VBox.setVgrow(editArea, Priority.ALWAYS);
        installEditorHandlers();
        root.getChildren().addAll(editTitle, editArea);

        // estado ortográfico
        spellLabel = new Label("");
        root.getChildren().add(spellLabel);

        // ---- preview ----
        Label previewTitle = title("preview (resultado formateado):");
        previewLabel = new Label("");
        previewLabel.setTextFill(Color.MEDIUMVIOLETRED);
        previewLabel.setWrapText(true);
        root.getChildren().addAll(previewTitle, previewLabel);
        root.getChildren().add(new javafx.scene.control.Separator());

        // estado / mensaxes
        statusLabel = new Label("listo");
        statusLabel.setTextFill(Color.SEAGREEN);
        root.getChildren().add(statusLabel);

        root.getChildren().add(buildBottomBar());

        updateView();

        Scene scene = new Scene(root, 920, 780);
        stage.setScene(scene);
        stage.show();
        Platform.runLater(editArea::requestFocus);

        stage.setOnHidden(e -> {
            spellExecutor.shutdownNow();
            gitExecutor.shutdownNow();
        });
    }

    // ---------------------------------------------------------------
    // construción de barras
    // ---------------------------------------------------------------

    private HBox buildNavBar() {
        Button prevBtn = new Button("<< anterior");
        prevBtn.setOnAction(e -> prevLine());
        Button nextBtn = new Button("siguiente >>");
        nextBtn.setOnAction(e -> nextLine());
        Button gotoEnd = new Button("ir a fin");
        gotoEnd.setOnAction(e -> {
            currentIndex = Math.max(0, locHelper.getLineCount() - 1);
            updateView();
        });

        gotoField = new TextField();
        gotoField.setPrefColumnCount(5);
        gotoField.setPromptText("nº");
        gotoField.setOnAction(e -> gotoFromBox());
        Button gotoBtn = new Button("ir");
        gotoBtn.setOnAction(e -> gotoFromBox());

        CheckBox baseCheck = new CheckBox("usar texto base");
        baseCheck.setSelected(textoBase);
        baseCheck.selectedProperty().addListener((o, was, is) -> {
            textoBase = is;
            updateView();
        });

        timeLabel = new Label("");
        timeLabel.setTextFill(Color.TEAL);

        indexLabel = new Label("");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, prevBtn, nextBtn, gotoEnd,
                new Label("ir:"), gotoField, gotoBtn, baseCheck,
                spacer, timeLabel, indexLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildSearchBar() {
        searchField = new TextField();
        searchField.setPrefColumnCount(28);
        searchField.setPromptText("buscar nas liñas, claves e texto limpo");
        searchField.setOnAction(e -> doSearch(searchField.getText()));
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F3) {
                if (e.isShiftDown()) prevSearchResult(); else nextSearchResult();
                e.consume();
            }
        });
        Button searchBtn = new Button("buscar");
        searchBtn.setOnAction(e -> doSearch(searchField.getText()));
        Button prevRes = new Button("< ant");
        prevRes.setOnAction(e -> prevSearchResult());
        Button nextRes = new Button("seg >");
        nextRes.setOnAction(e -> nextSearchResult());

        HBox bar = new HBox(8, new Label("buscar:"), searchField, searchBtn, prevRes, nextRes);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildKeyInfo() {
        keyLabel = new Label("");
        keyLabel.setTextFill(Color.GOLDENROD);
        infoLabel = new Label("");
        infoLabel.setTextFill(Color.TEAL);
        HBox bar = new HBox(16, keyLabel, infoLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildBottomBar() {
        Button save = new Button("gardar");
        save.setOnAction(e -> doSaveFromEditBox());
        Button saveChanges = new Button("gardar cambios");
        saveChanges.setOnAction(e -> doSaveChanges());
        Button pushBtn = new Button("gardar e subir a GitHub");
        pushBtn.setOnAction(e -> doSaveAndPush());
        Button refresh = new Button("refrescar");
        refresh.setOnAction(e -> updateView());
        Button dumpTxt = new Button("exportar .txt");
        dumpTxt.setOnAction(e -> doDumpToTxt());

        CheckBox autoAdvanceCheck = new CheckBox("auto-avanzar");
        autoAdvanceCheck.setSelected(autoAdvance);
        autoAdvanceCheck.selectedProperty().addListener((o, was, is) -> autoAdvance = is);

        // revisión ortográfica guiada automática ao premer Enter
        CheckBox spellOnEnterCheck = new CheckBox("corrixir ao premer Enter");
        spellOnEnterCheck.setSelected(spellOnEnter);
        spellOnEnterCheck.setDisable(!spellChecker.isAvailable());
        spellOnEnterCheck.selectedProperty().addListener((o, was, is) -> spellOnEnter = is);

        Button close = new Button("cerrar");
        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, save, saveChanges, pushBtn, refresh, dumpTxt,
                autoAdvanceCheck, spellOnEnterCheck, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static Label title(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        return l;
    }

    private static TextArea readOnlyArea(int rows) {
        TextArea ta = new TextArea();
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(rows);
        ta.setFont(Font.font("monospaced"));
        return ta;
    }

    // ---------------------------------------------------------------
    // editor: teclado, subliñado e suxestións
    // ---------------------------------------------------------------

    private void installEditorHandlers() {
        // preview en vivo (barato: reapplyFormatting é puro)
        editArea.textProperty().addListener((o, was, is) -> updatePreview());

        // comprobación ortográfica con debounce (non bloquea o tecleo)
        if (spellChecker.isAvailable()) {
            editArea.multiPlainChanges()
                    .successionEnds(Duration.ofMillis(350))
                    .subscribe(ignore -> scheduleSpellCheck());
        }

        editArea.addEventFilter(KeyEvent.KEY_PRESSED, this::onEditorKey);

        // clic dereito: mover o cursor á palabra clicada para as suxestións
        editArea.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                editArea.hit(e.getX(), e.getY())
                        .getCharacterIndex()
                        .ifPresent(editArea::moveTo);
            }
        });
        editArea.setOnContextMenuRequested(e -> {
            showSuggestionsAt(editArea.getCaretPosition(), e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private void onEditorKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> {
                if (!e.isShiftDown()) {
                    e.consume();
                    if (spellOnEnter && spellChecker.isAvailable()) {
                        // revisión guiada e, ao rematala, gárdase a liña
                        runGuidedSpellCheck(true);
                    } else {
                        doSaveFromEditBox();
                    }
                }
            }
            case TAB -> {
                // Shift+Tab insire un salto de liña (mantendo a convención da TUI)
                if (e.isShiftDown()) {
                    editArea.replaceSelection("\n");
                    e.consume();
                }
            }
            case F5 -> {
                runGuidedSpellCheck(false);
                e.consume();
            }
            case PAGE_UP -> {
                if (e.isControlDown()) skipBackward(); else prevLine();
                e.consume();
            }
            case PAGE_DOWN -> {
                if (e.isControlDown()) skipForward(); else nextLine();
                e.consume();
            }
            case F3 -> {
                if (e.isShiftDown()) prevSearchResult(); else nextSearchResult();
                e.consume();
            }
            default -> { /* tecleo normal */ }
        }
    }

    private void scheduleSpellCheck() {
        if (!spellChecker.isAvailable()) return;
        final String text = editArea.getText();
        spellExecutor.submit(() -> {
            List<HunspellChecker.Region> regions = spellChecker.getMisspelledRegions(text);
            Platform.runLater(() -> applySpellResult(text, regions));
        });
    }

    private void applySpellResult(String checkedText, List<HunspellChecker.Region> regions) {
        // descartar resultado obsoleto: o texto cambiou mentres comprobabamos
        if (!editArea.getText().equals(checkedText)) return;

        int len = editArea.getLength();
        if (len > 0) editArea.setStyle(0, len, "");

        LinkedHashSet<String> words = new LinkedHashSet<>();
        List<HunspellChecker.Region> kept = new ArrayList<>();
        for (HunspellChecker.Region r : regions) {
            // non marcar as palabras ignoradas / engadidas ao dicionario nesta sesión
            if (ignoredWords.contains(r.word())) continue;
            if (r.end() <= len) {
                editArea.setStyle(r.start(), r.end(), SQUIGGLE_CSS);
            }
            kept.add(r);
            words.add(r.word());
        }
        lastRegions = kept;
        lastMisspelled = new ArrayList<>(words);

        if (checkedText.isBlank()) {
            spellLabel.setText("");
        } else if (lastMisspelled.isEmpty()) {
            spellLabel.setText("✓ sen erros");
            spellLabel.setTextFill(Color.SEAGREEN);
        } else {
            String joined = String.join(", ", lastMisspelled);
            if (joined.length() > 70) joined = joined.substring(0, 67) + "...";
            spellLabel.setText("⚠ " + joined + "   [F5 = revisar | clic dereito = suxestión]");
            spellLabel.setTextFill(Color.DARKORANGE);
        }
    }

    // ---------------------------------------------------------------
    // revisión ortográfica guiada (F5 / ao premer Enter)
    // ---------------------------------------------------------------

    /**
     * Inicia a revisión ortográfica guiada, palabra a palabra, sobre o texto
     * actual do editor. Móstrase un panel por cada palabra incorrecta.
     *
     * @param saveOnComplete se true, gárdase a liña ao completar a revisión
     *                       (uso desde Enter); ao cancelar NON se garda.
     */
    void runGuidedSpellCheck(boolean saveOnComplete) {
        if (!spellChecker.isAvailable()) {
            if (saveOnComplete) doSaveFromEditBox();
            return;
        }
        corrSaveOnComplete = saveOnComplete;
        corrSearchFrom = 0;
        showNextCorrection();
    }

    // busca o seguinte erro non ignorado e abre o seu panel; se non queda, remata.
    private void showNextCorrection() {
        String text = editArea.getText();
        HunspellChecker.Region target = null;
        for (HunspellChecker.Region r : spellChecker.getMisspelledRegions(text)) {
            if (r.start() >= corrSearchFrom && !ignoredWords.contains(r.word())) {
                target = r;
                break;
            }
        }
        if (target == null) {
            finishCorrection(true);
            return;
        }
        corrTarget = target;
        // resaltar a palabra en revisión no editor
        editArea.selectRange(target.start(), target.end());
        List<String> suggestions = spellChecker.getSuggestions(target.word());
        corrDialog = buildCorrectionDialog(text, target, suggestions);
        corrDialog.show();
    }

    /**
     * Aplica a acción escollida sobre a palabra actual e continúa coa seguinte.
     * Exposto no paquete para poder dirixir o fluxo desde tests sen interacción.
     */
    void applyCorrection(CorrAction action, String replacement) {
        HunspellChecker.Region target = corrTarget;
        if (corrDialog != null) {
            corrDialog.close();
            corrDialog = null;
        }
        if (target == null) return;

        switch (action) {
            case REPLACE -> {
                String repl = replacement == null ? "" : replacement;
                editArea.replaceText(target.start(), target.end(), repl);
                corrSearchFrom = target.start() + repl.length();
            }
            case IGNORE -> corrSearchFrom = target.end(); // saltar esta ocorrencia
            case IGNORE_ALL -> ignoredWords.add(target.word());
            case ADD_DICT -> {
                ignoredWords.add(target.word());
                spellChecker.addToDictionary(target.word());
            }
            case CANCEL -> {
                finishCorrection(false);
                return;
            }
        }
        showNextCorrection();
    }

    private void finishCorrection(boolean completed) {
        corrTarget = null;
        editArea.deselect();
        scheduleSpellCheck(); // refrescar squiggles tras os cambios
        if (completed) {
            status("revisión ortográfica completa", Color.SEAGREEN);
            if (corrSaveOnComplete) doSaveFromEditBox();
        } else {
            status("revisión ortográfica cancelada", Color.DARKORANGE);
        }
        corrSaveOnComplete = false;
    }

    // constrúe o panel modal para unha palabra: contexto + suxestións + accións
    private Stage buildCorrectionDialog(String sentence, HunspellChecker.Region target,
                                        List<String> suggestions) {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Ortografía: " + target.word());

        Label header = new Label("Palabra posiblemente incorrecta: \"" + target.word() + "\"");
        header.setStyle("-fx-font-weight: bold;");

        // a frase co erro resaltado en vermello
        Text before = new Text(sentence.substring(0, target.start()));
        Text bad = new Text(sentence.substring(target.start(), target.end()));
        bad.setStyle("-fx-fill: crimson; -fx-font-weight: bold;");
        Text after = new Text(sentence.substring(target.end()));
        TextFlow context = new TextFlow(before, bad, after);
        context.setMaxWidth(440);

        Label suggTitle = new Label(suggestions.isEmpty()
                ? "Sen suxestións dispoñibles. Escolle unha acción:"
                : "Suxestións (dobre clic ou «Substituír»):");
        ListView<String> suggList = new ListView<>(FXCollections.observableArrayList(suggestions));
        suggList.setPrefHeight(120);
        if (!suggestions.isEmpty()) suggList.getSelectionModel().selectFirst();
        suggList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String sel = suggList.getSelectionModel().getSelectedItem();
                if (sel != null) applyCorrection(CorrAction.REPLACE, sel);
            }
        });

        Button replaceBtn = new Button("Substituír");
        replaceBtn.setDefaultButton(true);
        replaceBtn.setDisable(suggestions.isEmpty());
        replaceBtn.setOnAction(e -> {
            String sel = suggList.getSelectionModel().getSelectedItem();
            if (sel != null) applyCorrection(CorrAction.REPLACE, sel);
        });
        Button ignoreBtn = new Button("Ignorar");
        ignoreBtn.setOnAction(e -> applyCorrection(CorrAction.IGNORE, null));
        Button ignoreAllBtn = new Button("Ignorar todas");
        ignoreAllBtn.setOnAction(e -> applyCorrection(CorrAction.IGNORE_ALL, null));
        Button addBtn = new Button("Engadir ao dicionario");
        addBtn.setOnAction(e -> applyCorrection(CorrAction.ADD_DICT, null));
        Button cancelBtn = new Button("Cancelar");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> applyCorrection(CorrAction.CANCEL, null));

        HBox actions = new HBox(8, replaceBtn, ignoreBtn, ignoreAllBtn, addBtn, cancelBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, header, context, suggTitle, suggList, actions);
        box.setPadding(new Insets(14));

        dlg.setScene(new Scene(box, 640, 360));
        // pechar coa X equivale a cancelar a revisión
        dlg.setOnCloseRequest(e -> {
            e.consume();
            applyCorrection(CorrAction.CANCEL, null);
        });
        return dlg;
    }

    // mostra un menú de suxestións para a palabra na posición indicada (offset)
    private void showSuggestionsAt(int caretOffset, double screenX, double screenY) {
        if (!spellChecker.isAvailable()) return;
        HunspellChecker.Region region = regionAt(caretOffset);
        if (region == null) return; // non hai erro nesa palabra

        if (suggestionsMenu != null) suggestionsMenu.hide();
        suggestionsMenu = new ContextMenu();

        List<String> suggestions = spellChecker.getSuggestions(region.word());
        if (suggestions.isEmpty()) {
            MenuItem none = new MenuItem("(sen suxestión para \"" + region.word() + "\")");
            none.setDisable(true);
            suggestionsMenu.getItems().add(none);
        } else {
            for (String s : suggestions) {
                MenuItem item = new MenuItem(s);
                item.setOnAction(e -> replaceRegion(region, s));
                suggestionsMenu.getItems().add(item);
            }
        }
        suggestionsMenu.getItems().add(new SeparatorMenuItem());
        MenuItem ignoreAll = new MenuItem("Ignorar todas");
        ignoreAll.setOnAction(e -> {
            ignoredWords.add(region.word());
            scheduleSpellCheck();
        });
        MenuItem addDict = new MenuItem("Engadir ao dicionario");
        addDict.setOnAction(e -> {
            ignoredWords.add(region.word());
            spellChecker.addToDictionary(region.word());
            scheduleSpellCheck();
        });
        suggestionsMenu.getItems().addAll(ignoreAll, addDict);

        suggestionsMenu.show(editArea, screenX, screenY);
    }

    // devolve a rexión de erro que contén o offset, ou null
    private HunspellChecker.Region regionAt(int offset) {
        for (HunspellChecker.Region r : lastRegions) {
            if (offset >= r.start() && offset <= r.end()) return r;
        }
        return null;
    }

    // substitúe a palabra exacta da rexión pola suxestión (por offset, preciso)
    private void replaceRegion(HunspellChecker.Region r, String replacement) {
        int len = editArea.getLength();
        if (r.start() <= len && r.end() <= len) {
            editArea.replaceText(r.start(), r.end(), replacement);
        }
        updatePreview();
        scheduleSpellCheck();
    }

    // ---------------------------------------------------------------
    // lóxica de localización (porte directo de VentanaLocal)
    // ---------------------------------------------------------------

    private String formatLiteralForDisplay(String literal) {
        return literal
                .replace("&", "⏎&\n")
                .replace("#", "⏎#\n")
                .replace("\n", "⏎\\n\n");
    }

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
            searchResultIndex = 0;
            for (int i = 0; i < searchResults.size(); i++) {
                if (searchResults.get(i) >= currentIndex) {
                    searchResultIndex = i;
                    break;
                }
            }
            gotoSearchResult();
        } else {
            status("non se atopou: \"" + term + "\"", Color.DARKORANGE);
        }
    }

    private void gotoSearchResult() {
        if (searchResults.isEmpty()) return;
        currentIndex = searchResults.get(searchResultIndex);
        updateView();
        status(String.format("resultado %d/%d para \"%s\"",
                searchResultIndex + 1, searchResults.size(), lastSearchTerm), Color.TEAL);
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

    private void doDumpToTxt() {
        String base = filename.endsWith(".json")
                ? filename.substring(0, filename.length() - 5)
                : filename;
        Path outPath = Path.of(base + ".txt");

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < locHelper.getLineCount(); i++) {
            lines.add(locHelper.stripFormatting(i));
        }

        try {
            Files.writeString(outPath, String.join("\n", lines));
            status("exportado a: " + outPath, Color.SEAGREEN);
        } catch (IOException e) {
            status("erro ao exportar: " + e.getMessage(), Color.CRIMSON);
        }
    }

    private void doSaveFromEditBox() {
        String newPlain = editArea.getText();

        if (newPlain.isEmpty() && !textoBase) {
            status("introduce texto antes de gardar", Color.DARKORANGE);
            return;
        }

        String formatted = locHelper.reapplyFormatting(currentIndex, newPlain);

        try {
            fileCopyManager.updateLine(currentIndex, formatted);
            editedLines.add(currentIndex + 1);
            String shortFmt = formatted.length() > 60 ? formatted.substring(0, 57) + "..." : formatted;
            String savedMsg = "gardado [" + (currentIndex + 1) + "]: " + shortFmt;
            status(savedMsg, Color.SEAGREEN);

            if (autoAdvance && currentIndex < locHelper.getLineCount() - 1) {
                currentIndex++;
                updateView();
                status(savedMsg, Color.SEAGREEN);
            }
        } catch (IOException e) {
            status("erro ao gardar: " + e.getMessage(), Color.CRIMSON);
        }
    }

    private void doSaveChanges() {
        try {
            fileCopyManager.saveToOriginal();
            updateView();
            status("cambios gardados no ficheiro orixinal", Color.SEAGREEN);
        } catch (IOException e) {
            status("erro ao gardar cambios: " + e.getMessage(), Color.CRIMSON);
        }
    }

    // ---------------------------------------------------------------
    // gardar e subir a GitHub
    // ---------------------------------------------------------------

    private void doSaveAndPush() {
        if (relativeInRepo == null) {
            status("este ficheiro non está dentro de lang/; non se pode subir a GitHub", Color.DARKORANGE);
            return;
        }
        if (!gitRepoService.isCloned()) {
            status("o proxecto de tradución non está descargado (pantalla inicial)", Color.DARKORANGE);
            return;
        }
        GitHubSession session = GitHubSession.getInstance();
        if (!session.isLoggedIn()) {
            status("inicia sesión en GitHub primeiro (pantalla inicial)", Color.DARKORANGE);
            return;
        }

        try {
            fileCopyManager.saveToOriginal();
        } catch (IOException ex) {
            status("erro ao gardar cambios: " + ex.getMessage(), Color.CRIMSON);
            return;
        }
        updateView();

        if (editedLines.isEmpty()) {
            status("non hai liñas editadas nesta sesión para subir", Color.DARKORANGE);
            return;
        }

        String subject = "Tradución liñas (" + GitRepoService.compressRanges(new ArrayList<>(editedLines)) + ")";
        showCommitMessageDialog(subject, session);
    }

    private void showCommitMessageDialog(String proposedSubject, GitHubSession session) {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Subir a GitHub");

        Label label = new Label("Mensaxe da actualización (podes editala):");
        TextField subjectField = new TextField(proposedSubject);
        subjectField.setPrefColumnCount(40);

        Button confirmBtn = new Button("subir");
        confirmBtn.setDefaultButton(true);
        Button cancelBtn = new Button("cancelar");
        cancelBtn.setCancelButton(true);

        confirmBtn.setOnAction(e -> {
            dlg.close();
            String subject = subjectField.getText().trim();
            runCommitAndPush(subject.isEmpty() ? proposedSubject : subject, session);
        });
        cancelBtn.setOnAction(e -> dlg.close());

        HBox buttons = new HBox(8, confirmBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(10, label, subjectField, buttons);
        box.setPadding(new Insets(14));

        dlg.setScene(new Scene(box, 460, 150));
        dlg.show();
    }

    private void runCommitAndPush(String subject, GitHubSession session) {
        status("subindo a GitHub...", Color.TEAL);

        com.git.GitHubAuth.GitHubUser user = session.getUser();
        String authorName = user != null ? user.name() : "amanuensis";
        String authorEmail = user != null ? user.noreplyEmail() : "amanuensis@users.noreply.github.com";
        Path targetFile = relativeInRepo;

        gitExecutor.submit(() -> {
            GitRepoService.PushOutcome outcome = gitRepoService.commitAndPush(
                    targetFile, subject, authorName, authorEmail, session.getToken());
            Platform.runLater(() -> {
                if (outcome instanceof GitRepoService.PushOutcome.Success) {
                    editedLines.clear();
                    status("subido a GitHub correctamente", Color.SEAGREEN);
                } else if (outcome instanceof GitRepoService.PushOutcome.Conflict c) {
                    editedLines.clear();
                    status("ocorreu un problema, outro usuario editou as liñas (" + c.lineRanges()
                            + ") ao mesmo tempo que ti. os teus cambios foron gardados nunha rama separada e non se perderon.",
                            Color.CRIMSON);
                } else if (outcome instanceof GitRepoService.PushOutcome.Failure f) {
                    status("erro ao subir: " + f.reason(), Color.CRIMSON);
                }
            });
        });
    }

    private void nextLine() {
        if (currentIndex < locHelper.getLineCount() - 1) {
            currentIndex++;
            updateView();
        }
    }

    private void prevLine() {
        if (currentIndex > 0) {
            currentIndex--;
            updateView();
        }
    }

    private void skipForward() {
        currentIndex = Math.min(locHelper.getLineCount() - 1, currentIndex + 10);
        updateView();
    }

    private void skipBackward() {
        currentIndex = Math.max(0, currentIndex - 10);
        updateView();
    }

    private void updateView() {
        int total = locHelper.getLineCount();
        if (total == 0) {
            indexLabel.setText("0/0");
            keyLabel.setText("");
            infoLabel.setText("");
            literalArea.setText("(ficheiro baleiro)");
            cleanArea.setText("");
            editArea.replaceText("");
            spellLabel.setText("");
            lastMisspelled = Collections.emptyList();
            lastRegions = Collections.emptyList();
            previewLabel.setText("");
            status("", Color.SEAGREEN);
            return;
        }

        timeLabel.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        indexLabel.setText(String.format("%d / %d", currentIndex + 1, total));

        String key = locHelper.getKey(currentIndex);
        String literal = locHelper.getOriginal(currentIndex);
        String clean = locHelper.stripFormatting(currentIndex);

        keyLabel.setText("key: " + key);

        int newlineCount = locHelper.countNewlines(currentIndex);
        StringBuilder info = new StringBuilder();
        info.append("[").append(newlineCount).append(" liñas]");
        if (locHelper.hasColorMarkers(currentIndex)) info.append(" [cor *]");
        if (locHelper.hasTildeMarkers(currentIndex)) info.append(" [efecto ~]");
        if (locHelper.hasBackslashOMarkers(currentIndex)) info.append(" [\\O @]");
        if (locHelper.hasBackslashIMarkers(currentIndex)) info.append(" [\\I $]");
        if (locHelper.hasPauseMarkers(currentIndex)) info.append(" [pausa]");
        infoLabel.setText(info.toString());

        literalArea.setText(formatLiteralForDisplay(literal));
        cleanArea.setText(clean);

        editArea.replaceText(textoBase ? clean : "");

        updatePreview();
        status("listo", Color.SEAGREEN);

        spellLabel.setText("");
        lastMisspelled = Collections.emptyList();
        lastRegions = Collections.emptyList();

        gotoField.setText(String.valueOf(currentIndex + 1));
    }

    private void updatePreview() {
        String newPlain = editArea.getText();
        if (newPlain.isEmpty()) {
            previewLabel.setText("(introduce texto para ver o preview)");
            return;
        }
        String formatted = locHelper.reapplyFormatting(currentIndex, newPlain);
        if (formatted.length() > 120) formatted = formatted.substring(0, 117) + "...";
        previewLabel.setText(formatted);
    }

    private void gotoFromBox() {
        String txt = gotoField.getText().trim();
        if (txt.isEmpty()) {
            status("introduce un número de liña (1 - " + locHelper.getLineCount() + ")", Color.DARKORANGE);
            return;
        }
        try {
            int n = Integer.parseInt(txt);
            if (n < 1 || n > locHelper.getLineCount()) {
                status("número fóra de rango: 1 - " + locHelper.getLineCount(), Color.DARKORANGE);
                return;
            }
            currentIndex = n - 1;
            updateView();
        } catch (NumberFormatException e) {
            status("número non válido", Color.DARKORANGE);
        }
    }

    private void status(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setTextFill(color);
    }
}
