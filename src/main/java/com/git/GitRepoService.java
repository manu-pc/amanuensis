package com.git;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Envoltorio de JGit para o repositorio de tradución clonado en lang/.
 * Cada instancia opera sobre un único directorio de repositorio.
 *
 * O ficheiro JSON en cuestión só ten valores string a nivel de clave (a app
 * nunca engade/quita claves, só edita valores), así que a reconciliación de
 * conflitos compárase a nivel de clave JSON en vez de fusión textual de git.
 */
public class GitRepoService {

    public static final String DEFAULT_REMOTE = "https://github.com/manu-pc/deltarune-en-galego-DEV.git";

    // Todas as instancias operan sobre a mesma carpeta lang/, e varios fíos tócana
    // á vez (auto-pull en segundo plano en GuiApp + push manual en LocalView).
    // Este lock estático serializa as operacións multi-paso (clone/pull/push) para
    // que un auto-pull non se cole entre o commit e o push doutro fío.
    private static final ReentrantLock GIT_LOCK = new ReentrantLock();

    /** Progreso das operacións de rede (clone/pull/push) para amosar na UI. */
    public interface ProgressListener {
        /** percent = -1 significa indeterminado (traballo total descoñecido). */
        void onProgress(String task, int percent);
    }

    /** Adapta o ProgressMonitor de JGit a un ProgressListener sinxelo. */
    private static final class ListenerMonitor implements ProgressMonitor {
        private final ProgressListener listener;
        private String task = "";
        private int total;
        private int done;

        ListenerMonitor(ProgressListener listener) {
            this.listener = listener;
        }

        @Override public void start(int totalTasks) { }

        @Override public void beginTask(String title, int totalWork) {
            this.task = title != null ? title : "";
            this.total = totalWork;
            this.done = 0;
            emit();
        }

        @Override public void update(int completed) {
            this.done += completed;
            emit();
        }

        @Override public void endTask() { }

        @Override public boolean isCancelled() { return false; }

        @Override public void showDuration(boolean enabled) { }

        private void emit() {
            int pct = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
            listener.onProgress(task, pct);
        }
    }

    private static ProgressMonitor monitorOrNull(ProgressListener listener) {
        return listener != null ? new ListenerMonitor(listener) : null;
    }

    private final Path repoDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public GitRepoService(Path repoDir) {
        this.repoDir = repoDir;
    }

    public boolean isCloned() {
        return Files.isDirectory(repoDir.resolve(".git"));
    }

    /**
     * Clona o repositorio de tradución en repoDir. Se repoDir xa existe con
     * contido pero sen .git (caso de hoxe: alguén copiou lang/ a man), gárdase
     * a carpeta existente como copia de seguridade en vez de sobrescribila.
     */
    public void cloneRepo(String remoteUrl, String token) throws GitAPIException, IOException {
        cloneRepo(remoteUrl, token, null);
    }

    public void cloneRepo(String remoteUrl, String token, ProgressListener progress)
            throws GitAPIException, IOException {
        GIT_LOCK.lock();
        try {
            if (Files.exists(repoDir) && !isCloned()) {
                boolean nonEmpty;
                try (var stream = Files.list(repoDir)) {
                    nonEmpty = stream.findAny().isPresent();
                }
                if (nonEmpty) {
                    // A carpeta xa ten ficheiros pero sen .git: caso típico de descargar
                    // o repo como .zip de GitHub. NON se pode mover/renomear a carpeta
                    // (o propio .jar execútase dende dentro e Windows bloquéao -> "outro
                    // proceso está a usar este ficheiro"). Nin sequera se pode sobrescribir
                    // a árbore de traballo, porque o .jar en execución está trackeado no
                    // repo. Solución: inicializar o git in situ e apuntar a HEAD á punta
                    // remota cun reset "mixed" (só o índice; a árbore de traballo, que xa
                    // coincide co commit do .zip, queda intacta e o .jar non se toca).
                    initInPlace(remoteUrl, token, progress);
                    return;
                }
            }
            CloneCommand clone = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(repoDir.toFile())
                    .setProgressMonitor(monitorOrNull(progress));
            withAuth(clone, token);
            clone.call().close();
        } finally {
            GIT_LOCK.unlock();
        }
    }

