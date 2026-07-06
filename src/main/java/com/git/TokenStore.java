package com.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Persiste o token de acceso de GitHub en disco.
 * Gárdase en texto plano, protexido só polos permisos do ficheiro (rw só para
 * o propietario en sistemas POSIX) — mesmo nivel de confianza que a maioría
 * dos axudantes de credenciais de git locais, non é un almacén cifrado real.
 */
public class TokenStore {

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".amanuensis");
    private static final Path FILE = DIR.resolve("github_token");

    public static void save(String token) throws IOException {
        Files.createDirectories(DIR);
        Files.writeString(FILE, token, StandardCharsets.UTF_8);
        trySetOwnerOnly(FILE);
    }

    public static String load() {
        try {
            return Files.exists(FILE) ? Files.readString(FILE, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException ignored) {
            // se non se pode borrar, quedará un token vello mais inofensivo no disco
        }
    }

    private static void trySetOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ou sistema sen permisos POSIX: quédase coa protección por defecto do SO.
        }
    }
}
