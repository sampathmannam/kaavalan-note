package com.kaavalan.note.features.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.RootViewModel
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.ui.hierarchy.DispatchComposerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CaptureActions(val text: () -> Unit, val photo: () -> Unit, val voice: () -> Unit)

/** Activity-scoped capture and delivery of one-shot results, independent of the selected tab. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CaptureHost(
    viewModel: CaptureViewModel,
    root: RootViewModel,
    contacts: List<Person>,
    snackbar: SnackbarHostState,
    privateMode: Boolean = false,
    workspaceReady: Boolean = true,
    senderName: String = "",
    content: @Composable (CaptureActions) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shared by root.sharedText.collectAsStateWithLifecycle()
    val quick by root.quickCapture.collectAsStateWithLifecycle()
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var dispatchText by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(contacts, privateMode, workspaceReady) {
        viewModel.onWorkspaceChanged(contacts.mapTo(mutableSetOf()) { it.id }, privateMode, workspaceReady)
    }

    LaunchedEffect(shared) {
        shared?.let {
            // Preserve an existing draft when a second capture arrives.
            viewModel.onTextChanged(listOf(state.text, it).filter(String::isNotBlank).joinToString("\n\n"))
            viewModel.openSheet()
            root.consumeSharedText()
        }
    }
    LaunchedEffect(quick) { if (quick) { viewModel.openSheet(); root.consumeQuickCapture() } }
    LaunchedEffect(viewModel) { viewModel.infoMessages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) {
        viewModel.calendarIntents.collect { event ->
            runCatching { context.startActivity(CalendarGate.toIntent(event)) }
                .onFailure { snackbar.showSnackbar("Note saved. No calendar app could open this reminder.") }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = photoUri?.let(Uri::parse)
        photoUri = null
        if (success && uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { PhotoCapture.recognize(context, uri) }.getOrDefault("") }
            if (text.isBlank()) viewModel.onPhotoError("No readable text found. Try another photo or type your note.")
            else viewModel.onPhotoTextRecognized(listOf(state.text, text).filter(String::isNotBlank).joinToString("\n\n"))
        }
    }
    val launchCamera: () -> Unit = {
        runCatching {
            val uri = CameraLauncher.newCaptureUri(context)
            photoUri = uri.toString()
            camera.launch(uri)
        }.onFailure { viewModel.onPhotoError("Camera unavailable. You can still type your note.") }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) launchCamera() else viewModel.onPhotoError("Allow camera access in Android Settings, or type your note.")
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.openSheet()
        if (it) viewModel.onVoiceStart(context) else viewModel.onVoiceError("Allow microphone access in Android Settings, or type your note.")
    }
    val actions = CaptureActions(
        text = viewModel::openSheet,
        photo = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        },
        voice = {
            viewModel.openSheet()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.onVoiceStart(context)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
    )
    content(actions)
    if (state.isVisible) CaptureSheet(
        viewModel = viewModel, contacts = contacts, eventsHandledByHost = true,
        onDismiss = viewModel::dismissSheet,
        onOpenDispatch = { dispatchText = it },
    )
    dispatchText?.let { text ->
        DispatchComposerSheet(initialText = text, senderName = senderName, senderDesignation = null, senderDivision = null,
            onDismiss = { dispatchText = null }, onSaved = { dispatchText = null; viewModel.clearDraft() })
    }
}