    /**
     * Adopta unha árbore de traballo existente (descargada como .zip, sen .git)
     * como clon do repo remoto sen mover nin sobrescribir ningún ficheiro:
     *  1. git init in situ,
     *  2. engadir o remoto e facer fetch,
     *  3. crear a rama local seguindo á remota e apuntar HEAD a ela,
     *  4. reset MIXED (só actualiza o índice; a árbore de traballo queda igual).
     * Como os ficheiros do .zip son idénticos ao commit remoto, a árbore queda
     * limpa e o .jar en execución nunca se reescribe.
     */
    private void initInPlace(String remoteUrl, String token, ProgressListener progress)
            throws GitAPIException, IOException {
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Repository repo = git.getRepository();
            git.remoteAdd().setName("origin").setUri(new URIish(remoteUrl)).call();
            withAuth(git.fetch(), token)
                    .setRemote("origin")
                    .setProgressMonitor(monitorOrNull(progress))
                    .call();

            String branch = remoteDefaultBranch(git, token);
            ObjectId remoteTip = repo.resolve("refs/remotes/origin/" + branch);
            if (remoteTip == null) {
                throw new IOException("non se puido atopar a rama remota orixe/" + branch);
            }

            // crear/actualizar a rama local -> punta remota e facer HEAD simbólico cara a ela
            RefUpdate ru = repo.updateRef("refs/heads/" + branch);
            ru.setNewObjectId(remoteTip);
            ru.forceUpdate();
            repo.updateRef(Constants.HEAD).link("refs/heads/" + branch);

            // configurar o seguimento para que pull/push saiban a que rama remota van
            StoredConfig cfg = repo.getConfig();
            cfg.setString("branch", branch, "remote", "origin");
            cfg.setString("branch", branch, "merge", "refs/heads/" + branch);
            cfg.save();

            // só índice: a árbore de traballo (idéntica ao commit) non se toca
            git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(branch).call();
        } catch (URISyntaxException e) {
            throw new IOException("URL do remoto non válida: " + remoteUrl, e);
        }
    }

    /** Cambios reais en ficheiros trackeados. Ignora *.copy*.json e o dicionario persoal (non trackeados). */
    public boolean hasTrackedChanges() throws IOException, GitAPIException {
        try (Git git = Git.open(repoDir.toFile())) {
            Status status = git.status().call();
            return !status.getModified().isEmpty()
                    || !status.getChanged().isEmpty()
                    || !status.getMissing().isEmpty()
                    || !status.getRemoved().isEmpty();
        }
    }

    public enum PullOutcome { UP_TO_DATE, UPDATED, SKIPPED_DIRTY, FAILED }

    /** Pull seguro: só actúa se non hai cambios pendentes en ficheiros trackeados. */
    public PullOutcome pullIfSafe(String token) {
        GIT_LOCK.lock();
        try {
            if (hasTrackedChanges()) return PullOutcome.SKIPPED_DIRTY;
            try (Git git = Git.open(repoDir.toFile())) {
                ObjectId before = git.getRepository().resolve("HEAD");
                withAuth(git.pull(), token).call();
                ObjectId after = git.getRepository().resolve("HEAD");
                return Objects.equals(before, after) ? PullOutcome.UP_TO_DATE : PullOutcome.UPDATED;
            }
        } catch (Exception e) {
            return PullOutcome.FAILED;
        } finally {
            GIT_LOCK.unlock();
        }
    }

    /**
     * Estado do remoto respecto ao local tras un fetch:
     *  AHEAD       — o remoto ten commits que non temos (habería que reconciliar)
     *  NOT_AHEAD   — o remoto non trae nada novo (estamos igual ou adiantados)
     *  UNAVAILABLE — non se puido contactar co remoto (sen rede, token, etc.)
     */
    public enum RemoteState { AHEAD, NOT_AHEAD, UNAVAILABLE }

    /** Fai fetch e di se o remoto avanzou. Non toca a árbore de traballo. */
    public RemoteState checkRemoteAdvance(String token) {
        GIT_LOCK.lock();
        try (Git git = Git.open(repoDir.toFile())) {
            withAuth(git.fetch(), token).call();
            Repository repo = git.getRepository();
            ObjectId local = repo.resolve("HEAD");
            ObjectId remote = repo.resolve("refs/remotes/origin/" + remoteDefaultBranch(git, token));
            if (local == null || remote == null || local.equals(remote)) return RemoteState.NOT_AHEAD;
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit localC = walk.parseCommit(local);
                RevCommit remoteC = walk.parseCommit(remote);
                // o remoto ten novidades se non é ancestro do local
                return walk.isMergedInto(remoteC, localC) ? RemoteState.NOT_AHEAD : RemoteState.AHEAD;
            }
        } catch (Exception e) {
            return RemoteState.UNAVAILABLE;
        } finally {
            GIT_LOCK.unlock();
        }
    }

    /** True só se o remoto avanzou (fetch OK e ten commits novos). */
    public boolean remoteHasNewCommits(String token) {
        return checkRemoteAdvance(token) == RemoteState.AHEAD;
    }

    /**
     * Commit + push de TODOS os ficheiros trackeados con cambios, reconciliando
     * conflitos por clave JSON ficheiro a ficheiro (ver commitAndPush). Devolve
     * Success se todo subiu, Conflict se algún entrou en conflito (coas súas PR
     * abertas), ou Failure ante un erro duro.
     */
    public PushOutcome commitAndPushAllDirty(String subject, String authorName, String authorEmail, String token) {
        List<String> dirty = new ArrayList<>();
        GIT_LOCK.lock();
        try (Git git = Git.open(repoDir.toFile())) {
            Status st = git.status().call();
            Set<String> set = new LinkedHashSet<>();
            set.addAll(st.getModified());
            set.addAll(st.getChanged());
            dirty.addAll(set);
        } catch (Exception e) {
            return new PushOutcome.Failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            GIT_LOCK.unlock();
        }
        if (dirty.isEmpty()) return new PushOutcome.Success();

        List<String> conflicts = new ArrayList<>();
        List<String> prUrls = new ArrayList<>();
        for (String rel : dirty) {
            PushOutcome o = commitAndPush(Path.of(rel), subject, authorName, authorEmail, token);
            if (o instanceof PushOutcome.Failure f) return f;
            if (o instanceof PushOutcome.Conflict c) {
                conflicts.add(rel + " (" + c.lineRanges() + ")");
                if (c.prUrl() != null) prUrls.add(c.prUrl());
            }
        }
        if (!conflicts.isEmpty()) {
            return new PushOutcome.Conflict(String.join("; ", conflicts), "(varias ramas)",
                    prUrls.isEmpty() ? null : String.join("  ", prUrls));
        }
        return new PushOutcome.Success();
    }

    // ---------------------------------------------------------------
    // publicar cambios: commit + push, con conciliación de conflitos
    // ---------------------------------------------------------------

    public sealed interface PushOutcome {
        record Success() implements PushOutcome {
        }

        record Conflict(String lineRanges, String fallbackBranch, String prUrl) implements PushOutcome {
        }

        record Failure(String reason) implements PushOutcome {
        }
    }

    public PushOutcome commitAndPush(Path relativeFile, String subject,
                                      String authorName, String authorEmail, String token) {
        String relPath = relativeFile.toString().replace('\\', '/');
        String fullMessage = subject + "\n\nFeito dende amanuensis";
        PersonIdent author = new PersonIdent(authorName, authorEmail);

        GIT_LOCK.lock();
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();

            git.add().addFilepattern(relPath).call();
            RevCommit ourCommit = git.commit()
                    .setOnly(relPath)
                    .setAuthor(author).setCommitter(author)
                    .setMessage(fullMessage)
                    .call();

            if (tryPush(git, token)) {
                return new PushOutcome.Success();
            }

            // Rexeitado: o remoto avanzou. Traer os cambios e reconciliar a nivel de clave JSON.
            // Usar a rama por defecto do remoto (non o nome local: podería ser "master").
            String remoteBranch = remoteDefaultBranch(git, token);
            withAuth(git.fetch(), token).call();
            ObjectId theirsId = repo.resolve("refs/remotes/origin/" + remoteBranch);
            if (theirsId == null) {
                return new PushOutcome.Failure("non se puido atopar a rama remota orixe/" + remoteBranch);
            }

            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit theirsCommit = walk.parseCommit(theirsId);
                RevCommit baseCommit = ourCommit.getParentCount() > 0
                        ? walk.parseCommit(ourCommit.getParent(0))
                        : null;

                JsonObject baseJson = baseCommit != null ? readJsonAt(repo, baseCommit, relPath) : new JsonObject();
                JsonObject theirsJson = readJsonAt(repo, theirsCommit, relPath);
                JsonObject oursJson = readWorkingTreeJson(relPath);

                Map<String, String> conflictingKeys = new LinkedHashMap<>();
                JsonObject merged = theirsJson.deepCopy();

                Set<String> allKeys = new LinkedHashSet<>();
                oursJson.keySet().forEach(allKeys::add);
                baseJson.keySet().forEach(allKeys::add);

                for (String key : allKeys) {
                    String baseVal = stringOrNull(baseJson, key);
                    String oursVal = stringOrNull(oursJson, key);
                    if (Objects.equals(oursVal, baseVal)) continue; // non cambiamos esta clave

                    String theirsVal = stringOrNull(theirsJson, key);
                    if (Objects.equals(theirsVal, baseVal)) {
                        merged.addProperty(key, oursVal);
                    } else if (Objects.equals(theirsVal, oursVal)) {
                        // xa coincide (mesma tradución en ambos os lados), nada que facer
                    } else {
                        conflictingKeys.put(key, oursVal);
                    }
                }

                if (!conflictingKeys.isEmpty()) {
                    String branch = "amanuensis-conflito-" + safeBranchToken(authorName)
                            + "-" + (System.currentTimeMillis() / 1000);
                    pushCommitToBranch(git, ourCommit, branch, token);
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(theirsId.getName()).call();

                    List<Integer> conflictLines = mapKeysToLineIndices(theirsJson, conflictingKeys.keySet());
                    String ranges = compressRanges(conflictLines);

                    // Abrir unha PR da rama de conflito cara á rama activa, para que a
                    // rama non quede orfa: un mantedor pode revisala e fusionala.
                    String prUrl = null;
                    GitHubApi.Repo ghRepo = GitHubApi.parseRepo(
                            repo.getConfig().getString("remote", "origin", "url"));
                    if (ghRepo != null) {
                        String prTitle = "Conflito de tradución (liñas " + ranges + ")";
                        String prBody = "Estas liñas (" + ranges + ") editáronse á vez ca outra persoa.\n\n"
                                + "Os cambios están nesta rama para revisar e fusionar manualmente, "
                                + "sen perder nada.\n\nFeito dende amanuensis.";
                        prUrl = GitHubApi.createPullRequest(token, ghRepo, branch, remoteBranch, prTitle, prBody);
                    }
                    return new PushOutcome.Conflict(ranges, branch, prUrl);
                }

                // Sen conflito real: reconstruír o noso cambio enriba da punta remota actual
                // (historial lineal en vez dun commit de fusión).
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(theirsId.getName()).call();
                writeJson(repoDir.resolve(relPath), merged);
                git.add().addFilepattern(relPath).call();
                git.commit().setOnly(relPath).setAuthor(author).setCommitter(author).setMessage(fullMessage).call();

                if (tryPush(git, token)) {
                    return new PushOutcome.Success();
                }
                return new PushOutcome.Failure("outra persoa subiu cambios xusto agora; téntao de novo");
            }
        } catch (Exception e) {
            return new PushOutcome.Failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            GIT_LOCK.unlock();
        }
    }

    /**
     * Rama por defecto do remoto (o seu HEAD), consultada directamente ao
     * servidor para NON depender da configuración local: unha copia antiga pode
     * estar checkouteada en "master" e seguir a "origin/master", e un push sen
     * refspec recrearía esa rama. Preguntamos ao remoto cal é o seu HEAD (symref)
     * en vez de fiarnos do nome local. Se non se pode determinar, cae en "main".
     */
    private String remoteDefaultBranch(Git git, String token) {
        try {
            Collection<Ref> refs = withAuth(git.lsRemote(), token).setRemote("origin").call();
            for (Ref r : refs) {
                if (Constants.HEAD.equals(r.getName()) && r.isSymbolic()) {
                    return Repository.shortenRefName(r.getTarget().getName()); // "main"
                }
            }
        } catch (Exception ignored) {
            // sen rede/token: caemos na rama por defecto coñecida
        }
        return "main";
    }

    private boolean tryPush(Git git, String token) throws GitAPIException {
        Iterable<PushResult> results = withAuth(git.push(), token)
                .setRefSpecs(new RefSpec("HEAD:refs/heads/" + remoteDefaultBranch(git, token)))
                .call();
        for (PushResult r : results) {
            for (RemoteRefUpdate update : r.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK
                        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    return false;
                }
            }
        }
        return true;
    }

    private void pushCommitToBranch(Git git, RevCommit commit, String branchName, String token) throws GitAPIException {
        withAuth(git.push(), token)
                .setRefSpecs(new RefSpec(commit.getName() + ":refs/heads/" + branchName))
                .call();
    }

    private JsonObject readJsonAt(Repository repo, RevCommit commit, String relPath) throws IOException {
        try (TreeWalk tw = TreeWalk.forPath(repo, relPath, commit.getTree())) {
            if (tw == null) return new JsonObject();
            ObjectId blobId = tw.getObjectId(0);
            ObjectLoader loader = repo.open(blobId);
            String text = new String(loader.getBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }

    private JsonObject readWorkingTreeJson(String relPath) throws IOException {
        String text = Files.readString(repoDir.resolve(relPath), StandardCharsets.UTF_8);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private void writeJson(Path file, JsonObject obj) throws IOException {
        Files.writeString(file, gson.toJson(obj));
    }

    private static String stringOrNull(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        var el = obj.get(key);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    /** Mesma orde/filtro que LocHelper: só valores string, na orde do obxecto JSON. */
    private static List<Integer> mapKeysToLineIndices(JsonObject fileJson, Set<String> keys) {
        List<Integer> indices = new ArrayList<>();
        int idx = 0;
        for (String key : fileJson.keySet()) {
            var el = fileJson.get(key);
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) continue;
            if (keys.contains(key)) indices.add(idx + 1); // 1-based, coma na UI ("liña N")
            idx++;
        }
        return indices;
    }

    /**
     * Comprime índices de liña (1-based) en intervalos lexibles, unindo dous
     * cando o oco entre eles é inferior a 5 liñas non editadas (algunhas
     * liñas non son traducibles e non deben partir un tramo por lo demais continuo).
     */
    public static String compressRanges(List<Integer> lineIndices) {
        if (lineIndices == null || lineIndices.isEmpty()) return "";
        List<Integer> sorted = new ArrayList<>(new LinkedHashSet<>(lineIndices));
        Collections.sort(sorted);

        List<int[]> ranges = new ArrayList<>();
        int start = sorted.get(0), end = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            int n = sorted.get(i);
            if (n - end < 5) {
                end = n;
            } else {
                ranges.add(new int[]{start, end});
                start = end = n;
            }
        }
        ranges.add(new int[]{start, end});

        List<String> parts = new ArrayList<>();
        for (int[] r : ranges) {
            parts.add(r[0] == r[1] ? String.valueOf(r[0]) : (r[0] + "-" + r[1]));
        }
        return String.join(", ", parts);
    }

    private static String safeBranchToken(String s) {
        String slug = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "usuario" : slug;
    }

    private static <C extends TransportCommand<C, ?>> C withAuth(C cmd, String token) {
        if (token != null) {
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""));
        }
        return cmd;
    }
}
