package com.gui;

import com.AppDir;
import com.git.GitHubSession;
import com.git.GitRepoService;
import com.git.KeyMerge;
import com.google.gson.JsonObject;
import com.local.EditLedger;
import com.local.HunspellChecker;
import com.local.JsonIo;
import com.local.LedgerStore;
import com.local.LocHelper;
import com.local.TranslationStore;
import com.local.map.MessageMap;
import com.local.map.MessagePropagator;
import com.local.markers.MarkerRenderer;
import com.local.markers.MarkerSummary;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final TranslationStore store;
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

    // O repo git é a carpeta base (a do jar), que contén .git e lang/.
    private final GitRepoService gitRepoService = new GitRepoService(AppDir.base());
    // ruta relativa dentro de lang/, ou null se o ficheiro aberto non pertence ao repositorio
    private final Path relativeInRepo;
    // edicións que quedaran no rexistro e se restauraron ao abrir (caída da app)
    private final int pendingRestoredOnOpen;
    // mensaxes que se repiten entre capítulos (baleiro se non hai message-map.json)
    private final MessageMap messageMap;

    private boolean textoBase = false;
    private boolean autoAdvance = true;
    private int currentIndex = 0;

    // compoñentes que precisan actualizarse
    private Label indexLabel;
    private Label pendingLabel;       // "N pendentes de subir a GitHub"
    private Label keyLabel;
    private Label infoLabel;
    private Label repeatLabel;        // "⚠ xa aparece no capítulo N"
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
        this.relativeInRepo = computeRelativeInRepo(filename);

        // As gardas van directas ao ficheiro real; o rexistro de edicións (fóra de
        // lang/) é quen sabe que claves son do usuario e con que valor de partida.
        Path file = Path.of(filename).toAbsolutePath().normalize();
        Path repoRoot = relativeInRepo != null ? AppDir.base() : null;
        EditLedger ledger = EditLedger.openFor(file, repoRoot);
        // mapa de mensaxes repetidas entre capítulos; se non está o ficheiro,
        // MessageMap.load devolve un baleiro e todo funciona sen propagación
        this.messageMap = MessageMap.load(repoRoot);
        this.store = new TranslationStore(locHelper, file, ledger, repoRoot, messageMap);
        // Se a app morreu no medio dunha garda, o valor do rexistro non chegou ao
        // ficheiro: restáurase agora.
        int restored = store.applyPendingFromLedger();
        this.pendingRestoredOnOpen = restored;

        this.spellChecker = new HunspellChecker();
    }

    // Devolve a ruta relativa á raíz do repo (a carpeta actual, que contén .git e
    // lang/) se filename está dentro dela, ou null. O resultado inclúe o prefixo
    // lang/ (ex: lang/chapter1/strings.json), que é o que git precisa para o commit.
    private static Path computeRelativeInRepo(String filename) {
        try {
            Path abs = Path.of(filename).toAbsolutePath().normalize();
            Path repoAbs = AppDir.base();
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
        updatePendingIndicator();
        if (pendingRestoredOnOpen > 0) {
            status("restauráronse " + pendingRestoredOnOpen
                    + " liña(s) pendentes que non chegaran a gardarse (a app pechouse a medio gardar)",
                    Color.DARKORANGE);
        }

        // mentres haxa texto a medio escribir, o aviso de actualización cala: un
        // reinicio non perde nada gardado, pero si perdería a caixa de edición
        UpdateUi.setBusyEditing(() -> !editArea.getText().isBlank());

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
        pendingLabel = new Label("");
        pendingLabel.setTextFill(Color.DARKORANGE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, prevBtn, nextBtn, gotoEnd,
                new Label("ir:"), gotoField, gotoBtn, baseCheck,
                spacer, timeLabel, pendingLabel, indexLabel);
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
        repeatLabel = new Label("");
        repeatLabel.setTextFill(Color.DARKORANGE);
        repeatLabel.setWrapText(true);
        HBox bar = new HBox(16, keyLabel, infoLabel, repeatLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildBottomBar() {
        Button save = new Button("gardar");
        save.setOnAction(e -> doSaveFromEditBox());
        Button pushBtn = new Button("gardar e subir a GitHub");
        pushBtn.setOnAction(e -> doSaveAndPush());
        Button viewChanges = new Button("ver cambios");
        viewChanges.setOnAction(e -> doPullChanges());
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

        HBox bar = new HBox(8, save, pushBtn, viewChanges, dumpTxt,
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
            if (!store.save(currentIndex, formatted)) {
                // o texto era idéntico ao que xa había: non se marca nada, e así unha
                // garda accidental non pode subir nin desfacer o traballo doutra persoa
                status("sen cambios: esa liña xa tiña ese texto", Color.DARKORANGE);
                return;
            }
            String shortFmt = formatted.length() > 60 ? formatted.substring(0, 57) + "..." : formatted;
            String savedMsg = "gardado [" + (currentIndex + 1) + "]: " + shortFmt;
            MessagePropagator.Result prop = store.lastPropagation();
            Color savedColor = Color.SEAGREEN;
            if (!prop.isEmpty()) {
                savedMsg = savedMsg + "  |  " + prop.summary();
                // sobrescribir outra tradución ou perder claves merece verse
                if (!prop.overwritten().isEmpty() || !prop.missing().isEmpty()
                        || !prop.errors().isEmpty()) {
                    savedColor = Color.DARKORANGE;
                }
            }
            status(savedMsg, savedColor);

            if (autoAdvance && currentIndex < locHelper.getLineCount() - 1) {
                currentIndex++;
                updateView();
                status(savedMsg, savedColor); // updateView deixara "listo"
            } else {
                updatePendingIndicator();
            }
        } catch (TranslationStore.KeyMissingException e) {
            // chegou un cambio de estrutura do servidor: recargar en vez de escribir
            // pola posición (iso metía a tradución na clave equivocada)
            status("o ficheiro cambiou no servidor; recargando...", Color.DARKORANGE);
            reloadInPlace(currentIndex);
        } catch (IOException e) {
            status("erro ao gardar: " + e.getMessage(), Color.CRIMSON);
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

        // Xa non hai nada que volcar: cada garda foi directa ao ficheiro real.
        if (store.pendingCount() == 0) {
            status("non hai liñas editadas pendentes de subir", Color.DARKORANGE);
            return;
        }

        String subject = "Tradución liñas ("
                + GitRepoService.compressRanges(store.pendingLineNumbers()) + ")";
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
        Path targetFile = relativeInRepo;
        // Só se poden mover as claves rexistradas: o resto do ficheiro vai coma no
        // repositorio, aínda que o disco local estea desactualizado.
        Map<String, KeyMerge.KeyEdit> edits = KeyMerge.fromLedger(store.ledger().entries());
        ProgressDialog dlg = new ProgressDialog(stage, "Subindo cambios",
                "Subindo os teus cambios a GitHub...");
        dlg.show();

        gitExecutor.submit(() -> {
            com.git.GitHubAuth.GitHubUser user = ensureUser(session);
            if (user == null) { // ensureUser xa avisou
                Platform.runLater(dlg::close);
                return;
            }
            GitRepoService.PushOutcome outcome = gitRepoService.commitAndPushKeys(
                    targetFile, edits, subject, user.name(), user.noreplyEmail(), session.getToken());
            Platform.runLater(() -> {
                dlg.close();
                applyPushOutcome(outcome, edits.keySet());
            });
        });
    }

    /**
     * Devolve o usuario real conectado, pedíndoo a GitHub se aínda non o temos
     * (sesión restaurada do disco). Se non se pode obter, avisa e devolve null:
     * a autoría dun commit debe ser sempre a do usuario real. Chamar en 2º plano.
     */
    private com.git.GitHubAuth.GitHubUser ensureUser(GitHubSession session) {
        com.git.GitHubAuth.GitHubUser user = session.getUser();
        if (user == null) {
            try {
                user = com.git.GitHubAuth.fetchUser(session.getToken());
                session.setUser(user);
            } catch (Exception ex) {
                Platform.runLater(() -> status(
                        "non se puido verificar a túa conta de GitHub; comproba a conexión e téntao de novo",
                        Color.CRIMSON));
                return null;
            }
        }
        return user;
    }

    /**
     * Trata o resultado dun commit+push (fío de UI). Compartido por subir e por ver
     * cambios.
     *
     * O rexistro de edicións só se limpa cando as edicións chegaron a algures: se o
     * push falla, mantense para poder reintentar (antes borrábase sempre e o traballo
     * quedaba sen rastro).
     *
     * @param pushedKeys claves que se intentaron subir nesta operación
     */
    private void applyPushOutcome(GitRepoService.PushOutcome outcome, Set<String> pushedKeys) {
        try {
            if (outcome instanceof GitRepoService.PushOutcome.Success) {
                store.ledger().remove(pushedKeys);
                status("subido a GitHub correctamente", Color.SEAGREEN);
                reloadInPlace(currentIndex);
            } else if (outcome instanceof GitRepoService.PushOutcome.Conflict c) {
                // As nosas versións quedaron na rama de conflito (e na PR), e a árbore
                // local volveu ao estado do servidor: sacar esas claves do rexistro,
                // que doutro xeito volverían dar conflito unha e outra vez.
                store.ledger().remove(pushedKeys);
                String base = "ocorreu un problema, outro usuario editou as liñas (" + c.lineRanges()
                        + ") ao mesmo tempo que ti. os teus cambios foron gardados nunha rama separada e non se perderon.";
                if (c.prUrl() != null) {
                    status(base + " abriuse unha proposta de fusión: " + c.prUrl(), Color.CRIMSON);
                    openInBrowser(c.prUrl());
                } else {
                    status(base + " (rama: " + c.fallbackBranch() + ")", Color.CRIMSON);
                }
                reloadInPlace(currentIndex);
            } else if (outcome instanceof GitRepoService.PushOutcome.Failure f) {
                status("erro ao subir (os teus cambios seguen gardados): " + f.reason(), Color.CRIMSON);
            }
        } catch (IOException e) {
            status("erro ao actualizar o rexistro de edicións: " + e.getMessage(), Color.CRIMSON);
        }
        updatePendingIndicator();
    }

    // Abrir unha URL no navegador do sistema nun proceso á parte. Non usar
    // java.awt.Desktop: inicializa o toolkit AWT dende o fío de JavaFX e en
    // Linux pode conxelar a app.
    private void openInBrowser(String url) {
        new Thread(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            try {
                if (os.contains("win")) {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }
            } catch (IOException ignored) {
                // sen abridor dispoñible: a URL xa se amosa na barra de estado
            }
        }, "open-browser").start();
    }

    // ---------------------------------------------------------------
    // ver cambios: traer do servidor sen sobrescribir os cambios propios
    // ---------------------------------------------------------------

    private void doPullChanges() {
        if (!gitRepoService.isCloned()) {
            status("o proxecto de tradución non está descargado (pantalla inicial)", Color.DARKORANGE);
            return;
        }
        GitHubSession session = GitHubSession.getInstance();
        if (!session.isLoggedIn()) {
            status("inicia sesión en GitHub para ver cambios (pantalla inicial)", Color.DARKORANGE);
            return;
        }
        ProgressDialog dlg = new ProgressDialog(stage, "Buscando cambios",
                "Buscando cambios no servidor...");
        dlg.show();
        boolean hasPendingEdits = LedgerStore.hasPendingEdits(AppDir.base());
        gitExecutor.submit(() -> {
            // "cambios locais sen subir" = edicións rexistradas. Xa non se pregunta por
            // ficheiros simplemente modificados: un ficheiro sucio sen rexistro non é
            // traballo do usuario (era o que arrastraba claves obsoletas aos commits).
            if (hasPendingEdits) {
                // hai cambios locais sen subir: segundo o estado do remoto, ofrecer subir
                GitRepoService.RemoteState remote = gitRepoService.checkRemoteAdvance(session.getToken());
                Platform.runLater(() -> {
                    dlg.close();
                    switch (remote) {
                        case AHEAD -> promptUpload(session, GitSync.MSG_DIVERGED);
                        case NOT_AHEAD -> promptUpload(session, GitSync.MSG_LOCAL_ONLY);
                        case UNAVAILABLE -> status(
                                "non se puido contactar co servidor; téntao máis tarde", Color.CRIMSON);
                    }
                });
                return;
            }
            // sen cambios locais: pull seguro normal
            GitRepoService.PullOutcome outcome = gitRepoService.pullIfSafe(session.getToken());
            Platform.runLater(() -> {
                dlg.close();
                applyPullOutcome(outcome);
            });
        });
    }

    private void applyPullOutcome(GitRepoService.PullOutcome outcome) {
        switch (outcome) {
            case UP_TO_DATE -> status("estás ao día (sen cambios novos)", Color.SEAGREEN);
            case SKIPPED_DIRTY -> status(
                    "hai cambios locais sen rexistrar neste repositorio; revísaos antes de actualizar",
                    Color.DARKORANGE);
            case DIVERGED -> promptUpload(GitHubSession.getInstance(), GitSync.MSG_DIVERGED);
            case FAILED -> status("erro ao buscar cambios no servidor", Color.CRIMSON);
            // Sempre se recarga: as edicións pendentes non se perden (están no rexistro,
            // e reaplícanse por riba do que veña do servidor), e a vista deixa de amosar
            // texto vello, que era o que facía que a seguinte garda partise dun estado
            // obsoleto.
            case UPDATED -> reloadInPlace(currentIndex);
        }
    }

    /** Pregunta se subir os cambios locais (a mensaxe cambia segundo o remoto avanzase ou non). */
    private void promptUpload(GitHubSession session, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        a.setTitle("Cambios sen subir");
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) {
                uploadAllLocalChanges(session);
            } else {
                status("os teus cambios seguen sen subir", Color.DARKORANGE);
            }
        });
    }

    /** Sobe todas as edicións rexistradas do repositorio (con reconciliación/PR). */
    private void uploadAllLocalChanges(GitHubSession session) {
        ProgressDialog dlg = new ProgressDialog(stage, "Subindo cambios",
                "Subindo os teus cambios ao servidor...");
        dlg.show();
        gitExecutor.submit(() -> {
            com.git.GitHubAuth.GitHubUser user = ensureUser(session);
            if (user == null) {
                Platform.runLater(dlg::close);
                return;
            }
            // Sobe TODOS os rexistros pendentes do repo (non só o desta fiestra); o
            // propio GitRepoService xa os limpa segundo o resultado.
            GitRepoService.PushOutcome outcome = gitRepoService.commitAndPushAllDirty(
                    "Actualización de tradución", user.name(), user.noreplyEmail(), session.getToken());
            Platform.runLater(() -> {
                dlg.close();
                applyAllDirtyPushOutcome(outcome);
            });
        });
    }

    /**
     * Trata o resultado dunha subida de TODOS os rexistros pendentes (ver
     * {@link #uploadAllLocalChanges}). A diferenza de {@link #applyPushOutcome},
     * o propio {@link GitRepoService} xa limpou os rexistros afectados (poden ser
     * doutros ficheiros que esta fiestra non ten en memoria); aquí só se refresca
     * a vista para que o rexistro deste ficheiro (posiblemente mudado por outro
     * proceso) volva coincidir.
     */
    private void applyAllDirtyPushOutcome(GitRepoService.PushOutcome outcome) {
        if (outcome instanceof GitRepoService.PushOutcome.Success) {
            status("subido a GitHub correctamente", Color.SEAGREEN);
            reloadInPlace(currentIndex);
        } else if (outcome instanceof GitRepoService.PushOutcome.Conflict c) {
            String base = "ocorreu un problema, outro usuario editou as liñas (" + c.lineRanges()
                    + ") ao mesmo tempo que ti. os teus cambios foron gardados nunha rama separada e non se perderon.";
            if (c.prUrl() != null) {
                status(base + " abriuse unha proposta de fusión: " + c.prUrl(), Color.CRIMSON);
                openInBrowser(c.prUrl());
            } else {
                status(base + " (rama: " + c.fallbackBranch() + ")", Color.CRIMSON);
            }
            reloadInPlace(currentIndex);
        } else if (outcome instanceof GitRepoService.PushOutcome.Failure f) {
            status("erro ao subir (os teus cambios seguen gardados): " + f.reason(), Color.CRIMSON);
        }
        updatePendingIndicator();
    }

    /**
     * Volve ler o ficheiro do disco (xa actualizado por un pull/push) e reaxusta o
     * rexistro de edicións pendentes contra o novo contido, sen reconstruír a
     * fiestra: as edicións pendentes (fóra desta liña) non se perden porque viven
     * no rexistro, non na vista. Se algunha clave pendente tamén cambiou no
     * servidor, avísase (o rexistro adopta o valor do servidor coma nova base).
     */
    private void reloadInPlace(int index) {
        try {
            locHelper.reload();
            JsonObject disk = JsonIo.read(store.file());
            List<String> clashes = store.ledger().rebaseAgainst(disk);
            int last = Math.max(0, locHelper.getLineCount() - 1);
            currentIndex = Math.min(Math.max(index, 0), last);
            updateView();
            updatePendingIndicator();
            if (!clashes.isEmpty()) {
                status("algunhas das túas edicións pendentes (" + clashes.size()
                        + ") tamén cambiaron no servidor; revísaas antes de subir", Color.DARKORANGE);
            }
        } catch (IOException e) {
            status("chegaron cambios, pero non se puideron recargar: " + e.getMessage(), Color.CRIMSON);
        }
    }

    /** Actualiza a etiqueta de liñas pendentes de subir a GitHub. */
    private void updatePendingIndicator() {
        int pending = store.pendingCount();
        pendingLabel.setText(pending == 0 ? "" : pending + " pendente(s) de subir");
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
            repeatLabel.setText("");
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
        String clean = locHelper.stripFormatting(currentIndex);

        keyLabel.setText("key: " + key);

        MarkerSummary markers = locHelper.markerSummary(currentIndex);
        StringBuilder info = new StringBuilder();
        info.append("[").append(markers.newlineCount()).append(" liñas]");
        if (markers.hasColor()) info.append(" [cor *]");
        if (markers.hasTilde()) info.append(" [efecto ~]");
        if (markers.hasBackslashO()) info.append(" [\\O @]");
        if (markers.hasBackslashI()) info.append(" [\\I $]");
        if (markers.hasPause()) info.append(" [pausa]");
        infoLabel.setText(info.toString());

        updateRepeatWarning();

        literalArea.setText(MarkerRenderer.literalForDisplay(locHelper.tokens(currentIndex)));
        cleanArea.setText(clean);

        editArea.replaceText(textoBase ? clean : "");

        updatePreview();
        status("listo", Color.SEAGREEN);

        spellLabel.setText("");
        lastMisspelled = Collections.emptyList();
        lastRegions = Collections.emptyList();

        gotoField.setText(String.valueOf(currentIndex + 1));
    }

    /**
     * Aviso de que a liña actual xa apareceu antes: se está traducida nun capítulo
     * anterior, o normal é non tocala aquí (e se se toca, o cambio propágase a
     * todas as aparicións). Sen {@code message-map.json} o aviso non sae nunca.
     */
    private void updateRepeatWarning() {
        MessageMap.Group group = store.groupOf(currentIndex);
        if (group == null) {
            repeatLabel.setText("");
            return;
        }
        List<MessageMap.Member> earlier = store.earlierOccurrences(currentIndex);
        int others = group.members().size() - 1;

        StringBuilder sb = new StringBuilder("⚠ ");
        if (earlier.isEmpty()) {
            sb.append("repetida: aparece tamén en ").append(others)
                    .append(others == 1 ? " sitio máis" : " sitios máis");
        } else {
            sb.append("xa vén de ").append(chapterLabel(earlier.get(0).relPath()));
            if (earlier.size() > 1) {
                sb.append(" (+").append(earlier.size() - 1).append(")");
            }
            sb.append(" — ao gardar propágase ás ").append(others).append(" aparicións");
        }
        if (group.ambiguous()) {
            sb.append(" [duplicada dentro dun capítulo]");
        }
        repeatLabel.setText(sb.toString());
    }

    /** "lang/chapter2/strings.json" -> "capítulo 2"; a raíz é o menú de capítulos. */
    private static String chapterLabel(String relPath) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("chapter(\\d+)").matcher(relPath);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last != null ? "capítulo " + last : "menú de capítulos";
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
