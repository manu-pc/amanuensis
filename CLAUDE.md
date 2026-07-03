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
```

No tests exist yet.

## Architecture

JavaFX desktop app for editing Undertale/Deltarune localization JSON files (Galician translation project). Java 21, Maven shade plugin produces a self-contained fat-jar with bundled JavaFX natives per platform.

**Entry point split** — `com.Main` is the real `main()` but does NOT extend `Application`; it delegates to `GuiApp.launchApp()`. This is intentional: fat-jars need a non-Application main to avoid "JavaFX runtime components are missing".

**Two-screen flow**:
1. `MainView` — startup screen; lets user pick a `.json` file or double-click from a `lang/` directory listing. Excludes `.copy*.json` and `chapter_settings.json` files.
2. `LocalView` — main editor; three read-only panes (literal with markers shown as `⏎`, clean plain text) plus an editable `InlineCssTextArea` (RichTextFX). Live preview shows `reapplyFormatting` output as the user types.

**Working copy safety** — `FileCopyManager` creates `<file>.copy.json` on open (timestamped if one already exists). All saves go to the copy. "gardar cambios" (`saveToOriginal`) flushes copy → original.

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
