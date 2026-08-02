package com.update;

/**
 * Versión da aplicación en execución e comparación de versións.
 *
 * A versión sae do manifesto do jar ({@code Implementation-Version}, que enche o
 * shade plugin coa versión do pom). En desenvolvemento non hai manifesto, así que
 * vale {@link #DEV}: unha versión de desenvolvemento é sempre <b>máis nova</b> ca
 * calquera publicada, para que a máquina que compila non se ofreza a «actualizar»
 * á versión que acaba de subir.
 */
public final class AppVersion {

    /** Versión que se usa cando non hai manifesto (mvn javafx:run, tests). */
    public static final String DEV = "dev";

    private AppVersion() {
    }

    /** Versión do jar en execución, ou {@link #DEV}. */
    public static String current() {
        String v = AppVersion.class.getPackage() == null
                ? null
                : AppVersion.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? DEV : v.trim();
    }

    public static boolean isDev(String version) {
        return version == null || version.isBlank() || DEV.equals(version);
    }

    /** True se {@code remote} é posterior á versión en execución. */
    public static boolean isNewerThanCurrent(String remote) {
        return isNewer(remote, current());
    }

    /**
     * True se {@code remote} é posterior a {@code local}. Unha versión de
     * desenvolvemento nunca se actualiza; unha versión remota de desenvolvemento
     * nunca se ofrece.
     */
    public static boolean isNewer(String remote, String local) {
        if (isDev(remote) || isDev(local)) {
            return false;
        }
        return compare(remote, local) > 0;
    }

    /**
     * Compara dúas versións con puntos ({@code 1.2.0}, {@code 1.2}, {@code 1.2.1-rc1}).
     *
     * Compáranse os números por posición; as posicións que falten valen 0, así que
     * {@code 1.2} e {@code 1.2.0} son iguais. Se os números empatan, un sufixo
     * ({@code -SNAPSHOT}, {@code -rc1}) vai <b>antes</b> ca a versión limpa, que é
     * o convenio habitual: 1.2.0-rc1 &lt; 1.2.0.
     */
    public static int compare(String a, String b) {
        int[] na = numbers(a);
        int[] nb = numbers(b);
        int len = Math.max(na.length, nb.length);
        for (int i = 0; i < len; i++) {
            int va = i < na.length ? na[i] : 0;
            int vb = i < nb.length ? nb[i] : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        String qa = qualifier(a);
        String qb = qualifier(b);
        if (qa.isEmpty() && qb.isEmpty()) {
            return 0;
        }
        if (qa.isEmpty()) {
            return 1; // sen sufixo é a versión final: posterior
        }
        if (qb.isEmpty()) {
            return -1;
        }
        return qa.compareTo(qb);
    }

    private static int[] numbers(String version) {
        String head = version == null ? "" : version.trim();
        int dash = indexOfQualifier(head);
        if (dash >= 0) {
            head = head.substring(0, dash);
        }
        if (head.isEmpty()) {
            return new int[0];
        }
        String[] parts = head.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0; // anaco non numérico: trátase coma 0
            }
        }
        return out;
    }

    private static String qualifier(String version) {
        String v = version == null ? "" : version.trim();
        int dash = indexOfQualifier(v);
        return dash < 0 ? "" : v.substring(dash + 1);
    }

    private static int indexOfQualifier(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '-' || c == '+' || c == '_') {
                return i;
            }
        }
        return -1;
    }
}
