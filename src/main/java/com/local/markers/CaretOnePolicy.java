package com.local.markers;

/**
 * Que facer coas pausas {@code ^1} ao reaplicar formato.
 *
 * <p>
 * O {@code ^1} é a pausa curta que o xogo fai na puntuación (no corpus real
 * aparece 8919 veces, o 96% delas xusto antes de {@code , . ! ?}). O tradutor
 * non as escribe: repuntúa a frase e o editor recolócaas.
 */
public enum CaretOnePolicy {
    /**
     * Conservar os {@code ^1} do orixinal nas súas posicións (límite de palabra
     * máis próximo). Útil cando a tradución respecta a puntuación orixinal.
     */
    PRESERVE,

    /**
     * Descartar os {@code ^1} do orixinal e deducilos da puntuación do texto
     * traducido.
     */
    REDERIVE,

    /**
     * Por defecto: conservar se o texto non cambiou (así unha edición nula non
     * altera o ficheiro) e deducir en canto o tradutor toca a liña.
     */
    REDERIVE_IF_CHANGED
}
