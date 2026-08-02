package com.local;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lectura e escritura dos JSON de localización. Único escritor de JSON da app:
 * todos os compoñentes deben pasar por aquí para que o formato sexa idéntico
 * (calquera diferenza de formato convertería un cambio dunha liña nun diff de
 * 1,4 MB no seguinte push).
 *
 * A escritura é atómica: escríbese nun ficheiro temporal ao lado, fórzase a
 * disco e móvese enriba do destino. Así unha caída no medio non deixa o ficheiro
 * de tradución truncado.
 */
public final class JsonIo {

    /** Mesma configuración que antes usaban por separado o vello sistema de copias e GitRepoService. */
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonIo() {
    }

    public static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** Valor de texto da clave, ou null se non existe ou non é texto. */
    public static String stringOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        var el = obj.get(key);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    /**
     * Escribe o JSON de forma atómica: ficheiro temporal irmán + move. O temporal
     * lévase o pid no nome para que dúas instancias non se pisen, e bórrase sempre.
     */
    public static void writeAtomic(Path file, JsonObject obj) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = dir.resolve(file.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        try {
            byte[] bytes = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true); // os datos están en disco antes de mover
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // sistemas de ficheiros sen move atómico: cámbiase igual, sen a garantía
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
