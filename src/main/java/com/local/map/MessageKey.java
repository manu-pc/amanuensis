package com.local.map;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unha clave de {@code strings.json} descomposta nas súas partes.
 *
 * O formato real dos ficheiros de Deltarune é
 * {@code <entidade>_slash_<evento>_gml_<liña>_<índice>[_b]}, por exemplo
 * {@code obj_npc_room_slash_Other_10_gml_240_0}:
 * <ul>
 * <li><b>entidade</b> — obxecto ou script do GML ({@code obj_npc_room},
 * {@code scr_text}, {@code DEVICE_MENU});</li>
 * <li><b>evento</b> — evento do obxecto ({@code Other_10}, {@code Step_0},
 * {@code Draw_0}) ou, nos scripts, o nome do script outra vez;</li>
 * <li><b>liña</b> — número de liña no GML <b>descompilado</b>;</li>
 * <li><b>índice</b> — cal das cadeas dessa liña;</li>
 * <li><b>_b</b> — sufixo das cadeas engadidas polo mod de tradución (431 en ch5),
 * que non teñen xemelga sen sufixo.</li>
 * </ul>
 *
 * O par (entidade, evento) é o <i>bloque</i>: un anaco de código que se pode
 * comparar entre capítulos. O número de liña é o que se move entre compilacións,
 * e por iso non se usa para identificar nada, só para ordenar dentro do bloque.
 *
 * Unhas 30 claves por ficheiro non seguen o formato ({@code obj_lang_settings_3_0},
 * {@code date}, {@code // From prev chapters}): {@link #parse} devolve null e
 * trátanse só por igualdade exacta de clave.
 */
public record MessageKey(String entity, String event, int line, int index, boolean bSuffix)
        implements Comparable<MessageKey> {

    private static final Pattern PATTERN =
            Pattern.compile("^(.+)_slash_(.+)_gml_(\\d+)_(\\d+)(_b)?$");

    /** Descompón a clave, ou null se non segue o formato. */
    public static MessageKey parse(String key) {
        if (key == null) {
            return null;
        }
        Matcher m = PATTERN.matcher(key);
        if (!m.matches()) {
            return null;
        }
        try {
            return new MessageKey(m.group(1), m.group(2),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                    m.group(5) != null);
        } catch (NumberFormatException e) {
            return null; // números absurdamente longos: trátase como clave opaca
        }
    }

    /** Identificador do bloque de código ao que pertence. */
    public String block() {
        return entity + " " + event;
    }

    /** Orde dentro do bloque: a orde do código fonte. */
    @Override
    public int compareTo(MessageKey o) {
        if (line != o.line) {
            return Integer.compare(line, o.line);
        }
        if (index != o.index) {
            return Integer.compare(index, o.index);
        }
        return Boolean.compare(bSuffix, o.bSuffix);
    }
}
