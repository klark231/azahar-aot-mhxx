#!/usr/bin/env bash
# Rename the repo's top-level mh4u/ folder to mhxx/, fix every path that points at it, drop the
# MH4U-only workflow, put an MHXX quick start at the top of the README, commit and push.
#
#   cd azahar-aot-mhxx
#   ./rename-to-mhxx.sh
#
# NO_PUSH=1: change the files, commit nothing.
# NOT renamed on purpose: the Java package org.mh4u.port, JNI names, MH4U* classes, MH4U_* env
# names, overrides/mh4u/ (code refers to these by name; renaming them breaks the build).
set -euo pipefail
die() { echo "error: $*" >&2; exit 1; }

[ -d mh4u/port/android ] || die "no mh4u/ folder here (already renamed, or run this in the repo root)"
[ ! -e mhxx ] || die "mhxx/ already exists"
git rev-parse --git-dir >/dev/null 2>&1 || die "not a git repo"

git mv mh4u mhxx

# Files that name the folder as a path. The pattern only matches mh4u/ when what comes before it
# is not part of a longer path (org/mh4u/port, overrides/mh4u/, ...).
files=$(grep -rlI -e 'mh4u' .github/workflows/android-aot.yml README.md docs tools 2.py \
        retarget-mhxx.sh create-repo.sh upload-mhxx.sh 2>/dev/null || true)
for f in $files; do
  perl -0pi -e '
    s{repo/mh4u(?![\w-])}{repo/mhxx}g;
    s{(?<![\w./-])mh4u/(?=port|config|tools|docs|server|src|include|\.github|requirements|tests|`)}{mhxx/}g;
    s{(?<=\.\./)mh4u/}{mhxx/}g;
  ' "$f"
done

# the MH4U-only recompiler workflow: useless for MHXX (it is still in git history)
git rm -q -f .github/workflows/library-mh4u.yml 2>/dev/null || true

# README: MHXX quick start on top, the old text stays below
if ! grep -q '^## MHXX quick start' README.md; then
  {
    cat <<'EOF'
# azahar-aot (MHXX)

## MHXX quick start

1. Release `game-data` of this private repo holds your decrypted dump: `game.3ds.part-00`,
   `game.3ds.part-01`, ... (the game, split under 2 GiB) and `update.cia`.
2. Actions → **library-aot** → Run (release_tag `game-data`). Artifact `aot-code-library` has
   `<TITLEID>.so`.
3. Actions → **android-aot** → Run. Artifact `azahar-aot-android` is the signed APK
   (the `mhxx/` folder is the Android app; its package names still say mh4u inside).
4. Put the `.so` in the Azahar user folder under `aot/`, or pick it in the app's Setup screen.

---

EOF
    cat README.md
  } > README.md.new && mv README.md.new README.md
fi

# check: every repo/mhxx/... path the workflows use exists, and nothing still points at mh4u/
bad=0
for p in $(grep -rhoE 'repo/mhxx/[A-Za-z0-9_./-]+' .github/workflows | sed 's#/\*.*##; s#^repo/##' | sort -u); do
  case "$p" in *build/*|*data*|*'*'*) continue;; esac
  [ -e "$p" ] || { echo "missing: $p"; bad=1; }
done
left="$(grep -rnE '(repo/|\.\./repo/)mh4u|(^|[^[:alnum:]_./-])mh4u/(port|config|tools|requirements)' .github tools docs README.md 2.py 2>/dev/null || true)"
[ -z "$left" ] || { echo "still pointing at mh4u/:"; echo "$left"; bad=1; }
[ "$bad" = 0 ] || die "check failed, nothing committed (undo with: git reset --hard)"

git add -A
git status --short | head -20
if [ "${NO_PUSH:-0}" != 1 ]; then
  git commit -q -m "rename mh4u/ to mhxx/, MHXX README, drop the MH4U-only workflow"
  git push -q && echo "pushed"
fi
