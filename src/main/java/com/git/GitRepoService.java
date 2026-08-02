package com.git;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.local.EditLedger;
import com.local.JsonIo;
import com.local.LedgerStore;

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

    // A app só xestiona a subcarpeta lang/. Todo o de fóra (o .jar, readme, scripts,
    // ficheiros que o usuario cree) é asunto do usuario: nunca se reporta como "sen
    // subir", nin se sobrescribe ao pullear, nin se inclúe nos commits.
    private static final String LANG_DIR = "lang";

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

    public GitRepoService(Path repoDir) {
        this.repoDir = repoDir;
    }

    public boolean isCloned() {
        return Files.isDirectory(repoDir.resolve(".git"));
    }

    /**
     * Existe esa ruta no commit actual? Úsao {@link com.gui.RepoBootstrap} para non
     * borrar como «resto vello» algo que volvese formar parte do repositorio.
     */
    public boolean existsInHead(String relPath) {
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD^{tree}");
            if (head == null) {
                return false;
            }
            try (RevWalk walk = new RevWalk(repo);
                    org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
                tw.addTree(walk.parseTree(head));
                tw.setRecursive(false);
                tw.setFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(relPath));
                return tw.next();
            }
        } catch (IOException | RuntimeException e) {
            // sen poder mirar, o prudente é non borrar nada
            return true;
        }
    }

    /** URL do remoto «origin», ou null se non hai repo/remoto configurado. */
    public String originUrl() {
        try (Git git = Git.open(repoDir.toFile())) {
            return git.getRepository().getConfig().getString("remote", "origin", "url");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Rama checkouteada, ou null. Non contacta co remoto. */
    public String currentBranch() {
        try (Git git = Git.open(repoDir.toFile())) {
            return git.getRepository().getBranch();
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
            Status status = git.status().addPath(LANG_DIR).call();
            return !status.getModified().isEmpty()
                    || !status.getChanged().isEmpty()
                    || !status.getMissing().isEmpty()
                    || !status.getRemoved().isEmpty();
        }
    }

    public enum PullOutcome { UP_TO_DATE, UPDATED, SKIPPED_DIRTY, DIVERGED, FAILED }

    /**
     * Pull seguro: só actúa se non hai cambios pendentes en ficheiros trackeados.
     *
     * NUNCA fai unha fusión textual (git merge): os ficheiros son JSON e un merge
     * de git deixaría marcadores {@code <<<<<<<} dentro, corrompendo o ficheiro. En
     * troques, faise fetch e só se avanza por fast-forward. Se o historial local
     * diverxe do remoto (p.ex. un commit local que non se chegou a subir porque
     * fallou a rede no medio dun push), devólvese {@link PullOutcome#DIVERGED} en
     * vez de fusionar, para que a chamada o reconcilie a nivel de clave e o suba.
     */
    public PullOutcome pullIfSafe(String token) {
        GIT_LOCK.lock();
        try {
            if (hasTrackedChanges()) return PullOutcome.SKIPPED_DIRTY;
            try (Git git = Git.open(repoDir.toFile())) {
                Repository repo = git.getRepository();
                withAuth(git.fetch(), token).call();
                ObjectId local = repo.resolve("HEAD");
                ObjectId remote = repo.resolve("refs/remotes/origin/" + remoteDefaultBranch(git, token));
                if (local == null || remote == null) return PullOutcome.FAILED;
                if (local.equals(remote)) return PullOutcome.UP_TO_DATE;
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit localC = walk.parseCommit(local);
                    RevCommit remoteC = walk.parseCommit(remote);
                    // o remoto non trae nada novo (estamos igual ou adiantados): nada que pullear.
                    if (walk.isMergedInto(remoteC, localC)) return PullOutcome.UP_TO_DATE;
                    // o local é ancestro estrito do remoto -> fast-forward puro. A árbore está
                    // limpa (comprobado arriba) e non hai commits locais únicos, así que un
                    // reset --hard á punta remota equivale a un FF sen perder nada.
                    if (walk.isMergedInto(localC, remoteC)) {
                        resetLangTo(git, remote);
                        return PullOutcome.UPDATED;
                    }
                    // ambos avanzaron: NON fusionar textualmente. Deixar que a chamada reconcilie.
                    return PullOutcome.DIVERGED;
                }
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
     * Commit + push de todas as edicións rexistradas, ficheiro a ficheiro (ver
     * {@link #commitAndPushKeys}). Devolve Success se todo subiu, Conflict se algún
     * ficheiro entrou en conflito (coas súas PR abertas), ou Failure ante un erro duro.
     *
     * @param editsByFile ruta relativa no repo → (clave → edición) do rexistro
     */
    public PushOutcome commitAndPushAllLedgers(Map<String, Map<String, KeyMerge.KeyEdit>> editsByFile,
            String subject, String authorName, String authorEmail, String token) {
        // Sen edicións rexistradas aínda pode haber commits locais sen subir (p.ex. un
        // push previo que fixo commit e fallou na rede): reconciliar e subir o HEAD.
        boolean nothingPending = editsByFile.values().stream().allMatch(Map::isEmpty);
        if (nothingPending) {
            return pushHeadReconciling(authorName, authorEmail, token);
        }

        List<String> conflicts = new ArrayList<>();
        List<String> prUrls = new ArrayList<>();
        Set<String> conflictKeys = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, KeyMerge.KeyEdit>> e : editsByFile.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            PushOutcome o = commitAndPushKeys(Path.of(e.getKey()), e.getValue(),
                    subject, authorName, authorEmail, token);
            if (o instanceof PushOutcome.Failure f) return f;
            if (o instanceof PushOutcome.Conflict c) {
                conflicts.add(e.getKey() + " (" + c.lineRanges() + ")");
                conflictKeys.addAll(c.conflictKeys());
                if (c.prUrl() != null) prUrls.add(c.prUrl());
            }
        }
        if (!conflicts.isEmpty()) {
            return new PushOutcome.Conflict(String.join("; ", conflicts), "(varias ramas)",
                    prUrls.isEmpty() ? null : String.join("  ", prUrls), conflictKeys);
        }
        return new PushOutcome.Success();
    }

    /**
     * Commit + push de <b>todos</b> os rexistros de edicións pendentes deste
     * repositorio (todos os ficheiros abertos algunha vez neste equipo, non só o do
     * editor que chama), e actualiza eses rexistros segundo o resultado: baléiranse
     * ao subir, e só se quitan as claves en conflito se houbo que abrir rama/PR.
     * Usado polos puntos de subida "sen editor" ({@link com.gui.GitSync}) e por
     * "ver cambios" no editor, onde as edicións doutros ficheiros non están en
     * memoria.
     */
    public PushOutcome commitAndPushAllDirty(String subject, String authorName, String authorEmail, String token) {
        Map<String, EditLedger> ledgers = LedgerStore.ledgersFor(repoDir);
        Map<String, Map<String, KeyMerge.KeyEdit>> editsByFile = new LinkedHashMap<>();
        ledgers.forEach((rel, ledger) -> editsByFile.put(rel, KeyMerge.fromLedger(ledger.entries())));

        PushOutcome outcome = commitAndPushAllLedgers(editsByFile, subject, authorName, authorEmail, token);
        try {
            if (outcome instanceof PushOutcome.Success) {
                for (EditLedger ledger : ledgers.values()) {
                    ledger.clear();
                }
            } else if (outcome instanceof PushOutcome.Conflict c) {
                for (EditLedger ledger : ledgers.values()) {
                    ledger.remove(c.conflictKeys());
                }
            }
            // Failure: non se toca nada, para poder reintentar.
        } catch (IOException ignored) {
            // o push xa fixo o seu; se o rexistro non se puido limpar, quedará
            // pendente e volverá subir o mesmo (idempotente) na próxima tentativa.
        }
        return outcome;
    }

    /** Ficheiros trackeados de lang/ con cambios que NON teñen edicións rexistradas. */
    public List<String> dirtyFilesWithoutLedger(Set<String> ledgerPaths) throws IOException, GitAPIException {
        try (Git git = Git.open(repoDir.toFile())) {
            Status st = git.status().addPath(LANG_DIR).call();
            Set<String> dirty = new LinkedHashSet<>();
            dirty.addAll(st.getModified());
            dirty.addAll(st.getChanged());
            dirty.removeAll(ledgerPaths);
            return new ArrayList<>(dirty);
        }
    }

    /**
     * Descarta os cambios locais dos ficheiros indicados, collendo a versión do
     * índice (equivale a {@code git checkout -- <ruta>}). Úsase para limpar restos
     * do vello sistema de copias, que doutro xeito bloquearían os pull para sempre.
     */
    public void discardLocalChanges(Collection<String> relPaths) throws GitAPIException, IOException {
        if (relPaths.isEmpty()) {
            return;
        }
        GIT_LOCK.lock();
        try (Git git = Git.open(repoDir.toFile())) {
            var checkout = git.checkout();
            relPaths.forEach(checkout::addPath);
            checkout.call();
        } finally {
            GIT_LOCK.unlock();
        }
    }

    // ---------------------------------------------------------------
    // publicar cambios: commit + push, con conciliación de conflitos
    // ---------------------------------------------------------------

    public sealed interface PushOutcome {
        record Success() implements PushOutcome {
        }

        /**
         * @param conflictKeys claves que quedaron en conflito, para que quen chama
         *                     saiba exactamente que entradas do rexistro tocar
         */
        record Conflict(String lineRanges, String fallbackBranch, String prUrl,
                Set<String> conflictKeys) implements PushOutcome {
        }

        record Failure(String reason) implements PushOutcome {
        }
    }

    /**
     * Commit + push dun ficheiro, movendo <b>só</b> as claves editadas polo usuario.
     *
     * O contido a confirmar constrúese como «HEAD + as edicións rexistradas»
     * ({@link KeyMerge#buildCommitContent}) en vez de coller a árbore de traballo tal
     * cal: así unha clave desactualizada no disco non pode colarse no commit. Ese era
     * o fallo que facía que un push de 11 liñas reescribise 183 claves e devolvese 172
     * ao inglés.
     *
     * @param edits clave → edición (valor de partida + valor novo) do rexistro
     */
    public PushOutcome commitAndPushKeys(Path relativeFile, Map<String, KeyMerge.KeyEdit> edits,
            String subject, String authorName, String authorEmail, String token) {
        String relPath = relativeFile.toString().replace('\\', '/');
        String fullMessage = subject + "\n\nFeito dende amanuensis";
        PersonIdent author = new PersonIdent(authorName, authorEmail);

        if (edits.isEmpty()) {
            return new PushOutcome.Success();
        }

        GIT_LOCK.lock();
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();

            // Normalizar a árbore de traballo a «HEAD + as miñas edicións» antes de
            // preparar o commit: o resto do ficheiro queda coma no repositorio.
            ObjectId headId = repo.resolve(Constants.HEAD);
            try (RevWalk walk = new RevWalk(repo)) {
                JsonObject head = headId != null
                        ? readJsonAt(repo, walk.parseCommit(headId), relPath)
                        : readWorkingTreeJson(relPath);
                writeJson(repoDir.resolve(relPath), KeyMerge.buildCommitContent(head, edits));
            }

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
                // A base xa non é un antepasado de git: é a que rexistrou o editor por
                // clave, o valor que tiña cando o usuario a tocou. Así unha clave que
                // simplemente estea desactualizada nin se le nin se move.
                JsonObject theirsJson = readJsonAt(repo, theirsCommit, relPath);

                Map<String, String> conflictingKeys = new LinkedHashMap<>();
                JsonObject merged = KeyMerge.reconcile(theirsJson, edits, conflictingKeys);

                if (!conflictingKeys.isEmpty()) {
                    String branch = "amanuensis-conflito-" + safeBranchToken(authorName)
                            + "-" + (System.currentTimeMillis() / 1000);
                    pushCommitToBranch(git, ourCommit, branch, token);
                    resetLangTo(git, theirsId);

                    // Números de liña só para a mensaxe: son posicionais, e por iso non se
                    // usan para decidir nada (esa era outra fonte de erros).
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
                    return new PushOutcome.Conflict(ranges, branch, prUrl,
                            new LinkedHashSet<>(conflictingKeys.keySet()));
                }

                // Sen conflito real: reconstruír o noso cambio enriba da punta remota actual
                // (historial lineal en vez dun commit de fusión).
                resetLangTo(git, theirsId);
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
     * Move HEAD+índice ao commit indicado e actualiza SÓ o worktree de lang/ para
     * que coincida. Os ficheiros de fóra de lang/ non se tocan no disco (o .jar do
     * usuario, etc. consérvanse), aínda que iso deixe o índice/worktree en desacordo
     * fóra de lang/ — algo que non importa porque todas as operacións da app
     * (status/add/commit) están limitadas a lang/. Substitúe a un `reset --hard` de
     * toda a árbore, que borraría eses ficheiros externos.
     */
    private void resetLangTo(Git git, ObjectId target) throws GitAPIException {
        // MIXED: move HEAD e índice ao obxectivo; o worktree queda intacto.
        git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(target.getName()).call();
        // Traer ao worktree só lang/ dende o índice (agora = obxectivo).
        git.checkout().addPath(LANG_DIR).call();
    }

    /**
     * Mestura a nivel de clave JSON usando un antepasado de git como base.
     *
     * <p>
     * <b>Só se usa xa desde {@link #pushHeadReconciling}</b>, onde segue sendo
     * correcta: alí «o noso lado» non é a árbore de traballo senón un <i>commit</i>
     * local, e a base é o antepasado común real dese commit, así que unha clave que
     * non tocamos ten forzosamente o mesmo valor en ours e en base e sáltase. O
     * camiño normal de publicación usa {@link KeyMerge}, coa base rexistrada por
     * clave polo editor: comparar a árbore de traballo cun antepasado de git era o
     * que facía que claves simplemente desactualizadas sobrescribisen traducións
     * doutras persoas.
     */
    private JsonObject mergeByKey(JsonObject baseJson, JsonObject theirsJson, JsonObject oursJson,
            Map<String, String> conflictingKeysOut) {
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
                conflictingKeysOut.put(key, oursVal);
            }
        }
        return merged;
    }

    /** Ancestro común (merge-base) de dous commits, ou null se non o hai. */
    private ObjectId mergeBase(Repository repo, ObjectId a, ObjectId b) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            RevCommit base = walk.next();
            return base != null ? base.getId() : null;
        }
    }

    /** Rutas .json que cambiaron entre base e head (as que editamos localmente). */
    private List<String> changedJsonPaths(Repository repo, ObjectId base, ObjectId head) throws IOException {
        try (RevWalk rw = new RevWalk(repo);
             org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader();
             org.eclipse.jgit.diff.DiffFormatter df =
                     new org.eclipse.jgit.diff.DiffFormatter(
                             org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            org.eclipse.jgit.treewalk.AbstractTreeIterator baseIter =
                    base != null
                            ? new org.eclipse.jgit.treewalk.CanonicalTreeParser(
                                    null, reader, rw.parseCommit(base).getTree())
                            : new org.eclipse.jgit.treewalk.EmptyTreeIterator();
            org.eclipse.jgit.treewalk.AbstractTreeIterator headIter =
                    new org.eclipse.jgit.treewalk.CanonicalTreeParser(
                            null, reader, rw.parseCommit(head).getTree());
            List<String> out = new ArrayList<>();
            for (org.eclipse.jgit.diff.DiffEntry d : df.scan(baseIter, headIter)) {
                String p = d.getNewPath();
                if (p != null && p.endsWith(".json") && !out.contains(p)) out.add(p);
            }
            return out;
        }
    }

    /**
     * Sube o HEAD local actual reconciliándoo a nivel de clave co remoto. Para o
     * caso no que hai commits locais SEN subir que diverxen do remoto (p.ex. un
     * push que fixo commit e logo fallou na rede): a árbore de traballo está limpa,
     * así que non hai ficheiros "dirty" que reconciliar, pero o commit local segue
     * sen chegar ao servidor. Reutiliza a mesma mestura por clave e o mesmo camiño
     * de rama de conflito + PR ca {@link #commitAndPush}.
     */
    public PushOutcome pushHeadReconciling(String authorName, String authorEmail, String token) {
        PersonIdent author = new PersonIdent(authorName, authorEmail);
        String message = "Actualización de tradución\n\nFeito dende amanuensis";
        GIT_LOCK.lock();
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();
            String remoteBranch = remoteDefaultBranch(git, token);
            withAuth(git.fetch(), token).call();
            ObjectId localId = repo.resolve("HEAD");
            ObjectId theirsId = repo.resolve("refs/remotes/origin/" + remoteBranch);
            if (localId == null || theirsId == null) {
                return new PushOutcome.Failure("non se puido resolver HEAD ou a rama remota");
            }
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit localC = walk.parseCommit(localId);
                RevCommit theirsC = walk.parseCommit(theirsId);
                if (walk.isMergedInto(localC, theirsC)) return new PushOutcome.Success(); // xa está no remoto
                if (walk.isMergedInto(theirsC, localC)) {
                    // adiantados en liña recta: push directo abonda
                    if (tryPush(git, token)) return new PushOutcome.Success();
                    // rexeitado: o remoto moveuse; refrescar e reconciliar embaixo
                    withAuth(git.fetch(), token).call();
                    theirsId = repo.resolve("refs/remotes/origin/" + remoteBranch);
                    theirsC = walk.parseCommit(theirsId);
                }

                ObjectId baseId = mergeBase(repo, localId, theirsId);
                RevCommit baseC = baseId != null ? walk.parseCommit(baseId) : null;

                Map<String, String> conflictingKeys = new LinkedHashMap<>();
                Map<String, JsonObject> mergedByPath = new LinkedHashMap<>();
                JsonObject conflictTheirs = null;
                for (String p : changedJsonPaths(repo, baseId, localId)) {
                    JsonObject baseJson = baseC != null ? readJsonAt(repo, baseC, p) : new JsonObject();
                    JsonObject theirsJson = readJsonAt(repo, theirsC, p);
                    JsonObject oursJson = readJsonAt(repo, localC, p);
                    Map<String, String> c = new LinkedHashMap<>();
                    mergedByPath.put(p, mergeByKey(baseJson, theirsJson, oursJson, c));
                    if (!c.isEmpty()) {
                        conflictingKeys.putAll(c);
                        if (conflictTheirs == null) conflictTheirs = theirsJson;
                    }
                }

                // O commit local diverxente non toca lang/: non é asunto da app.
                // Non resetear nada (non orfanar o commit externo do usuario).
                if (mergedByPath.isEmpty()) return new PushOutcome.Success();

                if (!conflictingKeys.isEmpty()) {
                    String branch = "amanuensis-conflito-" + safeBranchToken(authorName)
                            + "-" + (System.currentTimeMillis() / 1000);
                    pushCommitToBranch(git, localC, branch, token);
                    resetLangTo(git, theirsId);
                    String ranges = compressRanges(mapKeysToLineIndices(conflictTheirs, conflictingKeys.keySet()));
                    String prUrl = null;
                    GitHubApi.Repo ghRepo = GitHubApi.parseRepo(repo.getConfig().getString("remote", "origin", "url"));
                    if (ghRepo != null) {
                        String prTitle = "Conflito de tradución (liñas " + ranges + ")";
                        String prBody = "Estas liñas (" + ranges + ") editáronse á vez ca outra persoa.\n\n"
                                + "Os cambios están nesta rama para revisar e fusionar manualmente, "
                                + "sen perder nada.\n\nFeito dende amanuensis.";
                        prUrl = GitHubApi.createPullRequest(token, ghRepo, branch, remoteBranch, prTitle, prBody);
                    }
                    return new PushOutcome.Conflict(ranges, branch, prUrl,
                            new LinkedHashSet<>(conflictingKeys.keySet()));
                }

                // sen conflito: reconstruír os nosos cambios enriba da punta remota (historial lineal)
                resetLangTo(git, theirsId);
                var commit = git.commit().setAuthor(author).setCommitter(author).setMessage(message);
                for (Map.Entry<String, JsonObject> e : mergedByPath.entrySet()) {
                    writeJson(repoDir.resolve(e.getKey()), e.getValue());
                    git.add().addFilepattern(e.getKey()).call();
                    commit.setOnly(e.getKey()); // só lang/, nunca ficheiros externos
                }
                commit.call();
                if (tryPush(git, token)) return new PushOutcome.Success();
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

    /** Escritura atómica e co mesmo formato ca o resto da app (ver JsonIo). */
    private void writeJson(Path file, JsonObject obj) throws IOException {
        JsonIo.writeAtomic(file, obj);
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return JsonIo.stringOrNull(obj, key);
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
