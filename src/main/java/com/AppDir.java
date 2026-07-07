package com;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Carpeta base da aplicación: a carpeta que contén o .jar (e, ao seu carón,
 * o repo git coa subcarpeta lang/).
 *
 * Non se pode usar {@code Path.of(".")} (o directorio de traballo) porque
 * depende de COMO se lanza a app: ao facer dobre clic nun xestor de ficheiros
 * o cwd adoita ser $HOME, non a carpeta do jar, e entón non se atopa .git/lang.
 * Aquí resólvese a partir da localización real do .jar.
 */
public final class AppDir {

    private AppDir() {
    }

    /** Carpeta do .jar cando está empaquetado; o cwd en modo desenvolvemento. */
    public static Path base() {
        try {
            File loc = new File(AppDir.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // empaquetado: loc é o ficheiro .jar -> a base é a súa carpeta.
            // desenvolvemento: loc é target/classes (un directorio) -> usar o cwd.
            if (loc.isFile()) {
                return loc.getAbsoluteFile().getParentFile().toPath();
            }
        } catch (URISyntaxException | RuntimeException e) {
            // sen code source (classloaders raros): caer no cwd
        }
        return cwd();
    }

    /** Subcarpeta lang/ dentro da carpeta base. */
    public static Path lang() {
        return base().resolve("lang");
    }

    private static Path cwd() {
        return Path.of(".").toAbsolutePath().normalize();
    }

    /** True se a base ten un repo git clonado (.git presente). */
    public static boolean hasGit() {
        return Files.isDirectory(base().resolve(".git"));
    }
}
