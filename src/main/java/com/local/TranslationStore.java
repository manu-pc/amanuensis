package com.local;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonObject;
import com.local.map.MessageMap;
import com.local.map.MessagePropagator;

/**
 * Escritura das traducións dun ficheiro aberto no editor.
 *
 * Substitúe a {@code FileCopyManager}: xa non hai copia de traballo
 * ({@code .copy.json}). Cada garda escríbese <b>directamente no ficheiro real</b>
 * — o clon de git é a copia de seguridade, e o ficheiro real é, ademais, o que se
 * acaba subindo — e anótase no {@link EditLedger}, que é quen sabe que claves son
 * realmente do usuario e con que valor de partida.
 *
 * Orde deliberada en {@link #save}: primeiro o rexistro, despois o ficheiro. Se a
 * app morre entre as dúas escrituras queda unha entrada pendente cuxo valor non
 * está no disco, e {@link #applyPendingFromLedger()} restáuraa ao abrir. Ao revés
 * quedaría un cambio no ficheiro sen base coñecida: xusto o estado que provocaba
 * que se sobrescribisen traducións doutras persoas.
 *
 * Ademais, se o repositorio ten {@code message-map.json}, cada garda propágase ás
 * demais aparicións da mesma mensaxe noutros capítulos
 * ({@link com.local.map.MessagePropagator}); o resultado queda en
 * {@link #lastPropagation()} para que a interface o poida contar.
 */
public final class TranslationStore {

    private final LocHelper locHelper;
    private final Path jsonFile;
    private final EditLedger ledger;

    // propagación entre capítulos (null se o ficheiro non está nun repo con mapa)
    private final Path repoRoot;
    private final MessageMap messageMap;
    private final MessagePropagator.Ledgers ledgers;
    private MessagePropagator.Result lastPropagation = MessagePropagator.Result.NONE;

    public TranslationStore(LocHelper locHelper, Path jsonFile, EditLedger ledger) {
        this(locHelper, jsonFile, ledger, null, MessageMap.empty());
    }

    /**
     * @param repoRoot   raíz do repositorio, ou null se o ficheiro está fóra
     * @param messageMap mapa de mensaxes repetidas ({@link MessageMap#empty()} para
     *                   traballar sen propagación)
     */
    public TranslationStore(LocHelper locHelper, Path jsonFile, EditLedger ledger,
            Path repoRoot, MessageMap messageMap) {
        this.locHelper = locHelper;
        this.jsonFile = jsonFile;
        this.ledger = ledger;
        this.repoRoot = repoRoot;
        this.messageMap = messageMap == null ? MessageMap.empty() : messageMap;
        // o rexistro deste ficheiro xa está aberto: hai que darllo ao propagador,
        // porque un segundo EditLedger sobre o mesmo ficheiro escribiría por riba
        Path self = jsonFile.toAbsolutePath().normalize();
        MessagePropagator.Ledgers base = repoRoot == null
                ? f -> EditLedger.openFor(f, null)
                : MessagePropagator.defaultLedgers(repoRoot);
        this.ledgers = file -> file.toAbsolutePath().normalize().equals(self)
                ? ledger
                : base.ledgerFor(file);
    }

    public EditLedger ledger() {
        return ledger;
    }

    public Path file() {
        return jsonFile;
    }

    /**
     * Garda o valor da liña indicada.
     *
     * @return true se o ficheiro cambiou; false se esa liña xa tiña ese texto
     *         (nese caso non se anota nada: «tocar» non é «cambiar»)
     * @throws KeyMissingException se a clave xa non existe no ficheiro (chegou un
     *         cambio de estrutura dende o servidor): hai que recargar, nunca
     *         escribir pola posición
     */
    public boolean save(int lineIndex, String formattedValue) throws IOException {
        // lectura fresca: se entrou un pull por debaixo, respéctase o que hai agora
        JsonObject obj = JsonIo.read(jsonFile);
        String key = locHelper.getKey(lineIndex);

        if (!obj.has(key)) {
            throw new KeyMissingException(key);
        }

        String diskValue = JsonIo.stringOrNull(obj, key);
        if (!ledger.record(key, diskValue, formattedValue)) {
            return false;
        }

        obj.addProperty(key, formattedValue);
        JsonIo.writeAtomic(jsonFile, obj);
        locHelper.updateOriginal(lineIndex, formattedValue);

        // e agora as demais aparicións da mesma mensaxe noutros capítulos
        lastPropagation = MessagePropagator.propagate(repoRoot, messageMap, relPath(), key,
                formattedValue, ledgers);
        for (MessagePropagator.Target t : lastPropagation.written()) {
            // se a aparición estaba neste mesmo ficheiro (mensaxe duplicada dentro
            // dun capítulo), o valor en memoria tamén quedou vello
            if (t.relPath().equals(relPath())) {
                int i = locHelper.indexOfKey(t.key());
                if (i >= 0) {
                    locHelper.updateOriginal(i, formattedValue);
                }
            }
        }
        return true;
    }

