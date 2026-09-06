# Releasing KaavalanNote (Obtainium channel)

The app is distributed by sideload: signed universal APKs attached to
GitHub Releases, which [Obtainium](https://github.com/ImranR98/Obtainium)
watches and installs. There is no Play Store listing.

Everything below exists to protect one property: **an update must never
cost a user their data.** Android enforces that an update is signed by the
same key as the installed app; if the key changes, the only way forward is
uninstall + reinstall, which wipes the local encrypted database. That is
unrecoverable for this app — the data lives nowhere else by design.

---

## One-time setup

### 1. Create the release keystore

Run this yourself — it prompts for the passwords interactively, so they
never end up in a shell history file or a chat log:

```bash
mkdir -p ~/Documents/kaavalan-signing-BACKUP
keytool -genkeypair -v \
  -keystore ~/Documents/kaavalan-signing-BACKUP/kaavalan-note-release.jks \
  -alias kaavalan -keyalg RSA -keysize 4096 -validity 10000
```

**Back the `.jks` up somewhere off this Mac immediately** — a second
physical device, or an encrypted archive in cloud storage. Losing it
means no user can ever update again without wiping their data.

The keystore is gitignored (`*.jks`), and the previous one was purged
from git history in v2.1.0. Never commit it.

### 2. Point the build at it

Add to `local.properties` (gitignored — see `local.properties.example`):

```properties
KAAVALAN_RELEASE_STORE_FILE=~/Documents/kaavalan-signing-BACKUP/kaavalan-note-release.jks
KAAVALAN_RELEASE_STORE_PASSWORD=...
KAAVALAN_RELEASE_KEY_ALIAS=kaavalan
KAAVALAN_RELEASE_KEY_PASSWORD=...
```

Verify it resolved:

```bash
./gradlew :app:signingReport | grep -A3 "Variant: release"
```

`Store: null` means it did not resolve and `release.sh` will refuse to
publish.

---

## Cutting a release

1. Bump **both** values in `app/build.gradle.kts`:
   - `versionCode` — must strictly increase, or Obtainium silently stops
     offering the update
   - `versionName` — the human version, must match the argument you pass
2. Commit everything (the script refuses a dirty tree).
3. Run:

```bash
./release.sh 2.2.0
```

The script refuses to publish unless all six gates pass:

| Gate | Refuses when | Why it matters |
|---|---|---|
| 1 | tree dirty, or tag exists | a release must be reproducible from its tag |
| 2 | signing config unresolved | prevents publishing an **unsigned** APK |
| 3 | unit tests red | no shipping known-broken code |
| 4 | `versionCode` not increased | Obtainium would silently never offer it |
| 5 | APK fails signature verification | catches a corrupt or unsigned build |
| 6 | certificate ≠ pinned fingerprint | **catches signing with the wrong key** |

Gate 6 is the important one. On the first release the script pins the
signing certificate's SHA-256 into `release/signing-fingerprint.txt`
(safe to commit — it is public information, present in every APK). Every
later release must match it. If you ever build with a different or
regenerated keystore, the script stops rather than shipping an APK that
would strand your users.

---

## Obtainium setup (you and anyone you share with)

Add the app in Obtainium with:

- **URL:** `https://github.com/sampathmannam/kaavalan-note`
- **APK filter (regex):** `kaavalan-note-.*\.apk`

Releases attach exactly one universal APK named
`kaavalan-note-<version>.apk`, so the filter matches one file on every
device — no per-ABI guessing, and no way to hand someone the wrong build.

---

## Data continuity — read before switching phones or builds

Android identifies an app by its `applicationId`. These are **three
different apps** and none of them shares data with the others:

| Build | applicationId |
|---|---|
| Old GitHub releases (≤ v1.9.8) | `com.baton.app` |
| Local debug builds | `com.kaavalan.note.debug` |
| Obtainium releases (v2.x+) | `com.kaavalan.note` |

Consequences:

- Notes made while testing a **debug** build do not appear in the
  release build. Export first if you want to keep them.
- Anyone still running a `com.baton.app` build will never be offered a
  v2.x update — it is a different app to Android. They must export,
  install the new one, and import.

**Migrating data across any of those boundaries:** Settings → Export
(encrypted vault, or plain CSV/JSON), then Settings → Import on the new
install.

Within the `com.kaavalan.note` line, upgrades preserve data normally:
the Room schema is at version 16 with a complete migration chain
(2 → 16) and **no destructive fallback**, so no upgrade path drops
tables.

---

## Running the emulator for on-device checks

Boot the test AVD **headless with software rendering**:

```bash
$ANDROID_HOME/emulator/emulator -avd kaavalan-test \
  -no-snapshot -no-window -gpu swiftshader_indirect -no-audio \
  -netdelay none -netspeed full &
```

Booting it with the default (hardware) GPU on this machine fails during
display-surface creation and the emulator exits a few seconds later:

```
ERROR | Failed to make display surface context current: 12299
ERROR | Failed to bind to post worker context.
```

This looks like flakiness — it repeatedly presented as "the emulator died
again" and was initially misread as CPU load, since it happened while the
machine was busy. It is not load: the same AVD boots reliably at any load
with `-no-window -gpu swiftshader_indirect`, and other AVDs on the same
machine boot fine with hardware GPU. Nothing here needs a display anyway;
the QA driver works entirely over `adb`.

If a boot still fails, clear stale lock files first:

```bash
rm -f ~/.android/avd/kaavalan-test.avd/multiinstance.lock \
      ~/.android/avd/kaavalan-test.avd/*.tmp-*
```

## If you lose the keystore

There is no recovery. The honest options are both bad:

1. Publish under a new applicationId and have everyone export → install
   → import. Their old install stays on the phone until they remove it.
2. Keep shipping the old version forever.

This is why gate 6 exists, and why the backup step is not optional.
