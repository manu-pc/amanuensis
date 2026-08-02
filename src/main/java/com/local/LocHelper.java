package com.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.local.markers.MarkerReapplier;
import com.local.markers.MarkerStripper;
import com.local.markers.MarkerSummary;
import com.local.markers.MarkerTokenizer;
import com.local.markers.Token;

/**
 * Almacén das liñas traducibles dun ficheiro JSON de localización, indexadas por
 * posición (0-based) na orde do ficheiro.
 *
 * A lóxica de marcadores vive en {@link com.local.markers}: esta clase só le o
 * ficheiro, mantén os valores en memoria e delega
 * ({@link #stripFormatting(int)}, {@link #reapplyFormatting(int, String)},
 * {@link #tokens(int)}), cacheando o fluxo de tokens de cada liña porque o
 * editor reaplica formato en cada pulsación de tecla.
 */
public class LocHelper {
    private final String filename;
    private final List<String> keys = new ArrayList<>(); // claves na orde do ficheiro
    private final List<String> originals = new ArrayList<>(); // valores orixinais na orde do ficheiro
    private final List<List<Token>> tokenCache = new ArrayList<>(); // null = aínda sen tokenizar
    private final Map<String, Integer> keyIndex = new HashMap<>(); // clave -> posición

    public LocHelper(String filename) throws IOException {
        this.filename = filename;
        readAndParseFile();
    }

    private void readAndParseFile() throws IOException {
        String jsonText = Files.readString(Path.of(filename));
        JsonObject obj = JsonParser.parseString(jsonText).getAsJsonObject();

        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            JsonElement val = e.getValue();
            // Só liñas de texto: ignorar valores que non sexan string (arrays/obxectos
            // de ficheiros de metadatos como chapter_settings.json).
            if (!val.isJsonPrimitive() || !val.getAsJsonPrimitive().isString()) {
                continue;
            }
            keyIndex.put(e.getKey(), keys.size());
            keys.add(e.getKey());
            originals.add(val.getAsString());
            tokenCache.add(null);
        }
    }

    /**
     * Volve ler o ficheiro do disco, descartando claves, valores e tokens en
     * memoria. Úsase cando chegan cambios do servidor mentres o editor está aberto:
     * antes había que construír un LocalView novo, o que deixaba fíos orfos.
     */
    public void reload() throws IOException {
        keys.clear();
        originals.clear();
        tokenCache.clear();
        keyIndex.clear();
        readAndParseFile();
    }

    public int getLineCount() {
        return originals.size();
    }

    public String getKey(int lineIndex) {
        return keys.get(lineIndex);
    }

    public String getOriginal(int lineIndex) {
        return originals.get(lineIndex);
    }

    /** Posición desa clave no ficheiro, ou -1 se non está. */
    public int indexOfKey(String key) {
        Integer i = keyIndex.get(key);
        return i == null ? -1 : i;
    }

    /**
     * Actualiza o valor orixinal en memoria para a liña indicada.
     * Útil para manter a lista en sincronía despois de gardar cambios.
     */
    public void updateOriginal(int lineIndex, String newValue) {
        originals.set(lineIndex, newValue);
        tokenCache.set(lineIndex, null);
    }

    /** Fluxo de tokens da liña (cacheado; invalídase en {@link #updateOriginal}). */
    public List<Token> tokens(int lineIndex) {
        List<Token> cached = tokenCache.get(lineIndex);
        if (cached == null) {
            cached = MarkerTokenizer.tokenize(originals.get(lineIndex));
            tokenCache.set(lineIndex, cached);
        }
        return cached;
    }

    /** Resumo dos marcadores da liña (cores, efectos, pausas, saltos de liña). */
    public MarkerSummary markerSummary(int lineIndex) {
        return MarkerSummary.of(tokens(lineIndex));
    }

    /**
     * Conta o número de saltos de liña (newline markers) no texto orixinal.
     * Útil para que o usuario saiba cantas liñas ten o texto orixinal.
     */
    public int countNewlines(int lineIndex) {
        return markerSummary(lineIndex).newlineCount();
    }

    /**
     * Devolve o texto plano sen marcadores da liña index (0-based).
     * Ver {@link MarkerStripper}.
     */
    public String stripFormatting(int lineIndex) {
        return MarkerStripper.strip(tokens(lineIndex));
    }

    /**
     * A partir dunha liña sen indicadores de formato e a posición da liña orixinal,
     * reaplica os indicadores de formato que require esa liña.
     * Ver {@link MarkerReapplier}.
     */
    public String reapplyFormatting(int lineIndex, String newPlain) {
        return MarkerReapplier.reapply(tokens(lineIndex), newPlain);
    }

    @Override
    public String toString() {
        return "LocHelper[" + filename + ", " + originals.size() + " liñas]";
    }
}
