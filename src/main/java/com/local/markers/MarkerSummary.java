package com.local.markers;

import java.util.ArrayList;
import java.util.List;

/**
 * Resumo dos marcadores dunha liña, para as etiquetas informativas do editor
 * (badges «[cor *] [efecto ~] [\O @] [\I $] [pausa]» e o número de liñas).
 *
 * @param newlineCount número de marcadores de salto de liña
 * @param colors       marcadores de cor na orde en que aparecen
 * @param tildes       marcadores de efecto ~n na orde en que aparecen
 * @param backslashO   marcadores \On na orde en que aparecen
 * @param backslashI   marcadores \In na orde en que aparecen
 * @param pauseCount   número de marcadores de pausa ^n
 */
public record MarkerSummary(int newlineCount, List<String> colors, List<String> tildes,
        List<String> backslashO, List<String> backslashI, int pauseCount) {

    public static MarkerSummary of(List<Token> tokens) {
        int newlines = 0;
        int pauses = 0;
        List<String> colors = new ArrayList<>();
        List<String> tildes = new ArrayList<>();
        List<String> os = new ArrayList<>();
        List<String> is = new ArrayList<>();

        for (Token t : tokens) {
            if (t.isNewline()) {
                newlines++;
            } else if (Markers.isColor(t)) {
                colors.add(t.text());
            } else if (Markers.isTilde(t)) {
                tildes.add(t.text());
            } else if (Markers.isBackslashO(t)) {
                os.add(t.text());
            } else if (Markers.isBackslashI(t)) {
                is.add(t.text());
            } else if (t.isPending()) {
                pauses++;
            }
        }

        return new MarkerSummary(newlines, List.copyOf(colors), List.copyOf(tildes),
                List.copyOf(os), List.copyOf(is), pauses);
    }

    public boolean hasColor() {
        return !colors.isEmpty();
    }

    public boolean hasTilde() {
        return !tildes.isEmpty();
    }

    public boolean hasBackslashO() {
        return !backslashO.isEmpty();
    }

    public boolean hasBackslashI() {
        return !backslashI.isEmpty();
    }

    public boolean hasPause() {
        return pauseCount > 0;
    }
}
