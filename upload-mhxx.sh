#!/usr/bin/env bash
# Split a decrypted MHXX dump into <2 GiB parts and upload them (plus update.cia) to a release of
# your PRIVATE repo, where the library-aot workflow downloads and joins them.
#
#   ./upload-mhxx.sh OWNER/REPO /path/to/MHXX.3ds [/path/to/update.cia]
#
# Needs: gh (logged in: `gh auth login`), split, sha256sum (macOS: brew install coreutils gnu-sed).
# Env:   TAG=mhxx-data   PART=1900M   DRY_RUN=1 (split + verify only, no upload)
set -euo pipefail

REPO="${1:?usage: $0 OWNER/REPO game.3ds [update.cia]}"
GAME="${2:?usage: $0 OWNER/REPO game.3ds [update.cia]}"
UPDATE="${3:-}"
TAG="${TAG:-mhxx-data}"      # NOT game-data: an old MH4U update.cia there would be picked up too
PART="${PART:-1900M}"        # GitHub release assets must be < 2 GiB
LIMIT=$((2000 * 1024 * 1024))

[ -f "$GAME" ] || { echo "no such file: $GAME"; exit 1; }
ext="${GAME##*.}"
case "$ext" in 3ds|cia|cxi) ;; *) echo "game must be .3ds, .cia or .cxi"; exit 1 ;; esac
base="game.$ext"             # the names library-aot looks for

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

size() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }

# split FILE NAME: NAME.part-00, -01, ... (two digits, so cat's glob order is the right order);
# a file under the limit is linked as plain NAME
prepare() {
  local file="$1" name="$2"
  if [ "$(size "$file")" -lt "$LIMIT" ]; then
    ln -s "$(cd "$(dirname "$file")" && pwd)/$(basename "$file")" "$tmp/$name"
  else
    split -d -a 2 -b "$PART" "$file" "$tmp/$name.part-"
  fi
  # check that what the workflow will do (cat the parts) gives back the original
  local want got
  want="$(sha256sum "$file" | cut -d' ' -f1)"
  if [ -e "$tmp/$name" ]; then
    got="$(sha256sum "$tmp/$name" | cut -d' ' -f1)"
  else
    got="$(cat "$tmp/$name".part-* | sha256sum | cut -d' ' -f1)"
  fi
  [ "$want" = "$got" ] || { echo "join check FAILED for $name"; exit 1; }
  echo "ok  $name  sha256 $want"
}

prepare "$GAME" "$base"
if [ -n "$UPDATE" ]; then
  [ -f "$UPDATE" ] || { echo "no such file: $UPDATE"; exit 1; }
  prepare "$UPDATE" "update.cia"
fi
ls -lL "$tmp"

if [ "${DRY_RUN:-0}" = "1" ]; then echo "dry run: nothing uploaded"; exit 0; fi

vis="$(gh api "repos/$REPO" --jq .visibility)"
[ "$vis" = "private" ] || { echo "$REPO is $vis: refusing to upload game data"; exit 1; }

gh release view "$TAG" -R "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" -R "$REPO" --title "$TAG" --notes "game data (private)" --latest=false

# old game.* / update.* assets (a previous dump, a different part count) would be joined into the
# new file, so remove them first
gh release view "$TAG" -R "$REPO" --json assets --jq '.assets[].name' | \
  grep -E '^(game|update)\.' | while read -r old; do
    echo "removing old asset $old"
    gh release delete-asset "$TAG" "$old" -R "$REPO" -y
  done

for f in "$tmp"/*; do
  for try in 1 2 3; do
    echo "uploading $(basename "$f") (try $try)"
    gh release upload "$TAG" "$f" -R "$REPO" --clobber && break
    [ "$try" = 3 ] && { echo "upload failed: $f"; exit 1; }
    sleep 5
  done
done

echo
echo "Done. Now run the 'library-aot' workflow with release_tag = $TAG (with_update ticked)."
