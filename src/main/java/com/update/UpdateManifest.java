package com.update;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * O {@code update.json} que anuncia a versión publicada da app.
 *
 * Vive na raíz do repositorio de tradución e léase por HTTPS anónimo dende
 * {@code raw.githubusercontent.com} — o tradutor non precisa conta de GitHub nin
 * ter feito login na app. O jar en si vai como <i>asset</i> dunha release, que é
 * o que evita que o repositorio medre 26 MB por versión.
 *
 * <pre>
 * {
 *   "version": "1.2.0",
 *   "notes": "Propagación entre capítulos",
 *   "artifacts": {
 *     "linux":   {"file": "amanuensis.jar",
 *                 "url": "https://github.com/owner/repo/releases/download/v1.2.0/amanuensis.jar",
 *                 "sha256": "…", "size": 13340840},
 *     "windows": {"file": "amanuensis-windows.jar", "url": "…", "sha256": "…", "size": …}
 *   }
 * }
 * </pre>
 *
 * {@code sha256} e {@code size} son obrigatorios: son o que se comproba tras a
 * descarga, e sen eles non se instala nada.
 */
public record UpdateManifest(String version, String notes, Map<String, Artifact> artifacts) {

    /** Un jar publicado para unha plataforma. */
    public record Artifact(String platform, String file, String url, String sha256, long size) {
    }

    /** Nome de plataforma desta máquina: {@code windows}, {@code mac} ou {@code linux}. */
    public static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        return "linux";
    }

    /**
     * O artefacto desta plataforma, ou null se a versión publicada non trae un jar
     * para ela (p.ex. só se publicou o de Windows).
     */
    public Artifact forCurrentPlatform() {
        return artifacts.get(currentPlatform());
    }

    /** Analiza o JSON. Devolve null se falta algo esencial en vez de lanzar. */
    public static UpdateManifest parse(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String version = string(obj, "version");
            if (version == null || version.isBlank()) {
                return null;
            }
            Map<String, Artifact> artifacts = new LinkedHashMap<>();
            if (obj.has("artifacts") && obj.get("artifacts").isJsonObject()) {
                JsonObject arts = obj.getAsJsonObject("artifacts");
                for (String platform : arts.keySet()) {
                    if (!arts.get(platform).isJsonObject()) {
                        continue;
                    }
                    JsonObject a = arts.getAsJsonObject(platform);
                    String url = string(a, "url");
                    String sha = string(a, "sha256");
                    long size = a.has("size") ? a.get("size").getAsLong() : -1;
                    String file = string(a, "file");
                    // sen hash, tamaño ou url non se pode verificar: descártase
                    if (url == null || sha == null || size <= 0 || file == null) {
                        continue;
                    }
                    artifacts.put(platform, new Artifact(platform, file, url,
                            sha.trim().toLowerCase(Locale.ROOT), size));
                }
            }
            return new UpdateManifest(version.trim(), string(obj, "notes"), Map.copyOf(artifacts));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        return obj.get(key).getAsString();
    }
}
