package com.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Onde viven os rexistros de edicións pendentes ({@link EditLedger}) e como se
 * atopan ao arrancar.
 *
 * Van a {@code ~/.amanuensis/ledgers/}, fóra do repositorio, por dous motivos:
 * a carpeta {@code lang/} queda limpa (o vello sistema de copias deixaba
 * ficheiros de 1,4 MB tirados ao lado dos datos) e o rexistro sobrevive a
 * calquera operación de git sobre a árbore de traballo.
 *
 * Mesmo patrón que {@link com.git.TokenStore}: directorio creado ao momento e
 * permisos só para o propietario onde o sistema o admita.
 */
public final class LedgerStore {

    /**
     * Propiedade de sistema para redirixir o directorio (úsana as probas; en
     * produción non se define e vale {@code ~/.amanuensis/ledgers}).
     */
    public static final String DIR_PROPERTY = "amanuensis.ledgerDir";

    private LedgerStore() {
    }

    public static Path dir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".amanuensis", "ledgers");
    }

    static void ensureDir() throws IOException {
        Path dir = dir();
        Files.createDirectories(dir);
        trySetOwnerOnly(dir);
    }

    /**
     * Nome do rexistro dun ficheiro: un fragmento lexible da súa ruta máis un
     * resumo da ruta absoluta, para que dous ficheiros co mesmo nome en distintos
     * capítulos (ou dúas copias do proxecto) nunca compartan rexistro.
     */
    static Path fileFor(Path jsonFile) {
        Path abs = jsonFile.toAbsolutePath().normalize();
        return dir().resolve(slug(abs) + "-" + shortHash(abs.toString()) + ".ledger.json");
    }

    private static String slug(Path abs) {
        int count = abs.getNameCount();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, count - 3); i < count; i++) {
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(abs.getName(i).toString().replaceAll("[^A-Za-z0-9._-]", "_"));
        }
        String s = sb.toString();
        if (s.endsWith(".json")) {
            s = s.substring(0, s.length() - ".json".length());
        }
        return s.length() > 60 ? s.substring(s.length() - 60) : s;
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** Todos os rexistros gardados en disco. Os ilexibles ignóranse. */
    public static List<EditLedger> loadAll() {
        List<EditLedger> out = new ArrayList<>();
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(p -> p.getFileName().toString().endsWith(".ledger.json")).toList()) {
                try {
                    EditLedger ledger = EditLedger.fromLedgerFile(p);
                    if (!ledger.isEmpty()) {
                        out.add(ledger);
                    }
                } catch (Exception ignored) {
                    // rexistro corrupto ou dun formato futuro: non debe impedir arrancar
                }
            }
        } catch (IOException ignored) {
            // sen acceso ao directorio: trabállase sen recuperación
        }
        return out;
    }

    /** Rexistros non baleiros deste repositorio, indexados pola ruta relativa. */
    public static Map<String, EditLedger> ledgersFor(Path repoRoot) {
        Path root = repoRoot.toAbsolutePath().normalize();
        Map<String, EditLedger> out = new LinkedHashMap<>();
        for (EditLedger ledger : loadAll()) {
            if (ledger.relPath() != null && ledger.targetFile().startsWith(root)) {
                out.put(ledger.relPath(), ledger);
            }
        }
        return out;
    }

    /** Edicións pendentes deste repositorio: ruta relativa → (clave → entrada). */
    public static Map<String, Map<String, EditLedger.Entry>> allEdits(Path repoRoot) {
        Map<String, Map<String, EditLedger.Entry>> out = new LinkedHashMap<>();
        ledgersFor(repoRoot).forEach((rel, ledger) -> out.put(rel, ledger.entries()));
        return out;
    }

    /** True se hai algo pendente de subir neste repositorio. */
    public static boolean hasPendingEdits(Path repoRoot) {
        return !ledgersFor(repoRoot).isEmpty();
    }

    /** Número total de liñas pendentes neste repositorio (para avisos ao usuario). */
    public static int pendingCount(Path repoRoot) {
        return ledgersFor(repoRoot).values().stream().mapToInt(EditLedger::size).sum();
    }

    private static void trySetOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ou sistema sen POSIX: quédase coa protección por defecto do SO
        }
    }
}
