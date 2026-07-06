#!/usr/bin/env python3
"""
Propagate edits across repeated lines in chapter strings.json files.

Repeated lines = entries with identical *content* (>= MIN_LEN chars), even if
their JSON keys differ across chapters. A line edited in any chapter is pushed
to every other occurrence of that same line in all chapters.

Identity is fixed at init time (groups.json). After that, lines are tracked by
their (chapter, key) membership, NOT by live content -- so edits don't break the
link. groups.json stores each group's last agreed value to detect what changed.

Usage:
    python3 propagate.py init        # build groups.json from current files (run ONCE)
    python3 propagate.py             # detect edits, propagate, rewrite files
    python3 propagate.py status      # show groups + pending edits, write nothing
    python3 propagate.py init --force  # rebuild groups.json (discards tracking)
"""
import json
import sys
import os

CHAPTERS = [1, 2, 3, 4]
MIN_LEN = 10                      # ignore lines shorter than this (per your rule)
GROUPS_FILE = "groups.json"
DIR = os.path.dirname(os.path.abspath(__file__))


def path(c):
    return os.path.join(DIR, f"chapter{c}", "strings.json")


def load_chapter(c):
    with open(path(c), encoding="utf-8") as f:
        return json.load(f)


def save_chapter(c, d):
    with open(path(c), "w", encoding="utf-8") as f:
        f.write(json.dumps(d, ensure_ascii=False, indent=4) + "\n")


def gpath():
    return os.path.join(DIR, GROUPS_FILE)


# ---------------------------------------------------------------- init
def build_groups():
    """Group all occurrences (any chapter, incl. same-chapter dupes) of identical
    content >= MIN_LEN. Singletons are dropped (nothing to propagate)."""
    by_content = {}   # content -> list of [chapter, key]
    for c in CHAPTERS:
        for k, v in load_chapter(c).items():
            if not isinstance(v, str) or len(v) < MIN_LEN:
                continue
            by_content.setdefault(v, []).append([c, k])

    groups = {}
    gid = 0
    for content, members in by_content.items():
        if len(members) < 2:
            continue                      # unique line, nothing to cascade
        groups[str(gid)] = {"value": content, "members": members}
        gid += 1
    return groups


def cmd_init(force=False):
    if os.path.exists(gpath()) and not force:
        sys.exit(f"{GROUPS_FILE} already exists. Use 'init --force' to rebuild.")
    groups = build_groups()
    with open(gpath(), "w", encoding="utf-8") as f:
        json.dump(groups, f, ensure_ascii=False, indent=2)
    occ = sum(len(g["members"]) for g in groups.values())
    print(f"Built {GROUPS_FILE}: {len(groups)} repeated-line groups, "
          f"{occ} total occurrences (>= {MIN_LEN} chars).")


# ---------------------------------------------------------------- propagate
def load_groups():
    if not os.path.exists(gpath()):
        sys.exit(f"No {GROUPS_FILE}. Run: python3 propagate.py init")
    with open(gpath(), encoding="utf-8") as f:
        return json.load(f)


def detect(groups, chapters):
    """For each group, find members whose current value != stored canonical.
    Returns (updates, conflicts).
      updates  : list of (gid, new_value)
      conflicts: list of (gid, set_of_distinct_new_values)
    """
    updates, conflicts = [], []
    for gid, g in groups.items():
        old = g["value"]
        edited = set()
        for c, k in g["members"]:
            cur = chapters[c].get(k)
            if cur is not None and cur != old:
                edited.add(cur)
        if not edited:
            continue
        if len(edited) == 1:
            updates.append((gid, next(iter(edited))))
        else:
            conflicts.append((gid, edited))
    return updates, conflicts


def cmd_run(dry=False):
    groups = load_groups()
    chapters = {c: load_chapter(c) for c in CHAPTERS}
    updates, conflicts = detect(groups, chapters)

    if conflicts:
        print(f"!! {len(conflicts)} CONFLICT(S) -- same line edited differently, "
              f"skipped (resolve by hand):")
        for gid, vals in conflicts:
            old = groups[gid]["value"]
            print(f"  group {gid}: was {old!r}")
            for v in vals:
                holders = [f"ch{c}:{k}" for c, k in groups[gid]["members"]
                           if chapters[c].get(k) == v]
                print(f"      -> {v!r}  ({', '.join(holders)})")

    if not updates:
        print("No edits to propagate." if not conflicts else "No clean edits.")
        return

    changed_files = set()
    total_writes = 0
    print(f"\n{'[dry-run] would propagate' if dry else 'Propagating'} "
          f"{len(updates)} edited line(s):")
    for gid, newv in updates:
        g = groups[gid]
        oldv = g["value"]
        n = 0
        for c, k in g["members"]:
            if chapters[c].get(k) != newv:
                chapters[c][k] = newv
                changed_files.add(c)
                n += 1
                total_writes += 1
        g["value"] = newv
        ch_list = sorted({c for c, _ in g["members"]})
        print(f"  group {gid}: {oldv!r}\n           -> {newv!r}  "
              f"({n} occurrence(s) synced across ch{ch_list})")

    if dry:
        print("\n[dry-run] no files written.")
        return

    for c in sorted(changed_files):
        save_chapter(c, chapters[c])
    with open(gpath(), "w", encoding="utf-8") as f:
        json.dump(groups, f, ensure_ascii=False, indent=2)
    print(f"\nWrote {total_writes} value(s) across {len(changed_files)} "
          f"chapter file(s); updated {GROUPS_FILE}.")


# ---------------------------------------------------------------- main
def main():
    args = sys.argv[1:]
    if args and args[0] == "init":
        cmd_init(force="--force" in args)
    elif args and args[0] == "status":
        cmd_run(dry=True)
    elif not args:
        cmd_run(dry=False)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
