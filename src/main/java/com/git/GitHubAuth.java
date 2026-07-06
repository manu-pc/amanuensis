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
 * Login en GitHub sen contrasinal nin CLI: OAuth Device Flow.
 * App pública rexistrada como "Amanuensis" (sen client secret).
 */
public class GitHubAuth {

    private static final String CLIENT_ID = "Ov23lizje5MkimIRDrcK";
    // "repo" (non "public_repo") fai falta para funcionar tamén con repos privados.
    private static final String SCOPE = "repo";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                              int expiresInSeconds, int intervalSeconds) {
    }

    public record GitHubUser(long id, String login, String name) {
        /** Enderezo noreply privado de GitHub: sempre dispoñible, sen scope extra. */
        public String noreplyEmail() {
            return id + "+" + login + "@users.noreply.github.com";
        }
    }

    public static DeviceCode requestDeviceCode() throws IOException, InterruptedException {
        String form = "client_id=" + CLIENT_ID + "&scope=" + SCOPE;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://github.com/login/device/code"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!obj.has("device_code")) {
            throw new IOException("resposta inesperada de GitHub: " + resp.body());
        }
        return new DeviceCode(
                obj.get("device_code").getAsString(),
                obj.get("user_code").getAsString(),
                obj.get("verification_uri").getAsString(),
                obj.get("expires_in").getAsInt(),
                obj.get("interval").getAsInt());
    }

    private sealed interface PollResult {
        record Success(String accessToken) implements PollResult {
        }

        record Pending() implements PollResult {
        }

        record Failed(String reason) implements PollResult {
        }
    }

    private static PollResult pollOnce(String deviceCode) throws IOException, InterruptedException {
        String form = "client_id=" + CLIENT_ID
                + "&device_code=" + deviceCode
                + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (obj.has("access_token")) {
            return new PollResult.Success(obj.get("access_token").getAsString());
        }
        String error = obj.has("error") ? obj.get("error").getAsString() : "unknown_error";
        return switch (error) {
            case "authorization_pending", "slow_down" -> new PollResult.Pending();
            default -> new PollResult.Failed(error);
        };
    }

    /**
     * Bloquea o fío chamante ata que o usuario acepte/rexeite en github.com ou
     * caduque o código. Debe chamarse sempre desde un fío en segundo plano.
     */
    public static String pollForToken(DeviceCode code) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + code.expiresInSeconds() * 1000L;
        long intervalMs = Math.max(code.intervalSeconds(), 5) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(intervalMs);
            PollResult r = pollOnce(code.deviceCode());
            if (r instanceof PollResult.Success s) return s.accessToken();
            if (r instanceof PollResult.Failed f) {
                throw new IOException("login rexeitado ou caducado (" + f.reason() + ")");
            }
            // Pending: seguir agardando.
        }
        throw new IOException("o código de acceso caducou, téntao de novo");
    }

    public static GitHubUser fetchUser(String token) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("erro ao consultar o usuario de GitHub: HTTP " + resp.statusCode());
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        String login = obj.get("login").getAsString();
        String name = obj.has("name") && !obj.get("name").isJsonNull()
                ? obj.get("name").getAsString() : login;
        long id = obj.get("id").getAsLong();
        return new GitHubUser(id, login, name);
    }
}
