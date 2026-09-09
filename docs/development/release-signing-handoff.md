# Release signing handoff investigation — 2026-09-09

## Finding

The operator ran the private handoff command for commit
`db4ca7f8bb4a5ee26189f390fb81ee2950e6c7a1`. Gradle reported success, but
`app/build/outputs/apk/release/output-metadata.json` identified only unsigned
release APKs. Verification of the universal unsigned APK failed, as expected.
It is not an installable update.

The build script allowed an explicit `kaavalan.enableReleaseSigning=true`
request to fall back to an unsigned build if its signing configuration did not
resolve. Separately, `release.sh` never passed that opt-in to its signing report
or release assembly, so those commands could not select release signing.

The handoff changes into the development project itself. The operator's shell
prompt showing another directory did not change its build target. It also uses
an isolated Gradle user home, so it cannot assume credentials from the operator's
usual Gradle configuration are available. Their actual location has **not** been
established: production files, local signing passwords and the production
keystore remain outside the agent's authorized scope.

## Fix

- Explicit signing requests now fail at configuration time if incomplete,
  rather than succeeding with an unsigned artifact. The new error does not print
  configured key paths or credential values.
- Default/signing-disabled development builds still exercise the full unsigned
  R8 release pipeline without enabling the app's signing resolver.
- `release.sh` now opts into signing for its preflight and release build,
  preserves preflight failure, and explicitly disables signing for unit tests.
- Source-only unit guards cover the release commands. Changes to the build or
  release script are declared as test inputs, preventing stale unit-test results.
- `tools/tests/test_release_signing.py` exercises the real Gradle configuration
  in disposable fixtures containing only allowlisted public build files and
  synthetic values. No real key is copied, read or generated; no signing task is
  invoked. Even its complete-config fixture is an ordinary text file, not a key.

These changes repair signing control flow; they do **not** recover or provision
the original key, validate its password, or produce a signed APK.

## Safe checks

Read-only GitHub metadata checks found zero repository Actions secrets and no
deployment environments. The repository belongs to a personal account, not an
organization. The checked-in workflow is a build/test pipeline, not a remote
signing service. No secret values were requested. The latest published asset
remains `kaavalan-note-2.3.0.apk`; it is not the new redesign.

The original public signing fingerprint remains unchanged in
`release/signing-fingerprint.txt`. Android's update model requires the existing
signing identity; creating a replacement key is not a repair for this issue.
See [Android's signing documentation](https://developer.android.com/studio/publish/app-signing).

## Verification

- Unit suite: **726 total, 714 passed, 12 pre-existing skips, zero failures or
  errors**, across 144 suites. All three new release-script guards passed.
- Real Gradle signing-configuration fixtures: **5/5 passed** in 90.498 seconds.
  Covers opt-in off, absent configuration, missing key file, missing password,
  and complete synthetic configuration. The initial malformed-properties
  fixture was corrected because Android/Kotlin plugins independently parse
  `local.properties`; it was a test-fixture failure, not an app signing defect.
- `bash -n release.sh` and `git diff --check`: passed. The real release script
  was **not executed**.
- `lintDebug`: passed, **zero errors/fatals**, 334 existing warnings and 538
  informational findings. `lintVitalRelease` also passed.
- `assembleDebug` and signing-disabled `assembleRelease`: passed. R8 and
  unchanged app compilation inputs were up to date; the full combined Gradle
  invocation completed successfully in **8m 23s** (17 tasks executed, 98 up to
  date). Application ID/version and the pinned certificate were not changed.

No push, release publication or phone operation is part of this repair.

## Remaining authority boundary

### Subsequent authorization

The user subsequently explicitly authorized the signing access needed to finish
the release. Read-only private preflight found the original signing configuration
and successfully unlocked its existing key; its public certificate matches the
unchanged pinned fingerprint. Credentials stayed in process memory and were not
printed or copied into the development configuration. Production files were not
modified. The historical restriction and incomplete handoff below describe the
earlier blocked state, not a claim that the key is missing.

The final release uses the development checkout and the original signing
identity. Actual publication and phone-update evidence is recorded separately
after those operations complete.

Do not run the old generated private handoff command again as a proposed fix.
Its assumption that signing is configured has not been established, and its
verified-commit/clean-tree guards intentionally reject this changed checkout.

To produce an update, the original signing setup must be available to an
authorized release operator or a separately authorized signing process. The
agent cannot search or use the prohibited production signing setup without the
user explicitly granting a narrow exception. Never substitute a debug APK,
uninstall the existing app, change the pinned certificate, or publish an unsigned
validation build to work around this boundary.
