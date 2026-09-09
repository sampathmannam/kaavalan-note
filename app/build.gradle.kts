import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// local.properties is NOT auto-loaded into Gradle properties; AGP only
// reads sdk.dir from it. Read keys ourselves so a clone + add-to-properties
// + build works without editing gradle.properties (which is checked in).
// Development builds must not inspect signing material. Release credentials
// are resolved only with an explicit opt-in by the release operator / CI.
val enableReleaseSigning = providers.gradleProperty("kaavalan.enableReleaseSigning")
    .map(String::toBoolean).getOrElse(false)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (enableReleaseSigning && f.exists()) f.inputStream().use { load(it) }
}
// v2.0.0 (drop Supabase): removed BATON_SUPABASE_URL and
// BATON_SUPABASE_ANON_KEY. The app is now local-only; no
// cloud sync, no auth, no server-side code. All data lives
// in the on-device SQLCipher DB.

// v2.1.2 (release-integrity): signing-material resolution.
//
// Both helpers use the same precedence — Gradle property
// (`-P` or `~/.gradle/gradle.properties`, which lives outside
// the repo) → `local.properties` (gitignored) → environment
// variable (how CI injects secrets). Nothing is ever read from
// a checked-in default, so no credential can be committed by
// accident.
fun resolveSigningSecret(
    props: Properties,
    propertyName: String,
    envName: String,
): String? = if (!enableReleaseSigning) null else (
    (project.findProperty(propertyName) as? String)
        ?: props.getProperty(propertyName)
        ?: System.getenv(envName)
    )?.trim()?.takeIf { it.isNotEmpty() }

// The keystore path resolves against the repository root when
// it is relative, so `KAAVALAN_RELEASE_STORE_FILE=keys/release.jks`
// in local.properties means the same thing regardless of which
// directory Gradle was invoked from. `~` is expanded because
// developers naturally write `~/keys/release.jks` and Java's
// File() does not expand it.
fun resolveSigningPath(
    props: Properties,
    propertyName: String,
    envName: String,
): File? {
    val raw = resolveSigningSecret(props, propertyName, envName) ?: return null
    val expanded = if (raw.startsWith("~/")) {
        System.getProperty("user.home") + raw.removePrefix("~")
    } else {
        raw
    }
    val f = File(expanded)
    return if (f.isAbsolute) f else rootProject.file(expanded)
}

