# Handoff — moving KaavalanNote to another machine

Written 2026-09-07. Branch: `harden/production-readiness`, at `24622dd`,
pushed. Everything below is either already on GitHub or listed here as
something you must carry across by hand.

## What is done

App is at **v2.2.0 / versionCode 47**, `com.kaavalan.note`, 663 unit
tests green, lint clean (0 errors), release APK builds signed and has
been run on-device with no crashes.

- 21 defects found by an adversarial on-device audit, each fixed with a
  regression test.
- Dispatch has a live entry point for the first time: typing an audience
  `@mention` in the note bar (`@si`, `@station:Subedari`, `@all`) offers
  to send. A plain `@ramesh` stays an ordinary note. Verified on the
  R8-minified release build.
- Dispatch no longer auto-marks an instruction done on send.
- Recovery-phrase screen warns before replacing an existing phrase.
- Google Drive backup/restore actually work (both were dead no-ops).
- Roster is observed reactively, and a 0-recipient dispatch no longer
  renders as success.
- `release.sh` + `docs/RELEASING.md`: signed-release pipeline with six
  hard gates, aimed at Obtainium distribution.

## What you must carry across by hand

Two files, both deliberately **not** in git. Move them over a channel you
trust (encrypted drive, password manager's file vault) — not email, not
Slack, not a git remote.

| File | Where it is now | Why it matters |
|---|---|---|
| `~/Documents/kaavalan-signing-BACKUP/kaavalan-note-release.jks` | this Mac only | **The** release signing key. Lose it and no user can ever update without uninstalling and losing their data. |
| `<repo>/local.properties` | this Mac only | Holds `sdk.dir` plus the four `KAAVALAN_RELEASE_*` values, including the generated keystore password. |

The keystore password was generated randomly and written only into
`local.properties`. It exists nowhere else — not in any chat log, not in
this repo. If you lose that file you lose the password, which is as bad
as losing the keystore itself.

On the new machine:

```bash
git clone https://github.com/sampathmannam/kaavalan-note.git
cd kaavalan-note && git checkout harden/production-readiness
# copy the two files across, then fix sdk.dir for the new machine:
#   sdk.dir=/Users/<you>/Library/Android/sdk
# and repoint the keystore path:
#   KAAVALAN_RELEASE_STORE_FILE=/new/path/kaavalan-note-release.jks
./gradlew :app:signingReport | grep -A3 "Variant: release"   # Store: must not be null
```

`Store: null` means signing did not resolve and `release.sh` will refuse
to publish — which is the intended behaviour, not a bug.

## What is still outstanding

1. **Back the keystore up off this Mac.** Not done. It sits in a folder
   named `-BACKUP` that is still on the same disk, which is not a backup.
   This is the only unrecoverable risk in the project.
2. **Cut the first release.** Everything is ready:
   `./release.sh 2.2.0`. It will pin the signing certificate's SHA-256
   into `release/signing-fingerprint.txt` on that first run (commit it),
   and every later release must match it. It prompts once to confirm the
   keystore is backed up — answer honestly.
3. **Obtainium**, after the release exists: URL
   `https://github.com/sampathmannam/kaavalan-note`, APK filter
   `kaavalan-note-.*\.apk`.
4. **UI redesign**, parked mid-flight. A canvas with three structural
   directions is published; you picked **A · Ledger** (organise by
   direction of obligation — owed by me / owed to me — with people as a
   search filter rather than a tab). Five screens of the chosen direction
   are drafted, three remain (InstructionDetail, Settings, and the
   PersonDetail refinement). The working `.dc.html` files lived in the
   session scratchpad and have since been cleared, so this restarts from
   the published canvas, not from local files.

## Gotchas worth knowing

**Three separate app identities.** Android treats these as different
apps that share no data:

| Build | applicationId |
|---|---|
| GitHub releases ≤ v1.9.8 | `com.baton.app` |
| local debug builds | `com.kaavalan.note.debug` |
| Obtainium releases (v2.x+) | `com.kaavalan.note` |

Anything captured while testing a debug build will not appear in the
release build. Export first (Settings → Export) if you want to keep it.

**Emulator.** Boot headless with software rendering or it dies on GPU
init a few seconds in — see the "Running the emulator" section of
`docs/RELEASING.md`. This cost hours before it was diagnosed; it presents
as random flakiness and is not.

**Java.** `/usr/bin/keytool` is the macOS stub and fails with "Unable to
locate a Java Runtime". Use the JBR:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

**Data safety.** Room is at schema v16 with a complete 2→16 migration
chain and no destructive fallback, so upgrades within `com.kaavalan.note`
preserve data. Do not add `fallbackToDestructiveMigration` — for this app
the local database is the only copy.
