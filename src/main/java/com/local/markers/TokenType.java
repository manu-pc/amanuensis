package com.local.markers;

/**
 * Tipos de token do sistema de marcadores de Undertale/Deltarune.
 *
 * <ul>
 * <li>{@link #VISIBLE} — carácter visible (aparece no texto limpo)</li>
 * <li>{@link #FORMAT} — marcador de formato de posición fixa (\E, \M, \cX, ...)</li>
 * <li>{@link #PENDING} — marcador que se insire en límites de palabra (^n, ~n)</li>
 * <li>{@link #NEWLINE} — marcador que produce salto de liña (&amp;, #, \n)</li>
 * <li>{@link #END} — marcador de fin de texto (/, /%, %, %%)</li>
 * <li>{@link #TRAILING_WS} — espazos finais do orixinal, conservados tal cal</li>
 * </ul>
 */
public enum TokenType {
    VISIBLE,
    FORMAT,
    PENDING,
    NEWLINE,
    END,
    TRAILING_WS
}