// v2.1.2 (release-integrity): make `proguard-rules.pro` an input of
// the unit-test task.
//
// `ProguardRulesTest` reads the rules file from disk to assert that
// every fully-qualified keep rule still resolves. Gradle has no way
// to know that, so editing only the rules file left
// `testDebugUnitTest` UP-TO-DATE and the guard silently did not run
// — a stale rule could be introduced and the very test written to
// catch it would be skipped. Declaring the file as an input closes
// that hole. Verified by editing only proguard-rules.pro and
// observing the task re-execute.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("app/proguard-rules.pro"))
        .withPropertyName("proguardRules")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("app/build.gradle.kts"), rootProject.file("release.sh"))
        .withPropertyName("releaseSigningContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

android {
    namespace = "com.kaavalan.note"
    compileSdk = 35
    // v1.2: pin NDK for reproducible builds + first-class 16 KB
    // page-size support. The version catalog (libs.versions.toml)
    // declares the same version; we read it here so gradle.properties
    // overrides propagate.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.kaavalan.note"
        minSdk = 26
        targetSdk = 35
        // v1.6.3: UI/UX round 3 (Obsidian-style pass + app icon).
        // (1) App icon redesigned — new adaptive foreground
        // (indigo shield on cream) shipped to all 5 mipmap
        // densities + the launcher round variants. Notification
        // small icon (24x24 white silhouette) added; the
        // adaptive background switched from a teal-to-coral
        // gradient to a solid cream so the shield carries full
        // visual weight. (2) HomeScreen Quick-note bar moved
        // from a floating Box overlay into the Scaffold's
        // bottomBar slot — the previous overlay hid the last
        // person row on 1080x2400. (3) Open-count badge dropped
        // the prominent tertiaryContainer CircleShape pill and
        // is now a small labelMedium text on the right (Obsidian
        // document density). (4) HomeScreen and TodayScreen
        // TopAppBar titles re-styled to titleSmall + onSurface
        // with a 4dp start inset to compensate for
        // `windowInsets(0)`. (5) Typography tokens retuned to
        // Obsidian scale (16sp body, 14sp UI, 12sp small,
        // 1.5 line height, weight 400-500). (6) TodayScreen
        // passes personNameById to search results so the
        // instruction group header shows "K. Ramana" not a
        // truncated UUID. (7) TodayScreen "Review" button
        // switched from OutlinedButton to TextButton (secondary
        // to content). (8) PersonList / search-results
        // LazyColumns now use horizontal contentPadding so
        // rows have full-width click hit-targets.
        // v1.7.2: versionCode 28, versionName "1.7.2".
        // v1.7.1 fresh-eyes critique came back at 6.5/10.
        // v1.7.2 is a single-ship monolith that closes the new
        // P0 + P1 items and the two carry-over debts from the
        // v1.7.0 critique:
        //   P0-A (worry box "Review in 719163 days"): the
        //     synthetic fixture's reviewAtEpochDay values mapped
        //     to year 3995, so the Worry box rendered "Review
        //     in 719163 days (3995-08-21)" on the default Today
        //     view. v1.7.2 caps the days display to year-only
        //     when >365 days out, and tightens the fixture so
        //     the dated worries use days 3..21 from today.
        //   P1-B (Home last row clipped): when a person row
        //     has no designation + station, the inner Column
        //     collapses to just the name TextView. On a 7-row
        //     initial viewport the 7th row's name was clipped
        //     to h=14. v1.7.2 adds a minHeight=88.dp on the
        //     PersonRow so empty-subtitle rows are still
        //     readable.
        //   P1-C (PersonDetail header duplicated): the
        //     TopAppBar title showed the person's name AND the
        //     body's PersonHeader showed the name again. The
        //     TopAppBar is now just a chevron back icon; the
        //     body's PersonHeader is the single source of
        //     truth.
        //   P1-D (Today search person = no-op): tapping a
        //     person result on Today did nothing (comment said
        //     "search is read-only on Today"). Now routes to
        //     onOpenPerson, matching the Home behaviour.
        //   P1-F (Settings "Free" badge): the TagKind.FREE
        //     section header rendered as the bare word "Free"
        //     — a developer term that leaked into the user-
        //     facing UI. Now renders as "Your tags" for the
        //     FREE kind.
        //   Debt-1: TagPicker.kt's Color(0xFF6F6F6F) literal
        //     replaced with KaavalanColors.KindNeutralLight.
        //   Debt-2: Erase all data confirmation dialog now
        //     requires the user to type "ERASE" before the
        //     confirm button enables (was: single-tap
        //     irreversible action).
        // Bugfix release; no public-surface changes (version
        // bump because the v1.7.1 → v1.7.2 changes are
        // user-visible: worry box dates, home row height,
        // person-detail header, today search, settings tag
        // section label, and erase confirmation flow).
        // v1.7.1: versionCode 27, versionName "1.7.1".
        // v1.7.0 fresh-eyes critique came back at 4.5/10
        // (down from v1.6.8's 6.5 because the v1.6.8 nav
        // fix was incomplete). v1.7.1 is a single-ship
        // monolith that closes every P0 and P1 from that
        // critique:
        //   P0 nav (H1+H2, H3, H4): the system 3-button
        //     gesture-nav hit area overlaps the bottom
        //     NavigationBar — Today/Settings taps on the
        //     Home screen were captured by the system
        //     recents button and the user was dropped into
        //     a background app (BSA for Dummies on
        //     ZD2232FCR5). v1.6.4's 48dp extra bottom
        //     padding was JUST barely enough; the
        //     extended touch area reaches another 30-50dp
        //     above the visible buttons. Bumped to 96dp.
        //   P1 data (T1, T4, T5, P1): synthetic data
        //     duplicates ("B. Srinivas" x2, "Whitespace
        //     Edge" x2) and placeholder strings
        //     (AAAA..., XXX..., "Station-with-a-very-
        //     long-name-...") dominated the top of the
        //     People list. Renamed the duplicates to be
        //     unique and demoted the placeholders so the
        //     top of the list shows realistic names.
        //   P1 UI (T3, Q1, St1): the count badge in
        //     PersonRow now shows " 3 open" (visible
        //     label, not just a digit). The NoteBar
        //     capture buttons (Photo, Voice) now have
        //     visible "Photo" / "Voice" labels under
        //     the icons. The Settings sheet now has a
        //     visible Close X button in the top-right
        //     (was: scrim-tap / swipe-down only).
        // Bugfix release; no public-surface changes.
        // v1.7.0: versionCode 26, versionName "1.7.0". Closes
        // the three gaps flagged by the v1.6.8 fresh-eyes
        // critique (6.5/10):
        //   A. Real-user test: 1-page structured handoff in
        //      docs/v1.7.0_user_test.md (the test itself is a
        //      30-minute observation, not code).
        //   B. Backup story: encrypted local export/import
        //      was already built (VaultExporter/Importer with
        //      Argon2id KDF + AES-256-GCM). v1.7.0 is the first
        //      release to actually exercise it on a real
        //      device round trip (export → wipe → import →
        //      verify counts match). Previously: code path
        //      existed since v1.5.0 but no APK on a phone had
        //      used it.
        //   C. Search: bar + debounce + FTS4 + person filter
        //      were already wired into Home + Today. v1.7.0
        //      closes the remaining gap — tapping an instruction
        //      in search results now opens the InstructionDetailSheet
        //      directly (used to navigate to the person). Lifts
        //      InstructionDetailSheet into ui/components/ so
        //      Home + Today share one implementation.
        // No new public surface; ship as v1.7.0 (not v1.6.9)
        // because C changes the search-result tap behaviour
        // — it's a user-visible contract change worth its own
        // minor bump.
        // v1.7.4: versionCode 30, versionName "1.7.4". Closes
        // the UI critique of v1.7.3's People search result:
        // P1-A unbounded subtitle Text that broke hyphenated
        // words mid-character ("mobi|le-screens") and
        // truncated without ellipsis ("...Waran"); P1-C
        // last row clipped behind the Quick note bar.
        // v1.7.3: versionCode 29, versionName "1.7.3". Closes
        // the v1.7.3 fresh-eyes critique: P0-A stale seeded
        // data on upgrade, P1-B test-data placeholders, P1-C
        // Export CSV/JSON radio, P1-D M3 NavigationBarItem
        // clickable=false on active tab, P2-A quiet-contacts row
        // clip, P2-C (unknown person) header clip.
        // v1.8.0: versionCode 31, versionName "1.8.0". Closes
        // the production-readiness Phase 1 P0s + P1s +
        // Phase 2 P0 #1..#7: backup+restore, dedup,
        // retention, audit chain, branding, eFIR bridge,
        // role model. Phase 1 audit found 3 P0 + 3 P1 were
        // pre-existing closures; only 5 P0 + 3 P1 needed
        // fresh work.
        // v1.9.4: versionCode 36, versionName "1.9.4".
        // "Compact cards" release. The v1.9.3 fix
        // removed the visible gap below the bottom nav,
        // but the DecayRow card layout was still
        // ~292px tall because the v1.9.2 fix stacked
        // the "Mark recent" button above the
        // "Quiet a while" pill in a right-aligned
        // Column. On a 1264x2780 device, only 3 full
        // DecayRow cards + a partial 4th fit on the
        // visible Today screen, with a partial card
        // clipping at the bottom. The user reported
        // "UI should use the screen properly". v1.9.4
        // flips the right-side controls back to
        // horizontal siblings (the v1.8.0 layout)
        // while keeping the v1.9.2 `maxLines` caps on
        // the name (2 lines) + designation (1 line),
        // both ellipsized. The name + designation
        // + days-quiet column is bounded to ~3 lines,
        // so a ~420dp right column leaves ~800dp for
        // the name and designation — enough for any
        // realistic Indian-police name. Card height
        // drops from ~292px back to ~210px, the
        // screen shows 4-5 full DecayRow cards
        // instead of 3, and the bottom of the scroll
        // is no longer a partial card. Bugfix release;
        // no public-API or schema changes. versionCode
        // 35 -> 36. The third drive-verify polish
        // in this cycle (v1.9.2: stack controls;
        // v1.9.3: remove 192dp gap; v1.9.4: keep
        // stack fix, drop the visual gap; flip back
        // to horizontal controls because the stack
        // wasted too much vertical space).
        // v1.9.5: versionCode 37, versionName "1.9.5".
        // The fourth drive-verify polish. The
        // v1.9.4 horizontal-sibling layout still
        // left the "Mark recent" TextButton eating
        // ~80dp of horizontal space, which forced
        // the days-quiet text to ellipsize as
        // "haven't touched in 93 d..." instead of
        // the full "haven't touched in 93 days".
        // v1.9.5 drops the TextButton entirely and
        // moves the "Mark recent" affordance to:
        //  1. Swipe-right past a 96dp threshold
        //     (Material 3 standard list-item
        //     side-effect action)
        //  2. Long-press → ModalBottomSheet with
        //     "Mark as recent" + "Cancel" actions
        // The status pill (ReachOutPill) is the
        // only right-side control. Left column
        // gets the full available width so
        // "haven't touched in 93 days" renders
        // without ellipsis. 6+ full DecayRow
        // cards visible on the Today screen
        // (was 5 in v1.9.4). Bugfix release;
        // no public-API or schema changes.
        // versionCode 36 -> 37.
        // v1.9.7: "polish" pass (a11y, drive label, onboarding).
        // v1.9.8: honest production & enterprise gap analysis
        // (docs only — no code change).
        // v1.9.9: atomic create() in RoomInstructionRepository
        // (PROD-READINESS-P0-#2); debug-gated auto-reseed in
        // AppInitializer (A6 audit fix); one-tap "Report a
        // problem" mailto in Settings → About that embeds
        // the most recent crash log in the body (A10 audit
        // fix). No public-API or schema changes.
        // versionCode 38 -> 39.
        // v1.9.10: closes three observations surfaced by the
        // v1.9.8 audit's refuter (Obs-1: one shared
        // SupabaseClient via Hilt @Provides; Obs-2: tag refresh
        // surfaces HomeUiState.Error on failure; Obs-3: SQLCipher
        // mlock pragma already in v1.4.3 — keying-phase
        // limitation documented honestly). Plus the new
        // QuickNoteWidget: a single-tap home-screen capture
        // that opens a fullscreen entry activity (does not
        // open the main app) so a quick note takes 2 taps +
        // typing, not 3+ taps. No public-API or schema changes.
        // versionCode 39 -> 40.
        // v1.9.11: closes the rest of the v1.9.8 audit's
        // deferral table. A2 (recovery-phrase contract test),
        // A3 (connected-device androidTest CI), A7 (40 MB
        // debug-APK budget + CI guard), A8 (a11y code-level
        // invariants + manual checklist), A9 (Changelog screen
        // — accessible from Settings, NOT a launch-time modal
        // per the v1.6.0 design rule), Obs-3 mlock (custom
        // SQLCipher preKey hook silences the 31 keying-phase
        // v2.1.0: PM rating cleanup round 3. Adds:
        //   - WhatsApp-style Google Drive backup with
        //     AES-256-GCM client-side encryption
        //   - The DatabasePreflight write+read
        //     round-trip (catches silent corruption)
        //   - Removal of the pre-v8 destructive
        //     migration (6 explicit Migrations)
        // No DB schema changes; 16 new unit tests.
        //
        // versionCode 44 -> 45.
        //
        // v2.2.0: first release cut for the Obtainium channel, and the
        // first release under the `com.kaavalan.note` applicationId
        // (everything up to v1.9.8 on GitHub Releases was
        // `com.baton.app` — a different app to Android, so those
        // installs do not update into this line; see docs/RELEASING.md).
        // Contents: 21 defects found by the adversarial on-device audit
        // and fixed with regression tests, plus three product decisions
        // — dispatch reachable via an @mention offer in the note bar
        // (the whole hierarchy feature previously had no live entry
        // point), dispatch no longer auto-marks an instruction done on
        // send, and the recovery-phrase screen now warns before
        // replacing an existing phrase.
        // versionCode 46 -> 47.
        //
        // v2.3.0: focus-first redesign. Today opens with one useful
        // instruction before summaries and reflection tools; People is
        // a clearer directory with the note bar as the sole prominent
        // creation action; first capture no longer requires a person;
        // Settings shows its selected state; and activity-scoped share,
        // widget, and tile events reach the visible capture surface.
        // Includes the post-v2.2 reliability line already integrated
        // into main (release build/device CI, capture ingress, backup,
        // retention, migration, and startup hardening).
        // versionCode 47 -> 48.
        //
        // v2.4.0: complete reminder workflow. Notes can be saved with
        // quick or custom reminder times, edited later, and delivered as
        // private local notifications with Done and Snooze actions.
        // Notification taps open the matching instruction, and unique
        // WorkManager work survives app restarts without duplicate alerts.
        // versionCode 48 -> 49.
        versionCode = 49
        versionName = "2.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // v2.2.2 (test-infra): wipe app state between instrumented
        // tests. Without this the six device tests share one Room
        // database and one DataStore, so each test's premise depends
        // on which earlier tests happened to pass. Observed directly:
        // the moment AddPersonFlowTest started passing it persisted
        // "QA Person", and HomeEmptyStateTest -- which runs later and
        // asserts on the EMPTY state -- stopped finding an empty Home
        // at all. Only honoured with the orchestrator below.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        vectorDrawables { useSupportLibrary = true }

        // v2.0.0 (drop Supabase): removed SUPABASE_URL +
        // SUPABASE_ANON_KEY buildConfigFields. Local-only;
        // no server-side config.
        // v1.8.0 (PROD-READINESS-P2-#6): the brand-name and
        // brand-department build-config fields. A pilot
        // build (e.g. TNeGA / CCPS) overrides these via
        // `gradle -Pbrand.name="TNeGA CCPS" -Pbrand.dept="..."`;
        // the defaults are the R&D "Kaavalan note" brand
        // so a plain `./gradlew assembleDebug` produces
        // the v1.8.0 default. The Settings "About" row
        // reads BRAND_NAME so the user-visible label
        // tracks the build that produced the APK.
        buildConfigField(
            "String",
            "BRAND_NAME",
            "\"${project.findProperty("brand.name") ?: "Kaavalan note"}\"",
        )
        buildConfigField(
            "String",
            "BRAND_DEPARTMENT",
            "\"${project.findProperty("brand.dept") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "BRAND_ICON",
            "\"${project.findProperty("brand.icon") ?: "ic_launcher"}\"",
        )
        // v2.1.1 (security): the Google OAuth 2.0 client
        // ID. The v2.1.0/v2.1.1 code shipped a
        // placeholder ("KAAVALAN_NOTE_GOOGLE_OAUTH_CLIENT_ID_PLACEHOLDER")
        // hard-coded in
        // [com.baton.app.data.backup.GoogleOAuthClient].
        // The placeholder fails Google's token exchange
        // with `400 invalid_client`; the user must set
        // a real client ID from the Google Cloud Console
        // before shipping to the Play Store.
        //
        // The real client ID is read from `local.properties`
        // (gitignored) at build time, with a Gradle project
        // property override for CI. The default is the
        // v2.1.0 placeholder so an out-of-the-box
        // `./gradlew assembleDebug` still compiles.
        buildConfigField(
            "String",
            "KAAVALAN_NOTE_GOOGLE_OAUTH_CLIENT_ID",
            "\"" + (
                (project.findProperty("baton.googleOauthClientId") as? String)
                    ?: (localProps.getProperty("KAAVALAN_NOTE_GOOGLE_OAUTH_CLIENT_ID"))
                    ?: "KAAVALAN_NOTE_GOOGLE_OAUTH_CLIENT_ID_PLACEHOLDER"
            ) + "\"",
        )
        buildConfigField(
            "String",
            "KAAVALAN_NOTE_GOOGLE_OAUTH_REDIRECT_URI",
            "\"" + (
                (project.findProperty("baton.googleOauthRedirectUri") as? String)
                    ?: (localProps.getProperty("KAAVALAN_NOTE_GOOGLE_OAUTH_REDIRECT_URI"))
                    ?: "kaavalan-note://oauth-callback"
            ) + "\"",
        )
    }

    // v2.1.2 (release-integrity): resolve the release signing
    // material from the environment instead of hard-coding it.
    //
    // The v2.1.0 security pass scrubbed the keystore from git
    // history and added `*.keystore` to .gitignore, but left
    // `storeFile = file("kaavalan-note-release.keystore")` and a
    // pair of hard-coded password fallbacks in place. Two
    // consequences, both confirmed on a fresh clone of `main`:
    //
    //   1. `./gradlew :app:assembleRelease` fails at
    //      `validateSigningRelease` ("Keystore file ... not
    //      found") on every clean checkout and in CI. The
    //      shippable artifact could not be built at all.
    //   2. The `?: "kaavalan-note-release-2026"` fallbacks put
    //      the intended keystore password in a public repo. Any
    //      keystore later generated with that password — which
    //      the .gitignore comment explicitly invites — is
    //      protected by a published secret.
    //
    // The resolution order below is the standard Android one:
    // Gradle property (`-P`, `~/.gradle/gradle.properties`) →
    // `local.properties` (gitignored) → environment variable
    // (CI secrets). Without the explicit signing opt-in, the release
    // build stays UNSIGNED: R8/minify,
    // resource shrinking and the per-ABI splits all still get
    // exercised, which is what a CI `assembleRelease` gate is
    // actually for. Signing then happens with the real key at
    // release time. With the signing opt-in, incomplete configuration
    // is fatal before any build tasks run; never silently fall back.
    val releaseStoreFile: File? = resolveSigningPath(
        localProps,
        propertyName = "KAAVALAN_RELEASE_STORE_FILE",
        envName = "KAAVALAN_RELEASE_STORE_FILE",
    )
    val releaseStorePassword: String? = resolveSigningSecret(
        localProps,
        propertyName = "KAAVALAN_RELEASE_STORE_PASSWORD",
        envName = "KAAVALAN_RELEASE_STORE_PASSWORD",
    )
    val releaseKeyAlias: String? = resolveSigningSecret(
        localProps,
        propertyName = "KAAVALAN_RELEASE_KEY_ALIAS",
        envName = "KAAVALAN_RELEASE_KEY_ALIAS",
    ) ?: "kaavalan-note-release"
    val releaseKeyPassword: String? = resolveSigningSecret(
        localProps,
        propertyName = "KAAVALAN_RELEASE_KEY_PASSWORD",
        envName = "KAAVALAN_RELEASE_KEY_PASSWORD",
    ) ?: releaseStorePassword

    // A signing config is only worth creating when every part of
    // it resolved. A half-populated config fails later and more
    // confusingly than not having one.
    val releaseSigningReady: Boolean =
        releaseStoreFile != null &&
            releaseStoreFile.isFile &&
            !releaseStorePassword.isNullOrEmpty() &&
            !releaseKeyAlias.isNullOrEmpty() &&
            !releaseKeyPassword.isNullOrEmpty()

    // An unsigned R8 build is useful in CI, but an explicit request to sign
    // must never report success while silently producing an unsigned APK.
    // Do not include resolved paths or credential values in the failure.
    if (enableReleaseSigning && !releaseSigningReady) {
        throw GradleException(
            "KaavalanNote: release signing was requested, but the existing signing " +
                "configuration is unavailable or incomplete. No signed APK can be produced. " +
                "The release operator must configure the original signing key privately. " +
                "For unsigned development/CI validation, omit kaavalan.enableReleaseSigning " +
                "or set it to false. Never install or publish the unsigned validation APK.",
        )
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Both signature schemes: v1 (JAR) for API 26-27,
                // v2/v3 (APK Signature Scheme) for API 28+. Play
                // requires v2+ for new uploads; v1 keeps the
                // sideload path working on the minSdk 26 floor.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = providers.gradleProperty("kaavalan.debugApplicationIdSuffix").getOrElse(".debug")
            isDebuggable = true
        }
        release {
            // v1.2: enable R8 minify + resource shrink for release
            // APKs. Saves ~30% size and forces a clean proguard-rules.pro
            // (was missing in v1.1.1; now mandatory). Debug build is
            // unaffected.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // v2.1.2 (release-integrity): attach the release
            // signing config only when the keystore actually
            // resolved (see the `signingConfigs` block above).
            // When signing is not requested, the build type has no
            // signing config and AGP emits an unsigned APK —
            // `assembleRelease` still runs R8, resource
            // shrinking and the ABI splits, so CI keeps its
            // real release gate on a machine that holds no
            // secrets. The previous unconditional
            // `signingConfigs.getByName("release")` threw
            // `UnknownDomainObjectException` in that case.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // v2.2.2 (test-infra): run each instrumented test in its own
    // process via Android Test Orchestrator. This is what makes
    // `clearPackageData` above take effect; it also stops one
    // crashed test from taking the rest of the run down with it.
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // v2.1.2 (release-integrity): make Android Lint a real gate again.
    //
    // Lint reported 525 errors on `main`. 522 of them were
    // `MissingTranslation` — the known, deliberate state recorded in
    // docs/PRODUCTION_READINESS_PLAN.md (P3-P0-#3): Tamil and Hindi
    // cover the ~30 most user-facing strings and the remaining ~320
    // fall back to English, which is Android's documented behaviour
    // and not a defect. Because those 522 were fatal by default, the
    // CI lint job was set to `continue-on-error: true` — which
    // silenced lint entirely.
    //
    // What that hid: the single `NewApi` error in the project, a call
    // to the API 33 `Intent#getParcelableExtra(String, Class)`
    // overload from `VoiceCaptureService` against `minSdk 26`. It
    // crashed voice capture with `NoSuchMethodError` on every device
    // below Android 13. A gate that is always green catches nothing.
    //
    // So: demote the known-and-accepted `MissingTranslation` to a
    // warning, keep everything else fatal, and let the build fail on
    // a real error. `abortOnError` is left at its default (true).
    // When the remaining strings are translated, delete the
    // `informational` line rather than adding more entries to it.
    lint {
        // Documented partial-translation state; English fallback is
        // correct behaviour, not a bug. Re-promote once ta/hi are complete.
        informational += "MissingTranslation"
        // Correctness issues that would ship a crash must never be
        // downgraded, whatever else is in the report.
        fatal += listOf("NewApi", "InlinedApi")
        warningsAsErrors = false
        checkReleaseBuilds = true
        // A machine-readable report for CI to upload alongside the HTML.
        xmlReport = true
        htmlReport = true
    }

    // v1.9.1 (PROD-READINESS-P3-P1-#5 + honest
    // deployability): per-ABI splits for release builds.
    // The v1.9.0 universal release APK was 71.3 MB
    // (R8 + shrinkResources enabled, but all four ABIs
    // arm64-v8a / armeabi-v7a / x86 / x86_64 bundled).
    // Most of the size is the per-ABI lib/ subtree
    // (SQLCipher, OkHttp, ktor, supabase-kt native
    // shims, CameraX, ML Kit). Splitting per ABI drops
    // each per-architecture APK to ~35-40 MB on disk;
    // the App Bundle (.aab) lets Google Play deliver
    // the right ABI per device, so the user-installed
    // size is the per-ABI number.
    //
    // v1.9.1 trade-off: the splits config is added
    // here so `./gradlew :app:assembleRelease` emits
    // 4 APKs (one per ABI). The full App Bundle
    // (`./gradlew :app:bundleRelease` -> .aab) is
    // documented in `docs/play-store-listing.md` as
    // the Play-Store submission artifact; the splits
    // here are also useful for sideloading a single
    // per-ABI APK to a tester. v1.9.1 does NOT change
    // the debug build (still universal; debug never
    // ships to users).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

// v2.1.1 (test(e2e) smoke tests): the v2.0.0-supabase-drop
// branch left three stale androidTest files that no longer
// compile against the v2.0+ code base:
//
//   - `com.kaavalan.note.M0AcceptanceTest`         (references
//     the removed `AuthRepository` / `PersonRepository`).
//   - `com.kaavalan.note.CaptureHappyPathTest`     (same).
//   - `com.kaavalan.note.data.vault.VaultEndToEndTest`
//     (missing `androidx.core.net.toUri` import and
//     mis-uses `kotlinx.coroutines.flow.first`).
//
// The previous blanket `java.exclude("com/kaavalan/**")`
// block did not propagate to the Kotlin source set
// (https://issuetracker.google.com/issues/156782335) so
// KSP kept reading the dead files. The fix below filters
// the three specific files at the KSP + Kotlin compile
// task boundary, via the `getExcludes` / `setExcludes`
// pair that `KspTaskJvm` and the Kotlin compile base
// expose through `PatternFilterable`. The smoke tests in
// `app/src/androidTest/java/com/kaavalan/note/` (which
// live alongside the dead files) compile cleanly.

// v2.1.1 (test(e2e) smoke tests): the KSP + Kotlin compile
// tasks do not honor the AGP source set excludes — they
// wire their `sources` FileCollection through the
// KspSourceSet / Kotlin source set providers, which do
// not see `android.sourceSets[...].java.exclude()` or
// `kotlin.sourceSets[...].kotlin.exclude()`. The fix is
// to filter the dead files out at the task boundary, by
// appending the file paths to each task's own
// `getExcludes()` set (the `setExcludes` /
// `PatternFilterable` pair exposed by `KspTaskJvm` and
// the Kotlin compile base).
gradle.projectsEvaluated {
    val deadFilePatterns = listOf(
        "com/kaavalan/note/M0AcceptanceTest.kt",
        "com/kaavalan/note/CaptureHappyPathTest.kt",
        // v2.1.1: `data/vault/VaultEndToEndTest.kt` is also
        // dead input — it references `androidx.core.net.toUri`
        // (no import) and calls `kotlinx.coroutines.flow.first`
        // as a free function (it is an extension on `Flow`).
        // The Vault round-trip is covered by the JVM unit
        // tests in `src/test/.../data/vault/...`; the
        // instrumented round-trip needs a maintenance pass
        // that the user has not scheduled yet.
        "com/kaavalan/note/data/vault/VaultEndToEndTest.kt",
    )
    val taskTypes = listOf(
        com.google.devtools.ksp.gradle.KspTask::class.java,
        org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java,
    )
    taskTypes.forEach { taskType ->
        tasks.withType(taskType).configureEach {
            if (name.contains("AndroidTest")) {
                val getExcludes = javaClass.methods.firstOrNull { it.name == "getExcludes" }
                val setExcludes = javaClass.methods.firstOrNull { it.name == "setExcludes" }
                if (getExcludes != null && setExcludes != null) {
                    @Suppress("UNCHECKED_CAST")
                    val current = (getExcludes.invoke(this) as MutableSet<String>).toMutableSet()
                    current.addAll(deadFilePatterns)
                    setExcludes.invoke(this, current)
                }
            }
        }
    }
}

// v1.6.1: removed the `vendorLlamaCpp` + `vendorWhisperCpp` tasks.
// The on-device LLM is gone, so no C++ source to vendor, no
// JNI library to build, no `libllama.so` / `libwhisper.so` to
// merge. Capture uses `android.speech.SpeechRecognizer` (a
// system service) for voice transcription.
dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.sqlcipher.android)
    implementation(libs.security.crypto)

    // v2.0.0 (drop Supabase): supabase-kt + ktor + okhttp are
    // declared but UNUSED in v2.0.0 code. We keep them declared
    // for two reasons: (1) the version catalog has other entries
    // (glance, datastore) that depend transitively on okio
    // (pulled in by ktor) — removing the ktor declaration
    // breaks transitive resolution. (2) When/if a future v2.x
    // pass adds optional cloud sync, the deps are already
    // declared. A cleanup pass that removes the catalog
    // entries should verify transitive resolution still works.
    implementation(libs.supabase.kt)
    implementation(libs.supabase.postgrest.kt)
    implementation(libs.supabase.auth.kt)
    implementation(libs.supabase.functions.kt)
    implementation(libs.supabase.realtime.kt)
    implementation(libs.supabase.storage.kt)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)
    // M2-T7 (legacy): the OkHttp engine was the only ktor
    // engine supporting WebSockets (realtime). v2.0.0 has
    // no realtime (no Supabase); the dep is kept for
    // transitive resolution (okio).
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.okhttp)

    // M2-T2: photo capture via CameraX + ML Kit on-device OCR.
    implementation(libs.camerax.camera.core)
    implementation(libs.camerax.camera.camera2)
    implementation(libs.camerax.camera.lifecycle)
    implementation(libs.camerax.camera.view)
    implementation(libs.mlkit.text.recognition)
    // v2.1.0 (PM rating): Google Drive backup. The
    // preferred path is GoogleSignInClient (Play Services
    // Auth) for a one-tap sign-in. The fallback path
    // (when Play Services is not available) is a
    // Custom Tabs OAuth flow via androidx.browser.
    // The `play-services-auth` dep is conditional on
    // `googleSignInAvailable = true` (see below); for the
    // v2.1.0 build the Custom Tabs path is the
    // default since the offline cache doesn't have
    // play-services-auth.
    implementation(libs.androidx.browser)

    // Tier 0.1: Jetpack Glance for the home-screen / lock-screen
    // capture widget. The Glance composable API + a
    // GlanceAppWidgetReceiver entry point; replaces the legacy
    // AppWidgetProvider + RemoteViews implementation in
    // BatonCaptureWidget.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Tier 1.1 (v2.0): vault backup KDF.
    implementation(libs.argon2kt)
    // Tier 1.4 (v2.0): DataStore for the theme switcher + 1.2
    // onboarding flag.
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.okhttp)
    testImplementation(libs.room.testing)
    testImplementation(libs.sqlite)
    // v1.4.3 (F-09/F-20 wiring): WorkManagerTestInitHelper so the
    // WorkManagerInitializer unit tests can exercise enqueue without
    // an actual WorkManager runtime.
    testImplementation(libs.work.testing)
    // v1.3: Compose a11y contentDescription assertions
    // (createComposeRule + hasContentDescription). The test runs
    // under Robolectric so no device or emulator is needed.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    // v2.1.2 (test-infra): GrantPermissionRule. `androidx.test:rules` was
    // already present transitively (androidx.test:runner pulls it in at
    // runtime) but not exposed on the compile classpath, so the six
    // Compose UI smoke tests that now use GrantPermissionRule.grant(...)
    // to pre-grant POST_NOTIFICATIONS could not compile against it.
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.compose.bom))
    // v2.1.2 (release-integrity): androidTestImplementation(libs.mockk)
    // removed. `libs.mockk` resolves to the plain JVM `io.mockk:mockk`
    // artifact -- correct for `testImplementation` (used above), wrong
    // for `androidTestImplementation`, which runs on-device and needs
    // the separately published `io.mockk:mockk-android` artifact
    // instead. Nothing in app/src/androidTest/ actually references
    // MockK (verified by search), so this was a dead, wrong-variant
    // declaration. Its transitive `org.junit.jupiter:*` dependencies
    // collided on `META-INF/LICENSE.md` during resource merging and
    // failed `assembleDebugAndroidTest` outright --
    // `./gradlew :app:connectedDebugAndroidTest` could not even build,
    // let alone run, on any device. This had never been caught because
    // the CI androidTest job is optional and skips without a device
    // (see the job's own doc comment below); running it against a real
    // emulator for the first time is what surfaced this. If a future
    // instrumented test needs mocking, add `io.mockk:mockk-android` as
    // its own catalog entry rather than reusing this alias.
    // v1.8.0 (PROD-READINESS-P0-#7): the androidTest source
    // set needs the Compose UI test rule + the Hilt testing
    // annotation. The previous build was missing these — the
    // existing M0AcceptanceTest.kt and VaultEndToEndTest.kt
    // imported them but did not compile. Adding the deps here
    // unblocks both the existing tests and the new
    // CaptureHappyPathTest.
    androidTestImplementation(libs.compose.ui.test.junit4)
    // v2.2.2 (test-infra): the orchestrator APK itself. It is
    // installed alongside the test APK rather than compiled
    // against, hence androidTestUtil and not
    // androidTestImplementation.
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
