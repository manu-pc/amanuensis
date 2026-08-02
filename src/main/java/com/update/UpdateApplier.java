package com.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * O proceso que substitúe o jar e reinicia a app.
 *
 * Execútase <b>dende o jar descargado</b> ({@code java -cp <novo> UpdateApplier}),
 * nunca dende o que se vai substituír, e o primeiro que fai é agardar a que a
 * aplicación remate: mentres a JVM vella siga viva o ficheiro está bloqueado en
 * Windows e en uso en Linux.
 *
 * Todo o que fai queda rexistrado en {@code .amanuensis-update/applier.log},
 * porque para cando isto corre xa non hai interface onde amosar un erro.
 *
 * Antes de escribir garda o jar anterior como {@code <nome>.bak}: se a copia
 * falla a medias, aí queda a versión que funcionaba.
 */
public final class UpdateApplier {

    /** Cantas veces se reintenta a copia mentres o sistema aínda ten o jar collido. */
    private static final int COPY_ATTEMPTS = 60;
    private static final long RETRY_MS = 500;
    /** Se a app non morre en 2 minutos, algo vai mal: mellor non tocar nada. */
    private static final long EXIT_WAIT_MS = 120_000;

    private UpdateApplier() {
    }

    public static void main(String[] args) {
        long pid = -1;
        Path from = null;
        Path to = null;
        String java = null;

        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--pid" -> pid = Long.parseLong(args[i + 1]);
                case "--from" -> from = Path.of(args[i + 1]);
                case "--to" -> to = Path.of(args[i + 1]);
                case "--java" -> java = args[i + 1];
                default -> log("argumento descoñecido: " + args[i]);
            }
        }

        if (from == null || to == null) {
            log("faltan --from ou --to; non se fai nada");
            return;
        }

        log("instalando " + from + " -> " + to);

        if (!waitForExit(pid)) {
            log("a aplicación segue viva pasado o tempo de espera; cancélase");
            return;
        }

        try {
            install(from, to);
        } catch (IOException e) {
            log("ERRO ao instalar: " + e);
            return;
        }

        relaunch(java, to);
    }

    /** Agarda a que remate o proceso indicado. True se xa non existe. */
    static boolean waitForExit(long pid) {
        if (pid <= 0) {
            return true;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return true;
        }
        long deadline = System.currentTimeMillis() + EXIT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!handle.get().isAlive()) {
                return true;
            }
            sleep(200);
        }
        return !handle.get().isAlive();
    }

    /**
     * Copia o jar novo enriba do vello, gardando antes unha copia de seguridade.
     * Reintenta porque Windows tarda un intre en soltar o ficheiro despois de que
     * o proceso remate.
     */
    static void install(Path from, Path to) throws IOException {
        Path backup = to.resolveSibling(to.getFileName() + ".bak");
        if (Files.exists(to)) {
            try {
                Files.copy(to, backup, StandardCopyOption.REPLACE_EXISTING);
                log("copia de seguridade en " + backup);
            } catch (IOException e) {
                log("aviso: non se puido gardar a copia de seguridade (" + e + ")");
            }
        }

        IOException last = null;
        for (int attempt = 1; attempt <= COPY_ATTEMPTS; attempt++) {
            try {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                log("instalado (intento " + attempt + ")");
                try {
                    Files.deleteIfExists(from);
                } catch (IOException ignored) {
                    // queda a descarga sen borrar: inofensivo
                }
                return;
            } catch (IOException e) {
                last = e;
                sleep(RETRY_MS);
            }
        }
        // non se puido escribir: restaurar o que había e deixalo como estaba
        if (Files.exists(backup)) {
            try {
                Files.copy(backup, to, StandardCopyOption.REPLACE_EXISTING);
                log("restaurada a versión anterior");
            } catch (IOException e) {
                log("ERRO: tampouco se puido restaurar (" + e + "); queda " + backup);
            }
        }
        throw new IOException("non se puido substituír o jar tras " + COPY_ATTEMPTS
                + " intentos: " + last);
    }

    private static void relaunch(String java, Path jar) {
        String bin = java == null || java.isBlank() ? UpdateService.javaBinary() : java;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin, "-jar", jar.toAbsolutePath().toString());
            pb.directory(jar.toAbsolutePath().getParent().toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(jar).toFile()));
            pb.start();
            log("reiniciada");
        } catch (IOException e) {
            log("ERRO ao reiniciar: " + e + " — ábrea a man");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path logFile(Path near) {
        Path dir = near.toAbsolutePath().getParent();
        return dir == null ? Path.of("applier.log") : dir.resolve(UpdateService.WORK_DIR)
                .resolve("applier.log");
    }

    private static void log(String message) {
        // a saída estándar deste proceso xa está redirixida ao ficheiro de rexistro
        System.out.println("[" + LocalDateTime.now() + "] " + message);
        System.out.flush();
    }
}
