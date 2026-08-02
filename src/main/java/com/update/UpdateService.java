package com.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.git.GitHubApi;

/**
 * Busca, descarga e prepara unha versión nova da aplicación.
 *
 * O tradutor non ten que usar GitHub para nada: todo isto son peticións HTTPS
 * anónimas ao mesmo repositorio que xa ten clonado.
 *
 * <h2>Por que non se substitúe o jar no sitio</h2>
 * Un proceso non pode substituír o seu propio jar. En Windows o ficheiro está
 * bloqueado mentres a JVM o ten aberto, e en Linux, aínda que o borrado funcione,
 * a JVM segue cargando clases do jar de forma perezosa durante toda a sesión, así
 * que cambialo por debaixo rompe a aplicación a media execución. Por iso a
 * descarga déixase preparada nun ficheiro aparte e o intercambio real faino
 * {@link UpdateApplier}, un proceso á parte que espera a que este remate.
 *
 * <h2>Que se comproba antes de instalar nada</h2>
 * <ul>
 * <li>a URL do jar ten que apuntar a un servidor de GitHub ({@link #ALLOWED_HOSTS});</li>
 * <li>o tamaño ten que coincidir co anunciado;</li>
 * <li>o SHA-256 ten que coincidir co anunciado.</li>
 * </ul>
 * Se algo falla bórrase a descarga e non se toca a instalación.
 */
public final class UpdateService {

    /** Carpeta de traballo da actualización, dentro da carpeta base da app. */
    public static final String WORK_DIR = ".amanuensis-update";

    /** Só se descarga de GitHub: un update.json manipulado non pode apuntar a outro sitio. */
    private static final List<String> ALLOWED_HOSTS = List.of(
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
            "raw.githubusercontent.com", "codeload.github.com");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Unha versión publicada máis nova ca a que se está a executar. */
    public record Available(String version, String notes, UpdateManifest.Artifact artifact) {
    }

    /** Aviso de progreso dunha descarga (bytes descargados / total). */
    public interface Progress {
        void bytes(long done, long total);
    }

    private UpdateService() {
    }

    // ---------------------------------------------------------------
    // buscar
    // ---------------------------------------------------------------

    /**
     * URL crúa do {@code update.json} deste repositorio, derivada da URL do remoto
     * (a mesma que xa se usa para clonar), ou null se non é un repo de GitHub.
     */
    public static String manifestUrl(String originUrl, String branch) {
        GitHubApi.Repo repo = GitHubApi.parseRepo(originUrl);
        if (repo == null) {
            return null;
        }
        String ref = branch == null || branch.isBlank() ? "main" : branch;
        return "https://raw.githubusercontent.com/" + repo.owner() + "/" + repo.name()
                + "/" + ref + "/update.json";
    }

    /**
     * Le o manifesto publicado. Nunca lanza: sen rede, sen ficheiro ou con JSON
     * roto devolve null, porque non poder comprobar actualizacións non pode
     * impedir traballar.
     */
    public static UpdateManifest fetchManifest(String originUrl, String branch) {
        String url = manifestUrl(originUrl, branch);
        if (url == null) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Cache-Control", "no-cache")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return UpdateManifest.parse(resp.body());
        } catch (IOException | InterruptedException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Hai unha versión nova para esta plataforma? Devolve null se non, se non se
     * puido consultar, ou se esta é unha copia de desenvolvemento.
     */
    public static Available check(String originUrl, String branch) {
        UpdateManifest manifest = fetchManifest(originUrl, branch);
        if (manifest == null || !AppVersion.isNewerThanCurrent(manifest.version())) {
            return null;
        }
        UpdateManifest.Artifact artifact = manifest.forCurrentPlatform();
        if (artifact == null) {
            return null; // esa versión non publicou jar para esta plataforma
        }
        return new Available(manifest.version(), manifest.notes(), artifact);
    }

    // ---------------------------------------------------------------
    // descargar
    // ---------------------------------------------------------------

    public static Path workDir(Path baseDir) {
        return baseDir.resolve(WORK_DIR);
    }

    /**
     * Descarga o jar novo e déixao preparado ao lado da instalación.
     *
     * @return ruta do jar descargado e verificado
     * @throws IOException se falla a descarga ou se o contido non coincide co
     *         tamaño/SHA-256 anunciados (nese caso non queda nada no disco)
     */
    public static Path download(UpdateManifest.Artifact artifact, Path baseDir, Progress progress)
            throws IOException {
        requireGitHubUrl(artifact.url());

        Path dir = workDir(baseDir);
        Files.createDirectories(dir);
        Path staged = dir.resolve(artifact.file() + ".new");
        Files.deleteIfExists(staged);

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(artifact.url()))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new IOException("o servidor respondeu " + resp.statusCode());
            }
            // a URL final tamén ten que ser de GitHub: as releases redirixen a outro
            // servidor deles, pero non pode acabar en calquera sitio
            requireGitHubUrl(resp.uri().toString());

