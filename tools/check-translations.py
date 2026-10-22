#!/usr/bin/env python3
"""Report pt-BR translations that have fallen behind their English source.

Each translated file carries a provenance marker near the top:

    <!-- translated-from: docs/01-dockerfile-lfc.md@<commit-sha> -->

This script compares that sha against the last commit that touched the
English file. If they differ, the English side moved and the translation
needs a look.

    python3 tools/check-translations.py            # report, exit 1 if stale
    python3 tools/check-translations.py --update    # stamp current shas

--update is what you run *after* reviewing and updating a translation (or
once, right after the first commit, to initialize the markers).
"""
import argparse
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# pt-BR file -> English source of truth
PAIRS = {
    "README.pt-BR.md": "README.md",
    "CHANGELOG.pt-BR.md": "CHANGELOG.md",
    "docs/pt-BR/README.md": "docs/README.md",
    "docs/pt-BR/01-dockerfile-lfc.md": "docs/01-dockerfile-lfc.md",
    "docs/pt-BR/02-petalinux-vivado.md": "docs/02-petalinux-vivado.md",
    "docs/pt-BR/03-backlog-generalization.md": "docs/03-backlog-generalization.md",
    "patches/README.pt-BR.md": "patches/README.md",
    "examples/README.pt-BR.md": "examples/README.md",
}

MARKER = re.compile(r"<!--\s*translated-from:\s*(?P<src>[^@\s]+)@(?P<sha>\S+)\s*-->")


def git(*args):
    return subprocess.run(
        ["git", "-C", REPO] + list(args),
        capture_output=True, text=True, check=False,
    ).stdout.strip()


def last_commit(path):
    return git("log", "-1", "--format=%H", "--", path)


def short(sha):
    return sha[:9] if sha and sha != "unknown" else sha


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--update", action="store_true",
                    help="stamp each translation with its source's current sha")
    args = ap.parse_args()

    if not git("rev-parse", "--git-dir"):
        print("not a git repository - nothing to compare against", file=sys.stderr)
        return 2

    stale, missing, ok = [], [], []

    for pt, en in sorted(PAIRS.items()):
        pt_abs, en_abs = os.path.join(REPO, pt), os.path.join(REPO, en)
        if not os.path.exists(pt_abs):
            missing.append("%s (translation absent)" % pt)
            continue
        if not os.path.exists(en_abs):
            missing.append("%s (English source %s absent)" % (pt, en))
            continue

        text = open(pt_abs, encoding="utf-8").read()
        m = MARKER.search(text)
        current = last_commit(en)

        if args.update:
            new_marker = "<!-- translated-from: %s@%s -->" % (en, current or "unknown")
            text = MARKER.sub(new_marker, text, count=1) if m else text
            open(pt_abs, "w", encoding="utf-8").write(text)
            print("stamped %-42s -> %s" % (pt, short(current)))
            continue

        if not m:
            missing.append("%s (no translated-from marker)" % pt)
        elif m.group("src") != en:
            missing.append("%s (marker points at %s, expected %s)"
                           % (pt, m.group("src"), en))
        elif m.group("sha") != current:
            commits = git("log", "--oneline", "%s..HEAD" % m.group("sha"), "--", en) \
                if m.group("sha") != "unknown" else ""
            stale.append((pt, en, m.group("sha"), current, commits))
        else:
            ok.append(pt)

    if args.update:
        return 0

    for pt in ok:
        print("up to date  %s" % pt)
    for pt, en, was, now, commits in stale:
        print("\nSTALE       %s" % pt)
        print("            %s moved %s -> %s" % (en, short(was), short(now)))
        for line in commits.splitlines():
            print("              %s" % line)
    for msg in missing:
        print("\nATTENTION   %s" % msg)

    if stale or missing:
        print("\n%d stale, %d needing attention, %d up to date."
              % (len(stale), len(missing), len(ok)))
        print("After updating a translation, run: "
              "python3 tools/check-translations.py --update")
        return 1

    print("\nAll %d translations are current." % len(ok))
    return 0


if __name__ == "__main__":
    sys.exit(main())
