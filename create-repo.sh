#!/usr/bin/env bash
# Create a NEW PRIVATE GitHub repo from the unzipped azahar-aot project, with
#   - the project pushed (build patches included),
#   - an Android signing key generated and stored as Actions secrets,
#   - an empty, non-draft release that will hold your game dump.
#
#   cd azahar-aot-main
#   ./create-repo.sh [REPO_NAME] [game.3ds [update.cia]]
#
# Needs: git, gh (already logged in), keytool (any JDK).  Termux: pkg install git gh openjdk-17
# Env:   TAG=mhxx-data        release that holds the dump (the workflow's release_tag input)
#        KEYDIR=~/azahar-aot-signing   where the keystore + passwords are kept (BACK THIS UP)
#        AZAHAR_TOKEN=...     only if your AzaharPlus fork is private (becomes a secret)
#        RUN=1                also start the library-aot / android-aot workflows
#        DRY_RUN=1            do everything locally, touch nothing on GitHub
set -euo pipefail

NAME="${1:-azahar-aot-mhxx}"
GAME="${2:-}"
UPDATE="${3:-}"
TAG="${TAG:-mhxx-data}"
KEYDIR="${KEYDIR:-$HOME/azahar-aot-signing}"
ALIAS="${ALIAS:-azahar-aot}"
DRY_RUN="${DRY_RUN:-0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { echo "error: $*" >&2; exit 1; }
gh_() { if [ "$DRY_RUN" = 1 ]; then echo "[dry run] gh $*"; cat >/dev/null; else gh "$@"; fi; }

for c in git keytool base64; do command -v "$c" >/dev/null || die "$c is not installed"; done
[ -f .github/workflows/library-aot.yml ] || die "run this inside the unzipped azahar-aot-main folder"
if [ -n "$GAME" ]; then
  [ -f "$GAME" ] || die "no such file: $GAME"
  [ -z "$UPDATE" ] || [ -f "$UPDATE" ] || die "no such file: $UPDATE"
  [ -x "$HERE/upload-mhxx.sh" ] || [ -f "$HERE/upload-mhxx.sh" ] || die "upload-mhxx.sh must sit next to this script"
fi

if [ "$DRY_RUN" = 1 ]; then
  OWNER="dry-run-user"; USER_ID=0
else
  command -v gh >/dev/null || die "gh is not installed"
  # (not "gh auth status": it fails when any old saved login is stale, even if the active one works)
  OWNER="$(gh api user --jq .login 2>/dev/null)" || die "gh can't reach GitHub with your login: run 'gh auth login' (token needs the repo and workflow scopes)"
  USER_ID="$(gh api user --jq .id)"
  gh repo view "$OWNER/$NAME" >/dev/null 2>&1 && die "$OWNER/$NAME already exists (pick another name)"
fi
REPO="$OWNER/$NAME"
echo "== repo: $REPO (private), release: $TAG"

# ---------------------------------------------------------------- .gitignore
# The shipped .gitignore has "*.patch", which would silently leave out patches/*.patch and
# mh4u/port/azahar/azahar-android.patch: android-aot applies them and would fail. azahar/ and
# reference/ are not needed (CI checks out your fork itself, README step 0).
sed -i.orig '/^\*\.patch$/d' .gitignore && rm -f .gitignore.orig
for d in /azahar/ /reference/; do grep -qx "$d" .gitignore || echo "$d" >> .gitignore; done

# ---------------------------------------------------------------- commit
[ -d .git ] || git init -q -b main
if [ -z "$(git config user.name || true)" ]; then git config user.name "$OWNER"; fi
if [ -z "$(git config user.email || true)" ]; then git config user.email "${USER_ID}+${OWNER}@users.noreply.github.com"; fi
git add -A
n="$(git ls-files 'patches/*.patch' 'mh4u/port/azahar/azahar-android.patch' | wc -l | tr -d ' ')"
[ "$n" -ge 7 ] || die "build patches are missing from the commit ($n found, expected 7)"
bad="$(git ls-files | grep -Ei '\.(3ds|cia|cxi|cci|so|jks)$' || true)"
[ -z "$bad" ] || die "game data or keys would be committed: $bad"
git diff --cached --quiet || git commit -q -m "azahar-aot: initial import"
echo "committed $(git ls-files | wc -l | tr -d ' ') files"

