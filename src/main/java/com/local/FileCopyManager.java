package com;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public class FileCopyManager {

    private final LocHelper locHelper;
    private final Path copyPath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /*
     * crea unha copia do ficheiro orixinal ao construír a instancia.
     *? se xa existe un ficheiro con nome filename.copy.json, engádese un sufixo con timestamp.
      */
    public FileCopyManager(LocHelper locHelper, String filename) throws IOException {
        this.locHelper = locHelper;
        Path originalPath = Path.of(filename);

        Path candidate = Path.of(filename + ".copy.json");
        if (Files.exists(candidate)) {
            String ts = String.valueOf(Instant.now().toEpochMilli());
            candidate = Path.of(filename + ".copy." + ts + ".json");
        }

        // copiar arquivo orixinal → copia
        Files.copy(originalPath, candidate, StandardCopyOption.COPY_ATTRIBUTES);
        this.copyPath = candidate;
    }

     //devolve a ruta do ficheiro copia sobre a que se están a facer as modificacións

    public Path getCopyPath() {
        return copyPath;
    }

    public void updateLine(int index, String formattedValue) throws IOException {
        // ler o json da copia
        String jsonText = Files.readString(copyPath);
        JsonObject obj = JsonParser.parseString(jsonText).getAsJsonObject();

        // obter a clave correspondente desde locHelper (asegura que usamos a mesma orde)
        String key = locHelper.getKey(index);

        // se a clave non existe no ficheiro copia, podemos crearla ou lanzar erro; aquí comprobamos:
        if (!obj.has(key)) {
            // intentar buscar por orde: como fallback, lemos as chaves por orde e substituimos pola posición index
            // isto é improbable se locHelper e a copia viñeron do mesmo ficheiro, pero por seguridade:
            JsonObject newObj = new JsonObject();
            int i = 0;
            for (String k : obj.keySet()) {
                if (i == index) {
                    newObj.addProperty(k, formattedValue);
                } else {
                    newObj.add(k, obj.get(k));
                }
                i++;
            }
            // reescribir copia
            writeJsonToCopy(newObj);
            return;
        }

        // substituir só o valor desa clave por formattedValue
        JsonElement newVal = gson.toJsonTree(formattedValue);
        obj.add(key, newVal);

        // reescribir o ficheiro copia mantendo formato legible
        writeJsonToCopy(obj);
    }

    private void writeJsonToCopy(JsonObject obj) throws IOException {
        String out = gson.toJson(obj);
        Files.writeString(copyPath, out);
    }
}
