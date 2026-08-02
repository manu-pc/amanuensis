package com.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

class JsonIoTest {

    @Test
    void writeAtomicReplacesContentAndLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("strings.json");
        Files.writeString(f, "{\"a\":\"vello\"}");

        JsonObject obj = new JsonObject();
        obj.addProperty("a", "novo");
        JsonIo.writeAtomic(f, obj);

        assertEquals("novo", JsonIo.stringOrNull(JsonIo.read(f), "a"));
        try (var stream = Files.list(dir)) {
            List<String> left = stream.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("strings.json"), left, "non debe quedar ningún ficheiro temporal");
        }
    }

    @Test
    void writeAtomicCreatesTheFileWhenItDoesNotExist(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("novo.json");
        JsonObject obj = new JsonObject();
        obj.addProperty("k", "v");
        JsonIo.writeAtomic(f, obj);
        assertTrue(Files.exists(f));
    }

    /**
     * Fixa o formato exacto de saída. Se alguén cambia a configuración de Gson
     * (indentación ou o escape de HTML), o primeiro push tras o cambio reescribiría
     * o ficheiro completo de 1,4 MB en vez da liña editada: esta proba impídeo.
     */
    @Test
    void serializationFormatIsPinned(@TempDir Path dir) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("a", "x");
        obj.addProperty("b", "<tag> & \"q\" á");

        Path f = dir.resolve("f.json");
        JsonIo.writeAtomic(f, obj);

        assertEquals("""
                {
                  "a": "x",
                  "b": "<tag> & \\"q\\" á"
                }""", Files.readString(f));
    }

    @Test
    void keyOrderIsPreservedAndWritingIsIdempotent(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("strings.json");
        try (InputStream in = getClass().getResourceAsStream("/strings-sample.json")) {
            Files.write(f, in.readAllBytes());
        }

        JsonObject first = JsonIo.read(f);
        JsonIo.writeAtomic(f, first);
        byte[] once = Files.readAllBytes(f);

        JsonIo.writeAtomic(f, JsonIo.read(f));
        assertArrayEquals(once, Files.readAllBytes(f),
                "escribir dúas veces debe dar exactamente os mesmos bytes");
        assertEquals(new java.util.ArrayList<>(first.keySet()),
                new java.util.ArrayList<>(JsonIo.read(f).keySet()),
                "a orde das claves non pode cambiar");
    }

    @Test
    void stringOrNullIgnoresMissingAndNonStringValues() {
        JsonObject obj = new JsonObject();
        obj.addProperty("texto", "ola");
        obj.addProperty("numero", 7);
        obj.add("lista", new com.google.gson.JsonArray());

        assertEquals("ola", JsonIo.stringOrNull(obj, "texto"));
        assertEquals(null, JsonIo.stringOrNull(obj, "numero"));
        assertEquals(null, JsonIo.stringOrNull(obj, "lista"));
        assertEquals(null, JsonIo.stringOrNull(obj, "inexistente"));
        assertEquals(null, JsonIo.stringOrNull(null, "texto"));
        assertFalse(obj.has("inexistente"));
    }
}