            long total = artifact.size();
            long done = 0;
            MessageDigest sha = sha256();
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = resp.body();
                    OutputStream out = Files.newOutputStream(staged)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    if (progress != null) {
                        progress.bytes(done, total);
                    }
                    if (done > total) {
                        throw new IOException("o ficheiro é maior do anunciado");
                    }
                }
            }
            if (done != total) {
                throw new IOException("descarga incompleta: " + done + " de " + total + " bytes");
            }
            String got = hex(sha.digest());
            if (!got.equals(artifact.sha256())) {
                throw new IOException("o SHA-256 non coincide (agardábase "
                        + artifact.sha256() + ", obtívose " + got + ")");
            }
            return staged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(staged);
            throw new IOException("descarga interrompida", e);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(staged);
            throw e;
        }
    }

    private static void requireGitHubUrl(String url) throws IOException {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException e) {
            throw new IOException("URL de descarga non válida: " + url);
        }
        if (host == null) {
            throw new IOException("URL de descarga sen servidor: " + url);
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(h)) {
            throw new IOException("a descarga non vén de GitHub: " + host);
        }
    }

    // ---------------------------------------------------------------
    // instalar
    // ---------------------------------------------------------------

    /**
     * Lanza o proceso que substitúe o jar e reinicia a app, e devolve.
     *
     * O que chama ten que <b>saír inmediatamente</b> despois: o proceso lanzado
     * está esperando a que esta JVM remate para poder tocar o ficheiro.
     *
     * @param stagedJar jar xa descargado e verificado
     * @param targetJar jar en execución, o que hai que substituír
     * @throws IOException se non se pode lanzar o proceso (aí non se cambiou nada)
     */
    public static void applyAndRestart(Path stagedJar, Path targetJar) throws IOException {
        String java = javaBinary();
        Path dir = stagedJar.toAbsolutePath().getParent();

        ProcessBuilder pb = new ProcessBuilder(
                java, "-cp", stagedJar.toAbsolutePath().toString(),
                UpdateApplier.class.getName(),
                "--pid", String.valueOf(ProcessHandle.current().pid()),
                "--from", stagedJar.toAbsolutePath().toString(),
                "--to", targetJar.toAbsolutePath().toString(),
                "--java", java);
        pb.directory(targetJar.toAbsolutePath().getParent().toFile());
        // a saída do instalador vai a un ficheiro: se algo falla despois de que
        // esta xanela desapareza, é o único sitio onde queda constancia
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("applier.log").toFile()));
        pb.start();
    }

    /**
     * Ruta do executable de java desta JVM. Non se usa "java" a secas porque nunha
     * instalación de escritorio pode non estar no PATH.
     */
    static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin",
                        UpdateManifest.currentPlatform().equals("windows") ? "java.exe" : "java")
                        .toString());
    }

    /**
     * Limpa o que sobra dunha actualización anterior: descargas a medias e a copia
     * de seguridade do jar (13 MB que non fan falta unha vez que a versión nova
     * arrancou, que é xustamente onde se chama isto).
     *
     * <b>Non</b> se borra {@code applier.log}: se unha actualización fallou, ese
     * ficheiro é a única constancia que queda, porque para entón xa non había
     * ningunha xanela onde amosar o erro.
     */
    public static void cleanWorkDir(Path baseDir) {
        Path dir = workDir(baseDir);
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    if (p.getFileName().toString().endsWith(".new")) {
                        deleteQuietly(p);
                    }
                }
            } catch (IOException ignored) {
                // sen permisos: non é motivo para molestar ao usuario
            }
        }
        Path jar = com.AppDir.runningJar();
        if (jar != null) {
            deleteQuietly(jar.resolveSibling(jar.getFileName() + ".bak"));
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // aínda en uso ou sen permisos: téntase de novo no seguinte arranque
        }
    }

    static void copyReplacing(Path from, Path to) throws IOException {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("esta JVM non ten SHA-256", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** SHA-256 dun ficheiro, en hexadecimal. */
    public static String sha256Of(Path file) throws IOException {
        MessageDigest sha = sha256();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
            }
        }
        return hex(sha.digest());
    }
}
