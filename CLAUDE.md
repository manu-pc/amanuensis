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
# output: target/amanuensis-windows-1.0-SNAPSHOT.jar

# Run built jar directly
java -jar target/amanuensis-1.0-SNAPSHOT.jar

# Build both jars (Linux + Windows) and push them to the deltarune-en-galego-DEV repo
scripts/sync-jars-to-deltarune.sh   # TARGET_REPO path is hardcoded inside

# Propagate an edit to every identical line across all chapters' strings.json
python3 propagate.py init    # build groups.json once (identity fixed at init time)
python3 propagate.py         # detect edits, propagate, rewrite files
python3 propagate.py status  # show groups + pending edits, write nothing
```

No tests exist yet.

## Architecture

JavaFX desktop app for editing Undertale/Deltarune localization JSON files (Galician translation project). Java 21, Maven shade plugin produces a self-contained fat-jar with bundled JavaFX natives per platform.

**Entry point split** — `com.Main` is the real `main()` but does NOT extend `Application`; it delegates to `GuiApp.launchApp()`. This is intentional: fat-jars need a non-Application main to avoid "JavaFX runtime components are missing".

**Two-screen flow**:
1. `MainView` — startup screen; lets user pick a `.json` file or double-click from a `lang/` directory listing. Excludes `.copy*.json` and `chapter_settings.json` files. Also hosts GitHub login, clone, and pull-if-safe controls.
2. `LocalView` — main editor; three read-only panes (literal with markers shown as `⏎`, clean plain text) plus an editable `InlineCssTextArea` (RichTextFX). Live preview shows `reapplyFormatting` output as the user types.

**Working copy safety** — `FileCopyManager` creates `<file>.copy.json` on open (timestamped if one already exists). All per-line saves go to the copy (the durable store). `saveToOriginal` flushes copy → original; it is invoked only by the "gardar e subir a GitHub" action (there is no separate copy→original button — writing the original without pushing had no value since the original exists only to be committed).

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

On yes → `commitAndPushAllDirty` (commit+push every dirty tracked file, per-file key reconciliation → PR on conflict). Editor-less sites (startup screen `MainView.doPull`, periodic `GuiApp` — periodic only prompts on `AHEAD` to avoid nagging) share `com.gui.GitSync`; the editor (`LocalView`) has its own variant that first flushes the working copy (`saveToOriginal`) so pending edits are included. "Local unpushed changes" = tracked-dirty everywhere, plus pending copy edits (`editedLines`) in the editor.

**Marker system** (`LocHelper`) — Undertale/Deltarune strings contain formatting codes. The tokenizer (`tokenize()`) classifies them into 5 types:
- `VISIBLE` — actual text characters
- `FORMAT` — fixed-position markers (`\E`, `\M`, `\R`, `(`, `* `, etc.)
- `PENDING` — word-boundary markers (`^n` pauses)
- `NEWLINE` — line break markers (`&`, `#`, `\n`)
- `END` — terminator tokens (`/`, `/%`, `%`, `%%`, `)`)

Relocatable markers use placeholders in the clean text the user edits:
- `*` ↔ color markers `\cX` / `\CX`
- `~` ↔ text effect `~n`
- `@` ↔ `\On`
- `$` ↔ `\In`

`stripFormatting()` converts original → user-editable plain text. `reapplyFormatting()` converts it back, reinserting markers in the correct positions.

**Spell check** — `HunspellChecker` wraps the system `hunspell` CLI. Dictionary lookup order:
1. `hunspellgal/build/gl.aff` (project-local compiled Galician dictionary)
2. System paths: `/usr/share/hunspell/gl_ES`, `/usr/share/hunspell/gl`, etc.

In practice `hunspellgal/` is gitignored and absent, so it falls back to the system dictionary (install `hunspell-gl`).

Spell check runs on a single daemon thread (`spell-check`) with 350 ms debounce to avoid blocking the UI thread. Personal dictionary stored at `lang/amanuensis-personal.dic`.

## Data layout (`lang/`)

```
lang/
  settings.json              # project metadata (lang name, URLs, etc.)
  amanuensis-personal.dic    # user's personal spell-check word list
  chapter1/
    chapter_settings.json    # chapter metadata — NOT opened in editor
    strings.json             # translatable strings — opened in editor
  ...
  fonts/                     # game fonts (not edited)
```

The `lang/` directory must be next to the jar at runtime. `MainView` lists all `.json` files under `lang/` recursively, excluding `*.copy*.json` and `chapter_settings.json`.