    /** Resultado da propagación da última {@link #save} (nunca null). */
    public MessagePropagator.Result lastPropagation() {
        return lastPropagation;
    }

    /** O grupo de mensaxes repetidas ao que pertence a liña, ou null. */
    public MessageMap.Group groupOf(int lineIndex) {
        return messageMap.find(relPath(), locHelper.getKey(lineIndex));
    }

    /** Aparicións da mesma mensaxe en ficheiros anteriores (capítulos previos). */
    public List<MessageMap.Member> earlierOccurrences(int lineIndex) {
        MessageMap.Group group = groupOf(lineIndex);
        return group == null ? List.of() : group.before(relPath());
    }

    /** Ruta do ficheiro relativa á raíz do repo, ou null se está fóra. */
    public String relPath() {
        if (repoRoot == null) {
            return null;
        }
        Path abs = jsonFile.toAbsolutePath().normalize();
        Path root = repoRoot.toAbsolutePath().normalize();
        return abs.startsWith(root) ? MessageMap.normalize(root.relativize(abs).toString()) : null;
    }

    /**
     * Reaplica ao ficheiro as edicións pendentes que non están no disco (caída da
     * app entre as dúas escrituras de {@link #save}). Só toca as claves cuxo valor
     * en disco segue sendo a base rexistrada: se cambiou no servidor, déixase para
     * {@link EditLedger#rebaseAgainst}.
     *
     * @return número de claves restauradas
     */
    public int applyPendingFromLedger() throws IOException {
        if (ledger.isEmpty()) {
            return 0;
        }
        JsonObject obj = JsonIo.read(jsonFile);
        List<String> restored = new ArrayList<>();

        for (Map.Entry<String, EditLedger.Entry> e : ledger.entries().entrySet()) {
            String key = e.getKey();
            EditLedger.Entry entry = e.getValue();
            if (!obj.has(key)) {
                continue;
            }
            String diskValue = JsonIo.stringOrNull(obj, key);
            if (Objects.equals(diskValue, entry.value())) {
                continue; // xa está aplicada
            }
            if (Objects.equals(diskValue, entry.base())) {
                obj.addProperty(key, entry.value());
                restored.add(key);
            }
            // se non coincide con ningunha das dúas, cambiou no servidor: rebaseAgainst
        }

        if (restored.isEmpty()) {
            return 0;
        }
        JsonIo.writeAtomic(jsonFile, obj);
        locHelper.reload();
        return restored.size();
    }

    /** Números de liña (1-based) das claves pendentes, para a mensaxe do commit. */
    public List<Integer> pendingLineNumbers() {
        List<Integer> lines = new ArrayList<>();
        for (int i = 0; i < locHelper.getLineCount(); i++) {
            if (ledger.isPending(locHelper.getKey(i))) {
                lines.add(i + 1);
            }
        }
        return lines;
    }

    public int pendingCount() {
        return ledger.size();
    }

    public boolean isPending(int lineIndex) {
        return ledger.isPending(locHelper.getKey(lineIndex));
    }

    /** A clave dunha liña desapareceu do ficheiro: cómpre recargar antes de gardar. */
    public static class KeyMissingException extends IOException {
        private final String key;

        public KeyMissingException(String key) {
            super("a clave '" + key + "' xa non existe no ficheiro; recarga antes de gardar");
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
