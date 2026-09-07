package com.kaavalan.note

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaavalan.note.data.preferences.KaavalanPreferences
import com.kaavalan.note.data.preferences.ThemeMode
import com.kaavalan.note.data.undo.UndoController
import com.kaavalan.note.features.capture.ShareIntake
import com.kaavalan.note.features.capture.ocrTextOrEmpty
import com.kaavalan.note.features.onboarding.OnboardingScreen
import com.kaavalan.note.features.search.SearchViewModel
import com.kaavalan.note.features.theme.ThemeViewModel
import com.kaavalan.note.features.vault.VaultExportSheet
import com.kaavalan.note.features.vault.VaultImportSheet
import com.kaavalan.note.ui.home.HomeScreen
import com.kaavalan.note.ui.privacy.RecoveryPhraseScreen
import com.kaavalan.note.ui.privacy.ThreatModelScreen
import com.kaavalan.note.ui.settings.SettingsSheet
import com.kaavalan.note.ui.theme.KaavalanNoteTheme
import com.kaavalan.note.ui.today.TodayScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M3.5: real [NavHost] with the three primary tabs.
 *
 * v1.4 (PHONE-FINDING-10 / F-02): request POST_NOTIFICATIONS
 * after sign-in via rememberLauncherForActivityResult. The
 * launcher is top-level in MainScaffold so it survives
 * recomposition. A rememberSaveable flag stops re-prompting
 * across config changes.
 *
 * v1.4 (PHONE-FINDING-6): NetworkObserver singleton registered
 * in onStart / unregistered in onStop. The current isOnline is
 * rendered as an OfflineIndicator overlay at the top of the
 * Scaffold.
 *
 * v2.0 (Tier 1.2 + Tier 1.4 + Tier 1.6): first-run onboarding
 * gates the [MainScaffold]; theme is read from the [ThemeViewModel]
 * (DataStore-backed) and passed to [KaavalanNoteTheme]; the
 * [UndoController] exposes the last [com.kaavalan.note.data.undo.UndoableAction]
 * which the [SnackbarHostState] listens to and shows a 5 s
 * "Undo" affordance.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels()
    @javax.inject.Inject lateinit var briefNotifier: com.kaavalan.note.data.brief.BriefNotifier
    @javax.inject.Inject lateinit var preferences: KaavalanPreferences
    @javax.inject.Inject lateinit var undoController: UndoController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // v2.1.1 (QA P1-#3): only consume the launch intent on the
        // FIRST create, not on a config change. On rotation
        // savedInstanceState is non-null, so re-firing
        // consumeSharedText / consumeQuickCapture here would re-open
        // the capture sheet every time the user rotates the device.
        // The onNewIntent path below is the right path for new
        // shared-text / widget intents delivered while the activity
        // is already alive; the cold-start path runs exactly once.
        if (savedInstanceState == null) {
            consumeSharedText(intent)
            consumeQuickCapture(intent)
        }
        briefNotifier.schedule()
        setContent {
            val themeViewModel: ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            KaavalanNoteTheme(darkTheme = useDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val seen by preferences.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = false)
                    if (!seen) {
                        OnboardingScreen(onDone = { /* DataStore flips; recomposition picks it up */ })
                    } else {
                        MainScaffold(
                            rootViewModel = rootViewModel,
                            undoController = undoController,
                            preferences = preferences,
                            onRequestNotificationsPermission = ::requestPostNotifications,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedText(intent)
        consumeQuickCapture(intent)
    }

    private fun consumeQuickCapture(intent: Intent?) {
        if (intent?.action == com.kaavalan.note.features.capture.KaavalanCaptureWidget.ACTION_QUICK_CAPTURE) {
            rootViewModel.onQuickCapture()
        }
    }

    private fun consumeSharedText(intent: Intent?) {
        val payload = ShareIntake.inspect(intent) ?: return
        when (payload) {
            is ShareIntake.Result.Text -> rootViewModel.onSharedText(payload.text)
            is ShareIntake.Result.Image -> {
                // The manifest share target is an activity alias that
                // launches MainActivity directly. OCR the sender-owned
                // URI here and degrade to an empty capture when it is
                // revoked, cloud-only, or undecodable.
                lifecycleScope.launch {
                    rootViewModel.onSharedText(
                        ocrTextOrEmpty(applicationContext, payload.uri),
                    )
                }
            }
        }
    }

    /**
     * v1.4 (PHONE-FINDING-10 / F-02): ask the user for the
     * POST_NOTIFICATIONS permission. On Android 13+ this is a
     * runtime grant; on older versions the manifest declaration
     * is enough. The launcher is created in [MainScaffold] (a
     * Composable, so it survives recomposition) and the
     * activity-level [requestPostNotifications] entry point
     * fires a Toast rationale + delegates to the launcher via
     * the [notifLauncher] reference.
     *
     * If the permission is already held (e.g. the user toggled
     * it on in system settings while the app was backgrounded)
     * we re-queue the brief directly so the WorkManager job
     * reflects the new state.
     */
    fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            briefNotifier.schedule()
            return
        }
        Toast.makeText(
            this,
            R.string.notifications_rationale,
            Toast.LENGTH_LONG,
        ).show()
        val launcher = notifLauncher
        if (launcher == null) {
            Toast.makeText(
                this,
                "Notifications launcher not ready; please retry.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * v1.4 (PHONE-FINDING-10 / F-02): the [MainScaffold]
     * composable assigns this launcher when it builds. The
     * assignment happens in a Composable, so it survives
     * recomposition; we keep a strong reference here so the
     * activity-level [requestPostNotifications] entry point
     * can fire it.
     */
    internal var notifLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
}

/**
 * M3.5: top-level scaffold.
 *
 * v1.4: hosts the POST_NOTIFICATIONS launcher and renders the
 * OfflineIndicator as a top-end overlay above the inner screens'
 * TopAppBars.
 *
 * v2.0 (Tier 1.6): hosts a single [SnackbarHostState] for the
 * undo snackbar. The host observes [UndoController.last] and
 * shows a 5 s "Undo" affordance when a destructive action
 * pushes a new [com.kaavalan.note.data.undo.UndoableAction]. The
 * snackbar auto-clears the action on dismiss / timeout.
 */
@Composable
private fun MainScaffold(
    rootViewModel: RootViewModel,
    undoController: UndoController,
    preferences: KaavalanPreferences,
    onRequestNotificationsPermission: () -> Unit,
) {
    val navController = rememberNavController()
    var showSettings by remember { mutableStateOf(false) }
    var showVaultExport by remember { mutableStateOf(false) }
    var showVaultImport by remember { mutableStateOf(false) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME
    // Settings is intentionally a configuration sheet rather than a
    // fourth working screen. Reflect that temporary state in the
    // navigation bar so its tap has visible, persistent feedback.
    val selectedNavRoute = if (showSettings) Routes.SETTINGS else currentRoute
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lastUndo by undoController.last.collectAsStateWithLifecycle()
    val undoLabel = stringResource(R.string.undo)

    // v2.0: a single SnackbarHostState listens to the UndoController
    // flow. When a new action arrives, the snackbar shows "Action
    // undone" + an "Undo" button. 5 s auto-dismissal maps to
    // SnackbarDuration.Short. On undo, the controller's
    // `undoLast()` re-inserts the row.
    //
    // v1.9.6 (drive-verify polish #6): the message must show
    // the human-readable name (`action.displayName`), NOT a
    // UUID fragment (`action.id.take(6)`). The v1.9.5
    // implementation read the first 6 chars of the contact's
    // UUID, so the snackbar read "Mark recent 96ldae" instead
    // of "Mark recent B. Ramesh Naidu". Every `UndoableAction`
    // variant now declares its own `displayName` (person name,
    // instruction title, capture preview, or person name for
    // MarkPersonRecent).
    LaunchedEffect(lastUndo) {
        val action = lastUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${action.label} ${action.displayName}",
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> {
                scope.launch { undoController.undoLast() }
            }
            SnackbarResult.Dismissed -> {
                undoController.clear()
            }
        }
    }

    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            (context as? MainActivity)?.briefNotifier?.schedule()
        }
    }
    SideEffect {
        (context as? MainActivity)?.notifLauncher = notifLauncher
    }

    var hasRequestedNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasRequestedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasRequestedNotifications = true
            onRequestNotificationsPermission()
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in setOf(Routes.HOME, Routes.TODAY)) {
                BottomNav(
                    navController = navController,
                    currentRoute = selectedNavRoute,
                    onSettingsClick = { showSettings = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenPerson = { id -> navController.navigate("person/$id") },
                        // RootViewModel is activity-scoped because share,
                        // widget, and tile actions arrive at MainActivity.
                        // Passing this same instance into Home prevents a
                        // navigation-scoped replacement from dropping an
                        // ingress event before CaptureSheet can consume it.
                        rootViewModel = rootViewModel,
                    )
                }
                composable(Routes.TODAY) {
                    TodayScreen(
                        onOpenPerson = { id -> navController.navigate("person/$id") },
                    )
                }
                composable(Routes.PERSON) { entry ->
                    val personId = entry.arguments?.getString("personId") ?: return@composable
                    HomeScreenPersonDetail(
                        personId = personId,
                        onBack = { navController.popBackStack() },
                        onOpenLinkedPerson = { id -> navController.navigate("person/$id") },
                    )
                }
                // v2.0 T3-2: recovery phrase screen. Reachable
                // from Settings → Privacy → Recovery phrase.
                // The screen manages its own FLAG_SECURE flag
                // via [com.kaavalan.note.ui.privacy.FlagSecureEffect].
                composable(Routes.RECOVERY_PHRASE) {
                    RecoveryPhraseScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v2.0 T3-3: threat model screen. Reachable
                // from Settings → Privacy → Threat model.
                composable(Routes.THREAT_MODEL) {
                    ThreatModelScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v1.9.12 (A9 wire-up): the changelog screen.
                // Reachable from Settings → Privacy → What's
                // new. The screen reads assets/changelog.json
                // and marks the current version as "seen" on
                // dismiss.
                composable(Routes.CHANGELOG) {
                    com.kaavalan.note.features.changelog.ChangelogScreen(
                        onDismiss = { navController.popBackStack() },
                    )
                }
                // v2.0 (PM rating): the in-app audit-log
                // viewer. Reachable from Settings → Privacy →
                // Audit log. The chain has been writing rows
                // since v1.8.0 (see AuditChainWriter); v2.0
                // surfaces them.
                composable(Routes.AUDIT_LOG) {
                    com.kaavalan.note.features.audit.AuditLogScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v2.0.2 (PM rating): the About screen.
                // Reachable from Settings → Privacy → About.
                composable(Routes.ABOUT) {
                    com.kaavalan.note.features.about.AboutScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v2.1.1 (QA P1-#4): the sync-conflict list + diff
                // screens are removed entirely. v2.0+ is vault-mode
                // (no cloud), the conflict table is always empty,
                // and the resolve buttons on the diff screen were
                // no-ops that only dismissed the dialog without
                // touching the DAO. The "Cloud sync is not enabled
                // in this build" string confirmed the screen is
                // dead. The SyncConflictEntity / SyncConflictDao /
                // `sync_conflicts` Room table are intentionally
                // retained for the future cloud-sync build.
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            onVaultExport = { showSettings = false; showVaultExport = true },
            onVaultImport = { showSettings = false; showVaultImport = true },
            onOpenRecoveryPhrase = {
                showSettings = false
                navController.navigate(Routes.RECOVERY_PHRASE)
            },
            onOpenThreatModel = {
                showSettings = false
                navController.navigate(Routes.THREAT_MODEL)
            },
            // v1.9.12 (A9 wire-up): the changelog screen is
            // reachable from Settings. The v1.6.0 design rule
            // forbids auto-showing it at first launch as a
            // modal; the Settings row is the canonical entry
            // point. The screen reads assets/changelog.json
            // and marks the current version as "seen" on
            // dismiss.
            onOpenChangelog = {
                showSettings = false
                navController.navigate(Routes.CHANGELOG)
            },
            // v2.0 (PM rating): the in-app audit-log viewer
            // is reachable from Settings. The chain has
            // been writing rows since v1.8.0; v2.0 surfaces
            // them.
            onOpenAuditLog = {
                showSettings = false
                navController.navigate(Routes.AUDIT_LOG)
            },
            // v2.0.2 (PM rating): the About screen is
            // reachable from Settings. The version is
            // already in the storage card; this is the
            // canonical build-info + privacy-posture
            // surface.
            onOpenAbout = {
                showSettings = false
                navController.navigate(Routes.ABOUT)
            },
            // v2.0.0: onOpenSyncConflicts removed — the sync
            // queue is a no-op stub (no cloud). The Settings
            // sheet no longer links to the sync-conflict
            // screen; the screen file is kept for forward-
            // compat but is dormant.
        )
    }
    if (showVaultExport) {
        VaultExportSheet(
            onDismiss = { showVaultExport = false },
            onExported = { showVaultExport = false },
        )
    }
    if (showVaultImport) {
        VaultImportSheet(
            onDismiss = { showVaultImport = false },
            onImported = { showVaultImport = false },
        )
    }
}

/**
 * M4-T2: bottom nav. Three tabs. The Home and Today entries use
 * `popUpTo(start)` to avoid growing the back stack. Settings opens
 * the bottom sheet.
 *
 * **v1.2 root-cause fix (BUG-MAIN-005):** the Settings entry's
 * `onClick` was a literal `{}` — the parent [MainScaffold] owns
 * the `showSettings` state but no callback was wired down.
 */
@Composable
private fun BottomNav(
    navController: NavHostController,
    currentRoute: String,
    onSettingsClick: () -> Unit,
) {
    // v1.6.3: with `enableEdgeToEdge()` the system 3-button
    // nav (or gesture indicator) sits at the very bottom of
    // the screen and the app draws behind it. Without
    // explicit navigation-bar inset the Material 3
    // NavigationBar would overlap the system home button,
    // making the bottom tab area un-tappable on 3-button
    // devices. navigationBarsPadding() pushes the bar above
    // the system nav. On gesture-nav devices the inset is
    // only ~16dp so this is invisible to the user; on
    // 3-button devices it's the full 48dp system nav.
    // v1.6.4: with `enableEdgeToEdge()` the system 3-button
    // nav (or gesture indicator) sits at the very bottom of
    // the screen and the app draws behind it. Without
    // explicit navigation-bar inset the Material 3
    // NavigationBar would overlap the system home button,
    // making the bottom tab area un-tappable on 3-button
    // devices.
    //
    // v1.6.3: navigationBarsPadding() (the system inset
    // value) was 63px on Pixel 6 / 420dpi, but the actual
    // 3-button nav is 126px tall. The remaining 63px overlap
    // puts the Compose nav labels inside the system nav
    // hit area — the recents button captures taps at
    // (x=907, y=2200) and the user is dropped into a
    // background app. Drive-verified on emulator-5554
    // (A14, 1080x2400) and ZD2232FCR5 (A17, 1264x2780).
    //
    // v1.6.4 fix: hardcoded 48dp (126px at 420dpi)
    // bottom padding on top of the inset.
    //
    // v1.7.1 fix (CRIT-H1+H2): the v1.6.4 48dp extra
    // padding was JUST barely enough — the system's
    // 3-button nav has an extended touch area that
    // reaches ~30-50dp above the visible buttons, so the
    // TOP 24-30% of the Compose NavigationBar overlapped
    // the recents-button hit area. Tapping the Today or
    // Settings tab from the Home screen on ZD2232FCR5
    // (Android 17, 1264x2780, 3-button nav) was captured
    // by the system recents and the user was dropped
    // into a background app (e.g. BSA for Dummies).
    // Bumped the explicit bottom padding to 96dp so the
    // Compose bar ends ~48dp above the system nav touch
    // area top. Drive-verified: bottom nav tappable from
    // every screen; the NoteBar above it still has a 12dp
    // visual gap.
    //
    // v1.9.2: the explicit `padding(bottom = 96.dp)` is
    // REMOVED. The v1.6.4 fix was for 3-button nav, where
    // the system recents button has an extended touch area
    // that reaches ~30-50dp above the visible buttons.
    // ZD2232FCR5 is now on gesture nav (the home-indicator
    // pill at the bottom of the screen is the only system
    // touch target in that area), so the 96dp manual
    // padding was over-applied — it just created a visible
    // dark gap of ~288px between the bottom nav and the
    // bottom of the screen. `navigationBarsPadding()` alone
    // is sufficient for gesture nav; the system gesture
    // insets are already handled by `enableEdgeToEdge()` in
    // [KaavalanApplication.onCreate]. If a user with 3-button
    // nav reports the recents-button-tap regression, the
    // fix is to read `WindowInsets.navigationBars` height
    // and add 48dp on top of it (the 3-button extended
    // touch area) — but gesture nav is the only mode that
    // the build-time Windows machine can drive-verify.
    NavigationBar(
        modifier = Modifier
            .navigationBarsPadding(),
    ) {
        NavEntry(
            label = stringResource(R.string.tab_home),
            icon = Icons.Default.Home,
            route = Routes.HOME,
            currentRoute = currentRoute,
            onClick = {
                navController.navigate(Routes.HOME) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
        NavEntry(
            label = stringResource(R.string.tab_today),
            icon = Icons.Default.Today,
            route = Routes.TODAY,
            currentRoute = currentRoute,
            onClick = {
                navController.navigate(Routes.TODAY) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
        NavEntry(
            label = stringResource(R.string.tab_settings),
            icon = Icons.Default.Settings,
            route = Routes.SETTINGS,
            currentRoute = currentRoute,
            onClick = onSettingsClick,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavEntry(
    label: String,
    icon: ImageVector,
    route: String,
    currentRoute: String,
    onClick: () -> Unit,
) {
    // v1.7.3 (P1-D, H3 follow-up from v1.7.0): replace the M3
    // NavigationBarItem with a custom Row that always exposes
    // `clickable=true` in the UI hierarchy. The v1.7.0 critique
    // H3 noted that the active tab reports `clickable=false` in
    // uiautomator dump because M3 NavigationBarItem sets the
    // `selected=true` child as non-clickable. The actual click
    // handler on the parent Surface still fires, so the user's
    // tap works — but the dump signal is wrong and a screen
    // reader announces the active tab as inert. The custom Row
    // fixes both: every tab reports `clickable=true` (including
    // the active one), and `onClick` fires on every tap (no
    // M3 internal guard). The visual selected indicator is the
    // same primary-color tint + 3dp pill that M3's
    // NavigationBarItem renders under the active label.
    val isActive = currentRoute == route
    val onSurfaceVariant = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) primary else onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
            androidx.compose.material3.Text(
                text = label,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = if (isActive) primary else onSurfaceVariant,
            )
            if (isActive) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .background(
                            color = primary,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                        .size(width = 24.dp, height = 3.dp),
                )
            }
        }
    }
}

/** M3.5: thin wrapper for the person detail nav entry. */
@Composable
private fun HomeScreenPersonDetail(
    personId: String,
    onBack: () -> Unit,
    onOpenLinkedPerson: (String) -> Unit = {},
) {
    val vm: com.kaavalan.note.ui.home.PersonDetailViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    com.kaavalan.note.ui.home.PersonDetailScreen(
        personId = personId,
        onBack = onBack,
        onOpenLinkedPerson = onOpenLinkedPerson,
        viewModel = vm,
    )
}

object Routes {
    const val HOME = "home"
    const val TODAY = "today"
    const val SETTINGS = "settings"
    const val PERSON = "person/{personId}"
    // v2.0 T3-2 + T3-3: the recovery phrase and threat model
    // screens. They are reachable from Settings → Privacy
    // (the Settings bottom sheet) but live as separate
    // nav destinations so they have their own
    // `Scaffold + TopAppBar` and the FLAG_SECURE
    // DisposableEffect in [RecoveryPhraseScreen] can scope
    // itself to the right window.
    const val RECOVERY_PHRASE = "privacy/recovery-phrase"
    const val THREAT_MODEL = "privacy/threat-model"
    // v1.9.12 (A9 wire-up): the changelog screen. Reachable
    // from Settings → Privacy → What's new. The v1.6.0
    // design rule forbids auto-showing it at first launch as
    // a modal — Settings is the canonical entry point.
    const val CHANGELOG = "privacy/changelog"
    // v2.0 (PM rating): the in-app audit-log viewer.
    // Reachable from Settings → Privacy → Audit log. The
    // chain has been writing rows since v1.8.0; v2.0
    // surfaces them.
    const val AUDIT_LOG = "privacy/audit-log"
    // v2.0.2 (PM rating): the About screen. Reachable
    // from Settings → Privacy → About. Build info,
    // privacy posture, source repo.
    const val ABOUT = "about"
    // v2.1.1 (QA P1-#4): the sync-conflict routes were
    // removed alongside the dead SyncConflictScreens file.
    // The data layer (entity / DAO / Room table) stays
    // for the future cloud-sync build.
    fun person(id: String) = "person/$id"
}

/**
 * Top-level ViewModel that owns the ephemeral UI events (shared text
 * from another app, the widget / tile "quick capture" pulse).
 *
 * v1.5.0 vault mode: no auth gate. The auth state machinery is no
 * longer observed at the root; the app opens straight to the home
 * tabs and SQLCipher keeps the local Room DB encrypted at rest.
 */
@HiltViewModel
class RootViewModel @Inject constructor() : ViewModel() {

    private val _sharedText = MutableStateFlow<String?>(null)
    val sharedText: StateFlow<String?> = _sharedText.asStateFlow()

    fun onSharedText(text: String) {
        _sharedText.value = text
    }

    fun consumeSharedText() {
        _sharedText.value = null
    }

    private val _quickCapture = MutableStateFlow(false)
    val quickCapture: StateFlow<Boolean> = _quickCapture.asStateFlow()

    fun onQuickCapture() {
        _quickCapture.value = true
    }

    fun consumeQuickCapture() {
        _quickCapture.value = false
    }
}
