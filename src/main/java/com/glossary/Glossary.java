package com.glossary;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.AppDir;

/**
 * O glosario compartido: o libro de cálculo con nomes de personaxes, obxectos e
 * termos acordados.
 *
 * <p>
 * Non se edita en Amanuensis a propósito. A folla non é unha táboa regular —os
 * capítulos 2 e 3 son bloques anchos de pares inglés/galego repetidos ao longo de
 * moitas columnas—, así que traducila a un esquema JSON perdería precisamente a
 * disposición coa que a xente traballa. O que fai a app é distribuíla e abrila no
 * programa de folla de cálculo que xa teña instalado.
 *
 * <p>
 * Vive dentro de {@code lang/} por un motivo práctico: {@code resetLangTo} só
 * actualiza esa carpeta na árbore de traballo, así que na raíz do repositorio o
 * ficheiro chegaba unha soa vez, ao clonar, e xa non se actualizaba nunca máis.
 * Dentro de {@code lang/} viaxa co resto. Non aparece na lista de ficheiros
 * editables porque {@code MainView} só lista {@code .json}.
 */
public final class Glossary {

    /** Ruta relativa á raíz do repositorio. Tamén a usa GitRepoService. */
    public static final String REL_PATH = "lang/glosario.xlsx";

    private Glossary() {
    }

    /** O ficheiro no disco deste equipo. */
    public static Path file() {
        return AppDir.base().resolve(REL_PATH);
    }

    public static boolean exists() {
        return Files.isRegularFile(file());
    }

    /**
     * Pegada do contido do glosario local, ou {@code ""} se non existe. Compárase
     * con {@link XlsxReader#digest} para saber se alguén cambiou algo de verdade,
     * en vez de fiarse dos bytes (ver a nota en {@link XlsxReader}).
     */
    public static String localDigest() {
        try {
            return exists() ? XlsxReader.digest(Files.readAllBytes(file())) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Abre o glosario no programa de follas de cálculo do sistema.
     *
     * <p>
     * Chámase desde un fío secundario: {@link Desktop#open} bloquea ata que o
     * sistema lanza o programa, e en Linux iso pode tardar bastante.
     *
     * @throws IOException se non existe ou se non hai con que abrilo
     */
    public static void open() throws IOException {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            throw new IOException("aínda non hai glosario descargado en " + REL_PATH);
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f.toFile());
                return;
            }
        } catch (IOException | RuntimeException e) {
            // AWT non sempre está dispoñible nunha app JavaFX (ou falla sen sesión
            // gráfica completa): próbase o lanzador do escritorio directamente.
        }
        openWithFallback(f);
    }

    private static void openWithFallback(Path f) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[] { "rundll32", "url.dll,FileProtocolHandler", f.toString() };
        } else if (os.contains("mac")) {
            cmd = new String[] { "open", f.toString() };
        } else {
            cmd = new String[] { "xdg-open", f.toString() };
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new IOException("non se puido abrir o glosario: " + e.getMessage(), e);
        }
    }
}