# ---------------------------------------------------------------- signing key
umask 077
mkdir -p "$KEYDIR"; chmod 700 "$KEYDIR"
if [ -f "$KEYDIR/release.jks" ] && [ -f "$KEYDIR/secrets.env" ]; then
  echo "reusing the signing key in $KEYDIR (same key = new APKs update old ones)"
  # shellcheck disable=SC1091
  . "$KEYDIR/secrets.env"
else
  [ ! -e "$KEYDIR/release.jks" ] || die "$KEYDIR/release.jks exists but secrets.env does not: move it away or restore secrets.env"
  KS_PW="$(head -c 600 /dev/urandom | tr -dc 'A-Za-z0-9' | head -c 32 || true)"
  [ "${#KS_PW}" -eq 32 ] || die "could not make a password"
  KS_ALIAS="$ALIAS"
  export KS_PW
  keytool -genkeypair -keystore "$KEYDIR/release.jks" -storetype PKCS12 \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass:env KS_PW -keypass:env KS_PW \
    -dname "CN=Azahar AOT, O=$OWNER" >/dev/null
  printf 'KS_PW=%s\nKS_ALIAS=%s\n' "$KS_PW" "$KS_ALIAS" > "$KEYDIR/secrets.env"
  echo "new signing key written to $KEYDIR"
fi

# ---------------------------------------------------------------- create + push
if [ "$DRY_RUN" = 1 ]; then
  echo "[dry run] gh repo create $REPO --private --source=. --remote=origin --push"
else
  gh repo create "$REPO" --private --source=. --remote=origin --push
fi

# ---------------------------------------------------------------- secrets
# names are the ones android-aot.yml reads. A PKCS12 key password must equal the store password.
base64 < "$KEYDIR/release.jks" | tr -d '\n' | gh_ secret set ANDROID_KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$KS_PW"    | gh_ secret set ANDROID_KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$KS_ALIAS" | gh_ secret set ANDROID_KEY_ALIAS        -R "$REPO"
printf '%s' "$KS_PW"    | gh_ secret set ANDROID_KEY_PASSWORD      -R "$REPO"
if [ -n "${AZAHAR_TOKEN:-}" ]; then
  printf '%s' "$AZAHAR_TOKEN" | gh_ secret set AZAHAR_TOKEN -R "$REPO"
fi

# ---------------------------------------------------------------- release for the dump
gh_ release create "$TAG" -R "$REPO" --title "$TAG" --notes "game data (private repo only)" --latest=false </dev/null

if [ -n "$GAME" ]; then
  TAG="$TAG" bash "$HERE/upload-mhxx.sh" "$REPO" "$GAME" ${UPDATE:+"$UPDATE"}
fi

# ---------------------------------------------------------------- workflows
if [ "${RUN:-0}" = 1 ] && [ "$DRY_RUN" != 1 ]; then
  sleep 5
  gh workflow run android-aot.yml -R "$REPO"
  [ -z "$GAME" ] || gh workflow run library-aot.yml -R "$REPO" -f release_tag="$TAG"
fi

cat <<EOF

Done: https://github.com/$REPO
Keep $KEYDIR safe (release.jks + secrets.env): lose it and new APKs can't update installed ones.

Next:
  # upload the dump, if you didn't pass it above
  TAG=$TAG ./upload-mhxx.sh $REPO MHXX.3ds update.cia
  # library (.so) for the game, then the signed APK
  gh workflow run library-aot.yml -R $REPO -f release_tag=$TAG
  gh workflow run android-aot.yml -R $REPO
  gh run list -R $REPO
EOF
