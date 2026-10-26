#!/usr/bin/env bash
# Point the app in this repo at MHXX instead of MH4U (the few strings that matter), commit, push.
#
#   cd azahar-aot-mhxx            # your repo folder
#   ./retarget-mhxx.sh MHXX.3ds   # reads the title ID from your decrypted .3ds
#   ./retarget-mhxx.sh 00040000XXXXXXXX    # or give the 16-hex-digit title ID yourself
#
# NO_PUSH=1 edits only. Folders, package names and MH4U_* env names are left alone on purpose:
# the workflows and the app's native code refer to them by those names.
set -euo pipefail
die() { echo "error: $*" >&2; exit 1; }

ARG="${1:?usage: $0 game.3ds | TITLEID}"
if [ -f "$ARG" ]; then
  # NCSD header: partition 0's offset (media units of 0x200) at 0x120; the NCCH header there
  # holds the program ID (8 bytes, little endian) at +0x118
  [ "$(od -An -c -j $((0x100)) -N4 "$ARG" | tr -d ' ')" = "NCSD" ] || die "$ARG is not a .3ds (NCSD) image: pass the title ID instead"
  units="$(od -An -tu4 -j $((0x120)) -N4 "$ARG" | tr -d ' ')"
  bytes="$(od -An -tx1 -j $(( units * 512 + 0x118 )) -N8 "$ARG" | tr -d ' \n')"
  ID="$(echo "$bytes" | fold -w2 | tac | tr -d '\n' | tr a-f A-F)"
else
  ID="$(echo "$ARG" | tr a-f A-F)"
fi
echo "$ID" | grep -Eq '^[0-9A-F]{16}$' || die "not a 16-digit title ID: $ID"
echo "title ID: $ID"

APP=mhxx/port/android/app
KT="$APP/src/main/java/org/mh4u/port/MainActivity.kt"
for f in "$KT" "$APP/build.gradle.kts" "$APP/src/main/AndroidManifest.xml"; do [ -f "$f" ] || die "run this in the repo root ($f missing)"; done

# the code library the Setup screen looks for / imports: <TITLEID>.so
sed -i.orig "s/0004000000126300\.so/$ID.so/g" "$KT"
# visible names
sed -i.orig \
  -e 's/Text("MH4U", color/Text("MHXX", color/' \
  -e 's/Monster Hunter 4 Ultimate · port/Monster Hunter XX · port/' \
  -e 's/Decrypted Monster Hunter 4 Ultimate (/Decrypted Monster Hunter XX (/' \
  -e 's/3 · Update (v1\.3\.0)/3 · Update/' \
  -e 's/The port targets the USA 1\.3\.0 update\. Install its \.cia once; skip this step if it is already installed\./Install the game'"'"'s update .cia once (the same one the library was built with); skip this step if it is already installed./' \
  "$KT"
# a different app: own id, so it installs next to an MH4U build signed with another key
# (the Java package and JNI names stay org.mh4u.port; only the installed app's id changes)
sed -i.orig 's/applicationId = "org\.mh4u\.port"/applicationId = "org.mh4u.port.mhxx"/' "$APP/build.gradle.kts"
sed -i.orig 's/android:label="MH4U"/android:label="MHXX"/' "$APP/src/main/AndroidManifest.xml"
find "$APP" -name '*.orig' -delete

grep -q "$ID.so" "$KT" || die "edit failed"
grep -q 'org.mh4u.port.mhxx' "$APP/build.gradle.kts" || die "applicationId edit failed"
echo "edited:"; git diff --stat 2>/dev/null || true

if [ "${NO_PUSH:-0}" != 1 ] && [ -d .git ]; then
  git add -A mhxx/port/android
  git commit -q -m "app: target MHXX ($ID)"
  git push -q && echo "pushed: now run the android-aot workflow"
fi
