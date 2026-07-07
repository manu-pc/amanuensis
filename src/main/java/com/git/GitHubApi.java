package com.git;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Chamadas á API REST de GitHub que non teñen que ver co login (ver GitHubAuth).
 * De momento só: abrir unha pull request para as ramas de conflito que crea
 * GitRepoService, para que un mantedor poida revisalas e fusionalas.
 */
public class GitHubApi {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** owner/repo dun repositorio de GitHub, extraído da URL do remoto. */
    public record Repo(String owner, String name) {
    }

    /**
     * Extrae owner/repo dunha URL de remoto de GitHub. Acepta as formas
     * https (https://github.com/owner/repo.git) e ssh (git@github.com:owner/repo.git).
     * Devolve null se non se recoñece.
     */
    public static Repo parseRepo(String remoteUrl) {
        if (remoteUrl == null) return null;
        String s = remoteUrl.trim();
        int gh = s.indexOf("github.com");
        if (gh < 0) return null;
        // saltar "github.com" e o separador que veña (":" en ssh, "/" en https)
        String path = s.substring(gh + "github.com".length());
        if (path.startsWith(":") || path.startsWith("/")) path = path.substring(1);
        if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
        String[] parts = path.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
        return new Repo(parts[0], parts[1]);
    }

    /**
     * Abre unha pull request de headBranch cara a baseBranch. Devolve a URL
     * (html_url) da PR creada, ou null se non se puido crear.
     * Non lanza: calquera fallo devolve null (a rama xa quedou subida igual).
     */
    public static String createPullRequest(String token, Repo repo, String headBranch,
                                           String baseBranch, String title, String body) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("title", title);
            payload.addProperty("head", headBranch);
            payload.addProperty("base", baseBranch);
            payload.addProperty("body", body);

            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.github.com/repos/" + repo.owner() + "/" + repo.name() + "/pulls"))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (resp.statusCode() == 201 && obj.has("html_url")) {
                return obj.get("html_url").getAsString();
            }
            return null;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return null;
        }
    }
}
