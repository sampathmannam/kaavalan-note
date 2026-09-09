#!/usr/bin/env bash
#
# Cut a signed KaavalanNote release and publish it to GitHub Releases
# for Obtainium.
#
#   ./release.sh 2.2.0
#
# Every gate below exists because a broken release is expensive in a
# specific way: Android refuses an update whose signing key differs
# from the installed one, so a mis-signed release does not "fail" --
# it silently strands every user on their current version until they
# uninstall (losing all local data, which for this app is the whole
# point). Obtainium likewise silently stops offering updates if
# versionCode does not increase.
#
# The gates, in order, all fatal:
#   1. clean working tree, and the tag does not already exist
#   2. signing config actually resolves (never publish an unsigned APK)
#   3. full unit-test suite green
#   4. versionCode strictly greater than the last published release
#   5. built APK verifies as signed
#   6. the signing certificate matches the pinned fingerprint
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

FINGERPRINT_FILE="release/signing-fingerprint.txt"
GRADLE_PROPS="app/build.gradle.kts"
APK_BUILT="app/build/outputs/apk/release/app-universal-release.apk"

: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME

die() { printf '\n\033[31mrelease: %s\033[0m\n\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok() { printf '    \033[32m✓\033[0m %s\n' "$1"; }

[ $# -eq 1 ] || die "usage: ./release.sh <versionName>   e.g. ./release.sh 2.2.0"
VERSION_NAME="$1"
TAG="v${VERSION_NAME}"

# --- locate apksigner (newest build-tools) ---------------------------------
APKSIGNER="$(find "$HOME/Library/Android/sdk/build-tools" -maxdepth 2 -name apksigner 2>/dev/null | sort -V | tail -1)"
[ -n "$APKSIGNER" ] || die "apksigner not found under \$ANDROID_HOME/build-tools"

# --- gate 1: clean tree, tag is new ----------------------------------------
step "Gate 1/6 — working tree and tag"
[ -z "$(git status --porcelain)" ] || die "working tree is dirty. Commit or stash first — a release must be reproducible from the tag."
if git rev-parse "$TAG" >/dev/null 2>&1; then
  die "tag $TAG already exists locally. Releases are immutable; pick a new version."
fi
if gh release view "$TAG" >/dev/null 2>&1; then
  die "release $TAG already published on GitHub. Pick a new version."
fi
ok "tree clean, $TAG is new"

# --- gate 2: signing config resolves ---------------------------------------
step "Gate 2/6 — signing config"
SIGNING_REPORT="$(./gradlew -q :app:signingReport -Pkaavalan.enableReleaseSigning=true 2>/dev/null)" \
  || die "release signing configuration is unavailable or incomplete. Configure the original signing key privately before releasing."
# AGP prints an unconfigured variant as `Config: null` / `Store: null`
# (NOT "none" -- an earlier version of this gate checked for "none" and
# therefore waved an unsigned build straight through, which is the exact
# failure this gate exists to catch). Match the real output, and treat a
# null Store as unsigned regardless of what Config says.
RELEASE_BLOCK="$(grep -A4 -i "Variant: release$" <<<"$SIGNING_REPORT" || true)"
if [ -z "$RELEASE_BLOCK" ] || grep -qiE "Config: *(null|none)|Store: *(null|none)" <<<"$RELEASE_BLOCK"; then
  die "release signing is NOT configured — the build would emit an UNSIGNED APK.
    Set the four KAAVALAN_RELEASE_* values in local.properties (see local.properties.example).
    Never publish an unsigned APK: Android cannot update an app across a signing-identity change."
fi
ok "release keystore resolves"

# --- gate 3: tests --------------------------------------------------------
step "Gate 3/6 — unit tests"
./gradlew --console=plain :app:testDebugUnitTest -Pkaavalan.enableReleaseSigning=false >/dev/null || die "unit tests failed. Not shipping."
ok "unit test suite green"

# --- gate 4: versionCode strictly increases -------------------------------
step "Gate 4/6 — versionCode"
VERSION_CODE="$(grep -E '^\s*versionCode = [0-9]+' "$GRADLE_PROPS" | grep -oE '[0-9]+' | tail -1)"
DECLARED_NAME="$(grep -E '^\s*versionName = ' "$GRADLE_PROPS" | sed -E 's/.*"(.*)".*/\1/')"
[ -n "$VERSION_CODE" ] || die "could not read versionCode from $GRADLE_PROPS"
[ "$DECLARED_NAME" = "$VERSION_NAME" ] || die "versionName in $GRADLE_PROPS is '$DECLARED_NAME' but you asked to release '$VERSION_NAME'. Bump it first."

LAST_TAG="$(gh release list --limit 1 --json tagName --jq '.[0].tagName' 2>/dev/null || true)"
if [ -n "$LAST_TAG" ]; then
  LAST_CODE="$(gh release view "$LAST_TAG" --json body --jq '.body' 2>/dev/null | grep -oE 'versionCode: [0-9]+' | grep -oE '[0-9]+' || true)"
  if [ -n "$LAST_CODE" ]; then
    [ "$VERSION_CODE" -gt "$LAST_CODE" ] || die "versionCode $VERSION_CODE is not greater than the last published release ($LAST_TAG → $LAST_CODE).
    Obtainium will silently refuse to offer this as an update. Bump versionCode in $GRADLE_PROPS."
    ok "versionCode $VERSION_CODE > $LAST_CODE ($LAST_TAG)"
  else
    ok "versionCode $VERSION_CODE (no machine-readable code on $LAST_TAG — first script-made release)"
  fi
else
  ok "versionCode $VERSION_CODE (no previous release)"
fi

# --- build ----------------------------------------------------------------
step "Building signed release (universal)"
./gradlew --console=plain :app:assembleRelease -Pkaavalan.enableReleaseSigning=true >/dev/null || die "assembleRelease failed"
[ -f "$APK_BUILT" ] || die "expected APK not found at $APK_BUILT"
ok "built $(du -h "$APK_BUILT" | cut -f1) universal APK"

# --- gate 5: APK is actually signed ---------------------------------------
step "Gate 5/6 — signature present"
"$APKSIGNER" verify --print-certs "$APK_BUILT" >/tmp/kaavalan-certs.txt 2>/dev/null \
  || die "apksigner could not verify $APK_BUILT — it is unsigned or corrupt. NOT publishing."
ok "APK verifies as signed"

# --- gate 6: signed with the pinned key -----------------------------------
step "Gate 6/6 — signing identity"
FINGERPRINT="$(grep -m1 -i 'SHA-256 digest' /tmp/kaavalan-certs.txt | awk '{print $NF}')"
[ -n "$FINGERPRINT" ] || die "could not read the certificate fingerprint from apksigner output"

mkdir -p "$(dirname "$FINGERPRINT_FILE")"
if [ ! -f "$FINGERPRINT_FILE" ]; then
  printf '%s\n' "$FINGERPRINT" > "$FINGERPRINT_FILE"
  cat <<EOF

  This is the FIRST release. The signing certificate fingerprint has been
  pinned to:

      $FINGERPRINT

  Written to $FINGERPRINT_FILE — commit it. Every future release must match
  it, or this script refuses to publish. This is the fingerprint your users'
  phones will bind to; if you ever lose the keystore behind it, nobody can
  update without uninstalling and losing their data.

EOF
  read -r -p "  Keystore backed up somewhere off this Mac? [yes/no] " CONFIRM
  [ "$CONFIRM" = "yes" ] || die "back the keystore up first, then re-run. This is the one unrecoverable step."
else
  PINNED="$(tr -d '[:space:]' < "$FINGERPRINT_FILE")"
  if [ "$(tr -d '[:space:]' <<<"$FINGERPRINT")" != "$PINNED" ]; then
    die "SIGNING KEY MISMATCH — refusing to publish.
      built with: $FINGERPRINT
      expected:   $PINNED
    Publishing this would strand every existing user: Android rejects an update
    signed by a different key, so they would have to uninstall (losing all data)
    to move forward. Find the original keystore."
  fi
  ok "signed with the pinned release key"
fi

# --- publish --------------------------------------------------------------
step "Publishing $TAG"
ASSET="kaavalan-note-${VERSION_NAME}.apk"
cp "$APK_BUILT" "/tmp/$ASSET"

git tag -a "$TAG" -m "KaavalanNote $VERSION_NAME"
git push origin "$TAG"

gh release create "$TAG" "/tmp/$ASSET" \
  --title "KaavalanNote $VERSION_NAME" \
  --notes "$(cat <<EOF
Install or update via Obtainium.

versionCode: $VERSION_CODE
versionName: $VERSION_NAME

Signed with the pinned release key (SHA-256 \`$FINGERPRINT\`).
Your existing data is preserved across this update.
EOF
)"

rm -f "/tmp/$ASSET"
printf '\n\033[32mreleased %s\033[0m — https://github.com/sampathmannam/kaavalan-note/releases/tag/%s\n\n' "$TAG" "$TAG"
