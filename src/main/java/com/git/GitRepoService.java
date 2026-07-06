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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        if (Files.exists(repoDir) && !isCloned()) {
            boolean nonEmpty;
            try (var stream = Files.list(repoDir)) {
                nonEmpty = stream.findAny().isPresent();
            }
            if (nonEmpty) {
                Path backup = repoDir.resolveSibling(repoDir.getFileName() + ".backup-" + System.currentTimeMillis());
                Files.move(repoDir, backup);
            }
        }
        CloneCommand clone = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(repoDir.toFile());
        withAuth(clone, token);
        clone.call().close();
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
        }
    }

    // ---------------------------------------------------------------
    // publicar cambios: commit + push, con conciliación de conflitos
    // ---------------------------------------------------------------

    public sealed interface PushOutcome {
        record Success() implements PushOutcome {
        }

        record Conflict(String lineRanges, String fallbackBranch) implements PushOutcome {
        }

        record Failure(String reason) implements PushOutcome {
        }
    }

    public PushOutcome commitAndPush(Path relativeFile, String subject,
                                      String authorName, String authorEmail, String token) {
        String relPath = relativeFile.toString().replace('\\', '/');
        String fullMessage = subject + "\n\nFeito dende amanuensis";
        PersonIdent author = new PersonIdent(authorName, authorEmail);

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
            withAuth(git.fetch(), token).call();
            ObjectId theirsId = repo.resolve("refs/remotes/origin/master");
            if (theirsId == null) {
                return new PushOutcome.Failure("non se puido atopar a rama remota orixe/master");
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
                    return new PushOutcome.Conflict(ranges, branch);
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
        }
    }

    private boolean tryPush(Git git, String token) throws GitAPIException {
        Iterable<PushResult> results = withAuth(git.push(), token).call();
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
