package com.kaavalan.note

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kaavalan.note.data.preferences.KaavalanPreferences
import com.kaavalan.note.data.undo.UndoController
import com.kaavalan.note.features.capture.ShareIntake
import com.kaavalan.note.features.capture.ocrTextOrEmpty
import com.kaavalan.note.features.onboarding.OnboardingScreen
import com.kaavalan.note.features.theme.appDarkTheme
import com.kaavalan.note.ui.theme.KaavalanNoteTheme
import com.kaavalan.note.ui.workspace.*
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * gates the [MainScaffold]; theme is resolved by [appDarkTheme]
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
            consumeReminderIntent(intent)
        }
        briefNotifier.schedule()
        setContent {
            KaavalanNoteTheme(darkTheme = appDarkTheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val seen by preferences.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = null)
                    if (seen == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (seen == false) {
                        OnboardingScreen(onDone = { /* DataStore flips; recomposition picks it up */ })
                    } else {
                        OfficerAppRoot(
                            rootViewModel = rootViewModel,
                            undoController = undoController,
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
        consumeReminderIntent(intent)
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
                // v2.2.1: OCR the shared image here.
                //
                // This branch used to be empty, on the reasoning that
                // the "receiver activity already OCR'd" it. No receiver
                // activity runs. The share target in the manifest is an
                // `<activity-alias>` that happens to be *named*
                // `.features.capture.ShareReceiverActivity` but declares
                // `android:targetActivity=".MainActivity"`, so the share
                // sheet launches this activity directly with the
                // original SEND intent. The `ShareReceiverActivity`
                // class is never declared as an `<activity>` and never
                // instantiated.
                //
                // The alias advertises `image/*`, so Kaavalan note
                // appears in the share sheet for photos. Picking it
                // opened the app and did nothing at all: no OCR, no
                // pre-fill, no message. For a photo of a written
                // instruction -- the case the OCR exists for -- the
                // whole content was dropped.
                //
                // `ocrTextOrEmpty` is used rather than
                // `PhotoCapture.recognize` because the URI belongs to
                // the sending app: it can be revoked, cloud-only, or a
                // format ML Kit cannot decode, and the raw call throws
                // in all three cases. An empty pre-fill is the
                // documented fallback; a crash is not.
                val uri = payload.uri
                lifecycleScope.launch {
                    rootViewModel.onSharedText(ocrTextOrEmpty(applicationContext, uri))
                }
            }
        }
    }

    private fun consumeReminderIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_REMINDER) return
        intent.getStringExtra(EXTRA_REMINDER_INSTRUCTION_ID)
            ?.let(rootViewModel::onOpenReminder)
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

    companion object {
        const val ACTION_OPEN_REMINDER = "com.kaavalan.note.action.OPEN_REMINDER"
        const val EXTRA_REMINDER_INSTRUCTION_ID = "reminder_instruction_id"
    }
}



object Routes {
    const val CONTACTS = "contacts"
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

    private val _pendingReminderInstructionId = MutableStateFlow<String?>(null)
    val pendingReminderInstructionId: StateFlow<String?> =
        _pendingReminderInstructionId.asStateFlow()

    fun onOpenReminder(instructionId: String) {
        _pendingReminderInstructionId.value = instructionId
    }

    fun consumeReminderInstruction() {
        _pendingReminderInstructionId.value = null
    }
}
