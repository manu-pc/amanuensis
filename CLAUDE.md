# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Dev run (no package step)
./mvnw javafx:run

# Build fat-jar (Linux) + run
./run.sh

# Build fat-jar for Windows
./mvnw -Pwindows clean package
# output: target/amanuensis-windows-1.1.0.jar

# Run built jar directly
java -jar target/amanuensis-1.1.0.jar

# Tests (marker system only; surefire also runs inside `package`, so run.sh gates on them)
./mvnw test

# Same tests plus a full-corpus roundtrip sweep over a real strings.json
./mvnw -Damanuensis.corpus=$PWD/eng-4.json test

# Rebuild the cross-chapter repeated-message map (run on ENGLISH strings.json)
./scripts/build-message-map.sh [repo-root]     # default: ./deltarune-en-galego-DEV

# Bulk-propagate translations across chapters (dry run unless --aplicar)
./scripts/propagate-chapters.sh [repo-root] [--aplicar] [--forzar-primeira]

# Publish a new app version (builds both jars, GitHub release, writes update.json)
./scripts/release.sh [repo-root]        # needs `gh` authenticated; bump pom version first
```

## Architecture

JavaFX desktop app for editing Undertale/Deltarune localization JSON files (Galician translation project). Java 21, Maven shade plugin produces a self-contained fat-jar with bundled JavaFX natives per platform.

**Entry point split** — `com.Main` is the real `main()` but does NOT extend `Application`; it delegates to `GuiApp.launchApp()`. This is intentional: fat-jars need a non-Application main to avoid "JavaFX runtime components are missing".

**Two-screen flow**:
1. `MainView` — startup screen; lets user pick a `.json` file or double-click from a `lang/` directory listing. Excludes `.copy*.json` and `chapter_settings.json` files. Also hosts GitHub login, clone, and pull-if-safe controls.
2. `LocalView` — main editor; three read-only panes (literal with markers shown as `⏎`, clean plain text) plus an editable `InlineCssTextArea` (RichTextFX). Live preview shows `reapplyFormatting` output as the user types.

**Edit safety (no working copy)** — there is no `.copy.json` file anymore. Every per-line save (`TranslationStore.save`) writes straight to the real JSON (the git clone is already the safety copy) and records the edit key-by-key in an `EditLedger` (`com.local.EditLedger`), which stores, per key, the value it had when the user first touched it (`base`) and the value they wrote (`value`). That `base` is what lets reconciliation tell "I edited this" from "my copy of this key is stale" — without it, a merely-outdated key looked like a deliberate edit and silently overwrote someone else's translation. Ledgers live outside the repo at `~/.amanuensis/ledgers/` (`LedgerStore`), one JSON file per opened translation file, keyed by a hash of its absolute path; `LedgerStore.allEdits`/`ledgersFor` scan that directory for all pending work across every file ever opened, not just the one currently in the editor. Save order is deliberately ledger-then-file: if the app dies between the two writes, `TranslationStore.applyPendingFromLedger()` replays the pending value from the ledger back onto the file on next open. On push, `KeyMerge` (`com.git`) rebuilds the commit content as "HEAD + ledger edits" — never the working tree — so an unrelated stale key can't ride along into a commit; per-key conflicts fall back to the same conflict-branch-and-PR flow described below, and only conflicting keys stay in the ledger afterward (clean keys/whole ledgers are cleared).

**Obsolete keys are not conflicts.** Recompiling the game deletes keys — the chapter-5 restructure dropped 77 of chapters 1–4's. An edit pending on one of those used to be reported as a conflict: a branch and a PR telling the translator "someone edited this line at the same time as you", when nobody had, and a PR that couldn't be merged because it would resurrect a deliberately deleted key. `KeyMerge.reconcile` now separates them (`droppedOut`) and the push returns `PushOutcome.Obsolete`, which clears the ledger entry and says so plainly. The key is still never recreated. Related: `commitIfChanged` exists because when *every* pending edit is obsolete the computed content equals HEAD, and JGit rejects the empty commit with "No changes" — that surfaced as `Failure`, which left the ledger untouched and made the app retry the same impossible push forever.

**App base dir** (`com.AppDir`) — the jar ships **inside** a `deltarune-en-galego-DEV` checkout, next to `.git/` and the `lang/` subfolder. `AppDir.base()` resolves that folder from the **jar's own location** (`getProtectionDomain().getCodeSource()`), NOT the launch cwd — double-clicking in a file manager sets cwd to `$HOME`, so `Path.of(".")` would break. Falls back to cwd in dev (`mvn javafx:run`, code source is `target/classes`). `AppDir.lang()` = `base()/lang`. Everything (git repo dir, file listing, open-by-name) resolves from `base()`.

**GitHub sync** (`com.git`, uses JGit — no system `git` binary needed) — `GitRepoService` targets `AppDir.base()` (the repo root); `lang/` is just a tracked subfolder, so commit paths are repo-root-relative (e.g. `lang/chapter1/strings.json`). Do **not** point git ops at `lang/`; `.git` lives at the base, one level above `lang/`.
- `GitHubAuth` — OAuth **Device Flow** login (no password/CLI); public app `CLIENT_ID` baked in, scope `repo`. Blocking `pollForToken` must run off the UI thread. GitHub author email derived from noreply address (`id+login@users.noreply.github.com`).
- `TokenStore` — token persisted plaintext at `~/.amanuensis/github_token`, protected only by POSIX owner-only perms (not encrypted).
- `GitHubSession` — in-memory singleton holding token + user; loads token from `TokenStore` on construction.
- `GitHubApi` — non-auth REST calls; currently opens a pull request for the conflict branch (`parseRepo` derives owner/repo from the origin URL). Failures return null (branch is already pushed regardless).
- `GitRepoService` — clone / `pullIfSafe` (only pulls when no tracked changes) / `commitAndPush`. **Conflict reconciliation is at JSON-key level, not textual git merge**: the app only ever edits string values, so on push rejection it fetches, compares base/theirs/ours per key, and either replays cleanly onto remote tip (linear history) or, on true conflicts, pushes the local commit to a `amanuensis-conflito-<user>-<ts>` branch, opens a PR from it (via `GitHubApi`, URL returned in `Conflict.prUrl`), hard-resets to remote, and reports conflicting line ranges. Reconciles against the **checked-out** branch (`repo.getBranch()` — remote default is `main`, not `master`). All multi-step ops (clone/pull/push) are serialized by a static `GIT_LOCK` so background auto-pull can't interleave with a push.

`GuiApp` runs a background pull every 5 min; `MainView` pulls on the startup screen (auto + "actualizar agora"); `LocalView`'s "ver cambios" button pulls from the editor.

**Upload prompt on pull** — when a pull site finds local unpushed changes, it checks the remote with `GitRepoService.checkRemoteAdvance` (fetch + ancestor check) → `AHEAD` / `NOT_AHEAD` / `UNAVAILABLE`, and instead of silently skipping it offers to upload:
- `AHEAD` (remote also moved) → prompt `MSG_DIVERGED`; on yes reconciles + pushes (PR on conflict).
- `NOT_AHEAD` (remote has nothing new) → prompt `MSG_LOCAL_ONLY` ("Subilos agora?"); clean push, no merge needed.
- `UNAVAILABLE` (fetch failed — offline/token) → says so, distinct from "nothing new".

On yes → `GitRepoService.commitAndPushAllDirty` (subject, author name/email, token): it gathers **every** pending ledger in `~/.amanuensis/ledgers/` for this repo via `LedgerStore.ledgersFor`, not just the currently-open file's, commits+pushes each with per-file key reconciliation (`commitAndPushAllLedgers` → `commitAndPushKeys`, PR on conflict), and then updates those ledgers itself: cleared on `Success`, only the conflicting keys removed on `Conflict` (so they don't loop forever), left untouched on `Failure` so a retry resends the same edits. Editor-less sites (startup screen `MainView.doPull`, periodic `GuiApp` — periodic only prompts on `AHEAD` to avoid nagging) share `com.gui.GitSync`, which calls this directly; the editor (`LocalView.uploadAllLocalChanges`) calls the same method and afterward reloads its own in-memory ledger from disk (`reloadInPlace`) so it reflects whatever `commitAndPushAllDirty` already did to the ledger file. "Local unpushed changes" = `LedgerStore.hasPendingEdits(AppDir.base())`, i.e. any non-empty ledger for this repo — there is no separate "dirty working copy" concept anymore.

**Self-update** (`com.update`) — translators must never touch GitHub, so the app updates itself over anonymous HTTPS.

Note why the git pull can't do this: `pullIfSafe` → `resetLangTo` (`GitRepoService.java:550`) resets HEAD/index to the remote tip but checks out **only `lang/`**, and `hasTrackedChanges()` is scoped to `lang/` too. That is deliberate — a full `reset --hard` would overwrite the jar the JVM is executing from. Do **not** widen it.

`UpdateService` GETs `update.json` from `raw.githubusercontent.com/manu-pc/amanuensis/master/update.json` — a **fixed public** location, deliberately not derived from the translation repo's origin. `Deltarune-en-Galego/deltarune-en-galego-DEV` is **private**, and both `raw.githubusercontent.com` and private release assets 404 without a token; publishing on the public code repo means update checks and downloads need no credentials at all, so they work before login, on a fresh install, and with an expired token. Jars are **GitHub Release assets** of that repo, not repo files, so neither repo grows ~26MB per release. `AppVersion.current()` reads `Implementation-Version` from the jar manifest (written by the shade `ManifestResourceTransformer` — the app can't self-update without it). A build with no manifest reports `dev` and never updates or gets offered, so the dev machine can't offer to "update" to what it just published. Version comparison is numeric per dotted segment, and a qualifier (`-rc1`, `-SNAPSHOT`) sorts *before* the clean version.

Before installing anything: the download URL host must be GitHub (checked on the initial URL **and** the post-redirect URL, since release assets redirect), size must match, sha256 must match. Any failure deletes the partial download and leaves the install untouched.

**A process cannot replace its own jar** — Windows locks it, and Linux keeps lazily loading classes from it all session. So `UpdateService.applyAndRestart` spawns `UpdateApplier` *from the staged jar* (never the one being replaced), which waits on `ProcessHandle.of(pid)`, copies over the target with a 60×500ms retry (Windows releases the lock a beat after exit), and relaunches. The java binary comes from `ProcessHandle.current().info().command()`, not `PATH`. The old jar is kept as `<name>.bak` and restored if the copy fails; everything is logged to `.amanuensis-update/applier.log`, which is the only diagnostic left once the UI is gone — startup cleanup deletes `*.new` and the `.bak`, never that log.

`UpdateUi` checks every 30 min in a daemon thread and prompts; on confirm it downloads with a `ProgressDialog`, spawns the applier, then `Platform.exit()` + `Runtime.halt(0)`. It **never prompts while `LocalView` has non-blank text in the edit box** (`UpdateUi.setBusyEditing`) — a restart is safe for saved work, since ledgers live in `~/.amanuensis/ledgers/` outside the repo, but would lose what's being typed. A declined version isn't offered again that session. `MainView` shows the running version plus a manual "buscar actualizacións" button.

**Startup repo repair** (`com.gui.RepoBootstrap`) — a translator downloads one file (the jar) and drops it in an empty folder. On startup, in a background thread after `MainView` shows: empty folder → clone `Deltarune-en-Galego/deltarune-en-galego-DEV`; existing clone → `pullIfSafe` then delete obsolete artifacts. Because that repo is private, cloning needs the session token, so with no token it returns `NEEDS_LOGIN` and `MainView.onLogin` clones automatically the moment device-flow login succeeds — the translator never presses a second button.

Pending ledger edits short-circuit to `PENDING_UPLOAD` and hand off to the normal `GitSync` upload flow rather than advancing; startup never discards unpushed work. Obsolete cleanup only removes a path if it is in the `OBSOLETE` list **and** absent from HEAD (`GitRepoService.existsInHead`), so a file returning to the repo stops being deleted automatically — and it never touches `AppDir.runningJar()`, which is usually the `amanuensis.jar` sitting right there. `isEmptyEnoughToClone` ignores dotfiles, `.amanuensis-update/`, `*.bak` and the crash log, so the app's own leftovers don't block a first-run clone while a user's real files do.

**"An empty folder" never is one — the jar is in it.** So `GitRepoService.cloneRepo` can't use `git clone` for the normal first run: JGit refuses a non-empty destination with *"destination path already exists and is not an empty directory"*. It falls back to `initInPlace` (init + fetch + point HEAD at the remote tip), which for any existing directory content is the only option — the folder can't be moved aside, because the running jar is inside it and Windows locks it. `initInPlace` was written for a *different* case (adopting an unzipped GitHub .zip whose files already match the commit) and so ended at a MIXED reset: index yes, disk no. On the real first run that produced a repo where the entire project counted as deleted and there was nothing to open, and it left a `.git` behind — which made `isCloned()` true, hid the "descargar proxecto de tradución" button, and sent a second attempt down the `git clone` path to fail with the message above. Hence `checkoutMissing`, which writes out only `Status.getMissing()`: it completes a fresh folder and never overwrites a local file or the running jar. `RepoBootstrap.repair` also treats "`.git` present but no `lang/`" as an incomplete download and finishes it, so anyone already stuck in that state gets out without touching anything by hand.

**A rewritten remote history is adopted, not treated as divergence.** History was purged on 2026-08-03 (media blobs dropped, three pre-cleanup branches deleted: 222MB → 18MB), which renumbers every commit. For a clone made before that, the local branch is no longer an ancestor of anything, so `pullIfSafe` returned `DIVERGED` and the translator silently stopped receiving anyone's work, forever, with no error. `pullIfSafe` now hard-resets onto the remote tip when the tree is clean **and** `LedgerStore.hasPendingEdits` is false. That guard is the right discriminator because unpushed work only ever lives in a ledger: local commits are created inside the push flows and pushed immediately, and a failed push deliberately leaves the ledger populated. With pending edits it still reports `DIVERGED` and reconciles per key as before.

**The download is shallow and single-branch** (`SHALLOW_DEPTH = 1`, `setNoTags`, an explicit `+refs/heads/<branch>:refs/remotes/origin/<branch>` refspec written into the config by `configureFetchOnly`). History is 222MB; the text being translated is 9MB. The rest is files that are no longer even in the project (55MB of `.zip`, 35MB of `.dll`, 27MB of `.jar`, 16MB of `.mp4`, sprites, sounds) — untracking them didn't remove them from history, so every translator still downloaded the lot. Depth alone is **not** enough: the repo has three pre-cleanup branches (`amanuensis-conflito-*`, `c4trad/*`) whose *tip trees* are 224MB each, so "one commit of every branch" still cost 141MB of the 222MB. Restricting the refspec is what makes it 1.5MB / ~5s, measured against the real repo. Later fetches deliberately carry no depth: in a shallow repo they bring only what's new and keep it shallow, and in someone's pre-existing full clone they behave exactly as before. Nothing is lost because the app never reads history — `KeyMerge` compares HEAD, the remote tip and the ledger, full stop.

The repo URL lives in **one** place, `GitRepoService.DEFAULT_REMOTE` (`RepoBootstrap.DEFAULT_REPO_URL` aliases it). It didn't: when the project moved from the personal account to the `Deltarune-en-Galego` org, the constant behind the manual "descargar proxecto de tradución" button kept pointing at the old path while automatic startup used the new one — so the button 404'd and JGit reported it as `invalid remote: origin`, which names neither the repo nor the real problem. `cloneRepo` now translates `InvalidRemoteException` into "either it doesn't exist or your account has no access" — for a private repo those two are indistinguishable from outside.

**Cross-chapter repeated messages** (`com.local.map`) — chapters share thousands of identical in-game messages, so editing one line should update every chapter it appears in.

Identity **cannot** be derived from the key or the text alone. Keys are `<entity>_slash_<event>_gml_<line>_<index>[_b]`, where `<line>` is the line number in *that chapter's* decompiled GML: the same key can be a different message in the next chapter (763 such keys between ch4 and ch5), the same message can have a different key (ch1 was recompiled: 1290 key matches vs ~3500 real ones), and the same text appears in unrelated places (1861 texts, `" "` in 437 slots, `"Check"` in 68). The `_b` suffix marks strings the translation mod added; ~30 keys per file (`obj_lang_settings_3_0`, `date`, `// From prev chapters`) don't parse at all.

`MessageMapBuilder` therefore aligns **code, not text**: group keys by block `(entity, event)`, sort by `(line, index)`, and LCS-align the value sequences of each shared block between every pair of files — a chapter file is a source dump in code order, so the diff between builds is insertions and deletions. Block scoping is what stops `"Check"` in `obj_shop1` matching `"Check"` in `scr_text`. A second pass per file pair matches identical key + identical value (catches reorders LCS drops, plus the unparseable keys), then union-find over all pairs yields one component per game message. The build asserts the invariant that makes it trustworthy: **no group may hold two distinct texts** — 0 violations over the real 66989 entries → 39926 messages, 9388 repeated groups. 80 groups are flagged `ambiguous` (a chapter contributes two keys; happens when a duplicated line survives in different chapters). Junk-heavy groups exist and are legitimate: the biggest is 154 `"Check"` ACT labels.

`message-map.json` lives at the **repo root** (not `lang/`, which `MainView` lists as editable files), is generated offline by `scripts/build-message-map.sh` **against the English base** (on a half-finished translation the chapters no longer match and alignment loses pairs), and stores each group's English `base` value — that's what makes "untranslated" distinguishable from "deliberately different". The app only reads it; `MessageMap.load` returns an empty map for a missing/corrupt/future-version file, so everything still works without it.

Propagation is **overwrite-everything** by project decision: `TranslationStore.save` writes the line, then `MessagePropagator` writes the same value to every other occurrence, each through **that file's** `EditLedger` — so propagated edits ride the normal push path with no git changes. `TranslationStore` hands the propagator its own already-open ledger for its own file (a second `EditLedger` on the same file would clobber the first), and `EditLedger.beginBatch/endBatch` collapses a bulk run's thousands of `record` calls into one ledger write. Occurrences that already had a different translation are still overwritten but reported (`Result.overwritten`) and turn the editor status bar orange. `LocalView` shows an orange `repeatLabel` whenever the current line is in a group, naming the earliest chapter it comes from.

`PropagateChapters` (`scripts/propagate-chapters.sh`) is the bulk version. Per group it takes the earliest occurrence **that is already translated** (value ≠ group `base`) — taking chapter 1 literally would revert to English everything translated in later chapters but not in ch1, which is exactly the half-finished state. `--forzar-primeira` overrides that. Dry run by default; `--aplicar` writes.

**Shared glossary** (`com.glossary`) — `lang/glosario.xlsx`, the agreed names for characters, items and terms. Edited **outside** Amanuensis in LibreOffice/Excel; the app only distributes it and opens it (`Glossary.open()` → AWT `Desktop`, falling back to `xdg-open`/`open`/`rundll32`). `LocalView` has a "glosario" button.

Deliberately **not** converted to JSON: the sheet is not a regular table (chapters 2–3 are wide blocks of paired English/Galician columns repeated across A–P), so a schema would destroy the layout people actually work in.

It lives in `lang/` for one specific reason: `resetLangTo` only checks out `lang/`, so at the repo root the file reached translators **once, at clone time, and never updated again**. Inside `lang/` it rides the normal pull. It doesn't pollute the editable-file list because `MainView` only lists `.json`.

`XlsxReader` is a minimal xlsx reader (zip + `javax.xml` DOM, no Apache POI — that would add ~10MB to a 13MB fat-jar; the real cost was ~13KB). It exists for change detection: **an xlsx is a zip, so LibreOffice rewrites timestamps and `docProps` on every save** — opening the glossary and closing it untouched already produces different bytes. Comparing bytes would leave the file permanently "modified", making `hasTrackedChanges()` always true and **silently stopping every pull**. So `GitRepoService.contentDigest` compares *cell contents* (sheet names + non-empty cells, sorted, sha256) for `.xlsx`. An unreadable file falls back to the sha256 of its raw bytes — never "unchanged" when unknown. The parser disallows DOCTYPE/external entities.

Sync is whole-file, not `KeyMerge` — a binary has no keys. `commitAndPushFile` is last-writer-wins **with one guard**: it only overwrites the server if the server hasn't touched that file since our base; if it has, our commit goes to the usual `amanuensis-conflito-*` branch + PR and the local copy becomes the server's. Adequate because the glossary is edited rarely (3 commits in its entire history). `commitAndPushAllDirty` calls it after the ledger push — that is the **only** thing that uploads the glossary, since it has no `EditLedger`; without it a local edit would sit dirty forever, blocking pulls and never uploading. It's also excluded from `dirtyFilesWithoutLedger` so it can never reach a discard path.

**Marker system** (`com.local.markers`) — Undertale/Deltarune strings contain formatting codes. The package is pure (no JavaFX, no IO) and fully unit-tested; `LocHelper` is just the JSON-backed line store that delegates to it and caches one token list per line (the editor reapplies formatting on every keystroke).

`MarkerTokenizer.tokenize()` classifies into 6 token types:
- `VISIBLE` — actual text characters (parentheses included, see below)
- `FORMAT` — fixed-position markers (`\E`, `\M`, `\f`, `* ` dialogue prefix, etc.)
- `PENDING` — word-boundary markers (`^n` pauses, `~n`)
- `NEWLINE` — line break markers (`&`, `#`, `\n`)
- `END` — terminator tokens (`/`, `/%`, `%`, `%%`); its `raw` includes the space before the marker
- `TRAILING_WS` — trailing whitespace, kept verbatim

Each `Token` carries `raw` (exact source slice), `text` (what reapply emits) and `clean` (what the user sees). **Invariant: `rawJoin(tokenize(s)).equals(s)`** — tokenization is lossless, which is what keeps trailing spaces and the space before `%` from being eaten.

Relocatable markers use placeholders in the clean text the user edits (`Markers.PLACEHOLDERS`, the single source of truth — `HunspellChecker` asks here instead of hardcoding them):
- `*` ↔ color markers `\cX` / `\CX`
- `~` ↔ text effect `~n`
- `@` ↔ `\On`
- `$` ↔ `\In`

`MarkerStripper.strip()` converts original → user-editable plain text; `MarkerReapplier.reapply()` converts it back. **One algorithm for all lines**: `compile()` turns the token stream into a per-visual-line blueprint (`LineSpec`: prefix formats, anchored mid formats, suffix, anchored pauses, newline marker), then the user's lines are rendered against it. So a `* ` dialogue prefix is re-emitted at the start of *each* visual line, pauses land at the nearest word boundary to their original position, and the newline marker repeats when the user adds lines. `MarkerRenderer.literalForDisplay()` builds the literal pane (`⏎` glyphs) from the same tokens.

- **Parens are ordinary visible characters** (`ParenPolicy.VISIBLE_CHARS`, the default): in the real corpus 1641 of 1810 paren lines are not whole-line wrappers, so treating them as markers showed unbalanced parens in the clean pane. `ParenPolicy.HIDDEN_BALANCED_WRAPPER` is implemented as a strict opt-in (hides only a true balanced whole-line wrapper).
- **`^1` policy** (`CaretOnePolicy`, default `REDERIVE_IF_CHANGED`): unchanged text keeps its original `^1` positions; once the translator edits the line, the `^1` are re-derived from the punctuation of the *plain text* (never injected into marker text). A `^1` reaches sentence-final punctuation only if the original had one there. Punctuation set includes `…` and CJK/fullwidth marks.
- **Word boundaries**: `Markers.isWordLetter` excludes han/kana/hangul/thai — those scripts have no spaces, so pauses stay exactly where they were.

Regression safety net: `GoldenRoundtripTest` asserts `reapply(strip(x)) == x` over `src/test/resources/markers/{edge-cases,corpus-sample}.json`, with `roundtrip-expected-failures.txt` as a shrink-only allow-list (a fix that makes a listed line pass fails the build until the entry is deleted; `target/roundtrip-actual-*.txt` is written on every run to copy from). On the full 15771-line corpus 5 lines are non-identical, all inherent to the design (3 mid-word pauses used as letter-by-letter effects, 2 literal `*` colliding with the color placeholder).

**Spell check** — `HunspellChecker` wraps the system `hunspell` CLI. Dictionary lookup order:
1. `hunspellgal/build/gl.aff` (project-local compiled Galician dictionary)
2. System paths: `/usr/share/hunspell/gl_ES`, `/usr/share/hunspell/gl`, etc.

In practice `hunspellgal/` is gitignored and absent, so it falls back to the system dictionary (install `hunspell-gl`).

Spell check runs on a single daemon thread (`spell-check`) with 350 ms debounce to avoid blocking the UI thread. Personal dictionary stored at `lang/amanuensis-personal.dic`.

## Data layout (`lang/`)

```
message-map.json           # cross-chapter repeated-message map (repo root, generated)
update.json                # published app version + jar sha256/URLs (repo root, generated)
.amanuensis-update/        # staged download + applier.log (untracked, app-managed)
lang/
  settings.json              # project metadata (lang name, URLs, etc.)
  glosario.xlsx              # shared glossary — edited in LibreOffice, NOT in the editor
  amanuensis-personal.dic    # user's personal spell-check word list
  chapter1/
    chapter_settings.json    # chapter metadata — NOT opened in editor
    strings.json             # translatable strings — opened in editor
    sprites/ sounds/ vid/    # localized game assets — on disk, NOT tracked
  ...
  fonts/                     # game fonts (on disk, NOT tracked)
```

The `lang/` directory must be next to the jar at runtime. `MainView` lists all `.json` files under `lang/` recursively, excluding `*.copy*.json` and `chapter_settings.json`.

**Only the translation JSON (plus the glossary and docs) is tracked.** `.gitignore` has always excluded `*.png`/`*.ogg`/`*.mp4`/`*.ttf`, but 698 such files had been added before that rule and stayed in the index — which is why chapter 5's assets were untracked while chapters 1–4's were not. They were untracked (`git rm --cached`, files kept on disk); history still holds the blobs, so the clone is still ~222MB. **Anyone assembling a release must supply the sprites/sounds/fonts from outside the repo** — fonts especially, since the game needs them to render accented Galician glyphs.
