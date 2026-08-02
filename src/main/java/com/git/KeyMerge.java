package com.git;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonObject;
import com.local.JsonIo;

/**
 * Reconciliación a nivel de clave JSON entre as edicións rexistradas do usuario e
 * o que hai no servidor. Funcións puras, sen git nin ficheiros: toda a decisión
 * de «que se sobe e que se respecta» vive aquí e pódese probar soa.
 *
 * <p>
 * Diferenza esencial co sistema anterior: antes «o meu cambio» era <em>o que
 * houbese na árbore de traballo</em>, comparado cun antepasado común de git. Unha
 * clave simplemente desactualizada no disco era indistinguible dunha editada a
 * propósito, así que gañaba sobre o servidor e desfacía a tradución doutra persoa
 * sen avisar (575 liñas volveron ao inglés así só no capítulo 4). Agora «o meu
 * cambio» é exactamente o que hai no rexistro de edicións, coa súa propia base
 * por clave: o resto do ficheiro nin se le nin se toca.
 */
public final class KeyMerge {

    /**
     * Unha edición do usuario.
     *
     * @param base  valor que tiña a clave cando a tocou por primeira vez
     * @param value valor que escribiu
     */
    public record KeyEdit(String base, String value) {
    }

    private KeyMerge() {
    }

    /**
     * Contido a confirmar: o de HEAD coas nosas edicións por riba. Constrúese
     * sempre así, sen ler a árbore de traballo, para que unha clave que non estea
     * no rexistro <b>non poida moverse</b> nin sequera se o ficheiro do disco está
     * desactualizado.
     */
    public static JsonObject buildCommitContent(JsonObject head, Map<String, KeyEdit> edits) {
        JsonObject out = head.deepCopy();
        for (Map.Entry<String, KeyEdit> e : edits.entrySet()) {
            if (out.has(e.getKey())) {
                out.addProperty(e.getKey(), e.getValue().value());
            }
            // clave que xa non existe en HEAD: ignórase (o editor recargará)
        }
        return out;
    }

    /**
     * Mestura as nosas edicións co estado do servidor, recorrendo <b>só</b> as
     * claves editadas:
     *
     * <ul>
     * <li>o servidor segue na nosa base → aplícase o noso valor</li>
     * <li>o servidor xa ten o noso valor → nada que facer</li>
     * <li>calquera outra cousa → conflito desa clave (ninguén gaña en silencio)</li>
     * </ul>
     *
     * @param conflictsOut énchese con clave → o noso valor, para a rama de conflito
     * @return o contido a subir (parte do do servidor)
     */
    public static JsonObject reconcile(JsonObject theirs, Map<String, KeyEdit> edits,
            Map<String, String> conflictsOut) {
        JsonObject merged = theirs.deepCopy();
        for (Map.Entry<String, KeyEdit> e : edits.entrySet()) {
            String key = e.getKey();
            KeyEdit edit = e.getValue();
            String theirsVal = JsonIo.stringOrNull(theirs, key);

            if (Objects.equals(theirsVal, edit.value())) {
                continue; // mesmo texto nos dous lados
            }
            if (Objects.equals(theirsVal, edit.base())) {
                merged.addProperty(key, edit.value());
            } else {
                conflictsOut.put(key, edit.value());
            }
        }
        return merged;
    }

    /** Converte o mapa de entradas do rexistro no formato que usa esta clase. */
    public static Map<String, KeyEdit> fromLedger(Map<String, com.local.EditLedger.Entry> entries) {
        Map<String, KeyEdit> out = new LinkedHashMap<>();
        entries.forEach((key, entry) -> out.put(key, new KeyEdit(entry.base(), entry.value())));
        return out;
    }
}
