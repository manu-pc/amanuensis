package com.local.markers;

/**
 * Que facer cos parénteses ao tokenizar.
 *
 * <p>
 * No corpus real (eng-4.json: 1810 liñas con parénteses) só ~170 son un
 * envoltorio de liña completa; 1641 levan parénteses no medio do texto, 86 teñen
 * varias aperturas e algunhas están desemparelladas. Tratalos como marcadores
 * producía texto limpo con parénteses sen parella
 * ({@code *\EH (AM I THAT BAD???)} → {@code (AM I THAT BAD???}) e colisións co
 * marcador de fin ({@code :(/%}).
 */
public enum ParenPolicy {
    /**
     * Comportamento por defecto: os parénteses son caracteres normais, visibles
     * no texto limpo. O tradutor vevos e escríbeos coma calquera outro signo.
     */
    VISIBLE_CHARS,

    /**
     * Oculta o par de parénteses só cando envolve toda a liña: apertura xusto
     * despois dos marcadores iniciais e peche no último carácter, sen
     * desequilibrio no medio. Calquera outro paréntese queda visible.
     */
    HIDDEN_BALANCED_WRAPPER
}
