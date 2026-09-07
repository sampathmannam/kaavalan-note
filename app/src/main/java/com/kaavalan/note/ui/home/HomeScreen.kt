package com.kaavalan.note.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import com.kaavalan.note.RootViewModel
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.person.toEntity
import com.kaavalan.note.features.capture.CameraLauncher
import com.kaavalan.note.features.capture.CaptureSheet
import com.kaavalan.note.features.capture.CaptureViewModel
import com.kaavalan.note.ui.hierarchy.DispatchComposerSheet
import com.kaavalan.note.features.capture.NoteBar
import com.kaavalan.note.features.capture.PhotoCapture
import com.kaavalan.note.features.capture.VoiceCaptureService
import com.kaavalan.note.features.search.SearchBar
import com.kaavalan.note.features.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * M3.5: Home tab. M3-T6 in-place routing is replaced with a real
 * `composable("person/{personId}")` entry in the parent
 * [com.kaavalan.note.MainActivity]'s NavHost. The HomeScreen no longer
 * owns the [selectedPersonId] state; it calls [onOpenPerson] to
 * trigger the nav.
 *
 * M3-T4: the settings gear in the top bar is removed (M4-T2
 * promotes Settings to a bottom-nav tab). The sheet is owned by
 * MainScaffold and is opened via [onOpenSettings].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    captureViewModel: CaptureViewModel = hiltViewModel(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val captureState by captureViewModel.state.collectAsStateWithLifecycle()
    val sharedText by rootViewModel.sharedText.collectAsStateWithLifecycle()
    val quickCapture by rootViewModel.quickCapture.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddPerson by remember { mutableStateOf(false) }
    // v2.x: non-null while the hierarchy dispatch composer is open;
    // holds the capture text carried over from the note bar.
    var dispatchInitialText by remember { mutableStateOf<String?>(null) }
    val deviceOwnerName by viewModel.deviceOwnerName.collectAsStateWithLifecycle()
    // v2.0 (Hierarchy): contact-import sheet. The IconButton next
    // to the FAB opens the contact picker. The picker handles the
    // `READ_CONTACTS` permission itself and on success calls
    // `homeViewModel.importContact(name, phone)` for each pick.
    var showContactPicker by remember { mutableStateOf(false) }
    // v2.0 (Hierarchy): the #tag chip tap has no dedicated screen
    // yet. Surface a Snackbar so the tap is observable (rather than
    // a silent no-op) and the user knows the system heard them.
    // The #tag-screen destination is on the v2.x roadmap; until then
    // this is the lowest-risk affordance.
    var selectedTagId by remember { mutableStateOf<String?>(null) }
    var showTagSnackbar by remember { mutableStateOf(false) }
    // v1.7.0: search-result → instruction detail sheet state.
    // The entity is the row that came back from FTS; the domain
    // model is what the sheet renders. We hold the entity and
    // convert on demand (cheaper than a second query).
    var selectedInstructionEntity by remember {
        mutableStateOf<com.kaavalan.note.data.local.entities.InstructionEntity?>(null)
    }
    val searchDetailViewModel: SearchResultDetailViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sharedText) {
        val text = sharedText
        if (text != null) {
            captureViewModel.onTextChanged(text)
            captureViewModel.openSheet()
            rootViewModel.consumeSharedText()
        }
    }
    LaunchedEffect(quickCapture) {
        if (quickCapture) {
            captureViewModel.openSheet()
            rootViewModel.consumeQuickCapture()
        }
    }

    val pendingUri = remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingUri.value
        if (success && uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { PhotoCapture.recognize(context, uri) }.getOrElse { "" }
                }
                if (text.isNotBlank()) {
                    captureViewModel.onPhotoTextRecognized(text)
                }
            }
        }
        pendingUri.value = null
    }

    // v1.5.4: the camera permission flow. The Photo button used to
    // fire `cameraLauncher.launch(uri)` unconditionally, which
    // crashed with `SecurityException: CAMERA permission denied` on
    // first install (the runtime perm hadn't been granted yet — the
    // v1.3 path assumed the system perm dialog appeared in the
    // `TakePicture` activity, which is not the case). The new
    // pattern: check the perm first, request it via the launcher if
    // missing, then launch the camera on grant. The same
    // `cameraLauncher.launch(uri)` only fires after the user
    // accepts the perm dialog. On denial, surface a friendly
    // message in the capture sheet (the `Microphone permission
    // denied` path is the analogue for the Voice button).
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = CameraLauncher.newCaptureUri(context)
            pendingUri.value = uri
            cameraLauncher.launch(uri)
        } else {
            captureViewModel.onPhotoError("Camera permission denied")
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            captureViewModel.onVoiceStart(context)
        } else {
            captureViewModel.onVoiceError("Microphone permission denied")
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(showTagSnackbar, selectedTagId) {
        if (showTagSnackbar && selectedTagId != null) {
            showTagSnackbar = false
            snackbarHostState.showSnackbar("Tag #$selectedTagId — detail view coming in v2.x")
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                // v1.6.3: Obsidian-style title. The default Material
                // titleLarge is too loud for a document-density
                // app; we use titleSmall with onSurface so the
                // title reads as a section label, not a banner.
                // Vertical padding trimmed to keep the top bar
                // compact. We also add an explicit start padding
                // because `windowInsets(0)` strips the leading
                // inset the TopAppBar would normally add, leaving
                // the title flush against the left edge.
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    },
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                )
                // v2.0 (Tier 1.3): the search bar below the
                // top app bar. The results show on the same
                // screen when the user types something
                // matching; when the query is empty the
                // existing PersonList renders.
                val searchViewModel: SearchViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                SearchBar(viewModel = searchViewModel)
            }
        },
        // v1.6.3: the Quick note bar moved from a floating Box
        // overlay into the Scaffold's bottomBar slot. The
        // previous floating overlay sat on top of the last
        // person row (a hard-to-reach "P..." entry was hidden
        // behind it on a 1080x2400 emulator). With the bar in
        // bottomBar the Scaffold's content slot is auto-padded
        // so the LazyColumn can scroll the last row above the
        // bar. The MainActivity's outer Scaffold still draws the
        // tab bar (Home/Today/Settings) below this bottomBar,
        // so the layout order is: LazyColumn -> Quick note
        // bar -> tab bar.
        bottomBar = {
            NoteBar(
                onTextClick = { captureViewModel.openSheet() },
                onCameraClick = {
                    // v1.5.4: gate the camera launch on the
                    // `CAMERA` runtime perm. The v1.3 path fired
                    // `cameraLauncher.launch(uri)` directly, which
                    // crashed on first install with
                    // `SecurityException` because the perm hadn't
                    // been granted yet. The system camera app
                    // does not surface the perm dialog itself
                    // (the calling app must). Mirror the
                    // mic-permission pattern: check, request if
                    // missing, launch on grant.
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        val uri = CameraLauncher.newCaptureUri(context)
                        pendingUri.value = uri
                        cameraLauncher.launch(uri)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onMicClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        captureViewModel.onVoiceStart(context)
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddPerson = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_add_person))
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
            val searchViewModel: SearchViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val query by searchViewModel.query.collectAsStateWithLifecycle()
            val results by searchViewModel.results.collectAsStateWithLifecycle()
            val personResults by searchViewModel.personResults.collectAsStateWithLifecycle()
            // v1.6.2: feed the visible people list to the search VM
            // so the people filter (new in v1.6.2) has data to work
            // with. When state is Loaded we have a list; otherwise
            // the VM sees an empty list (it will receive a non-empty
            // list on the next state change thanks to
            // `WhileSubscribed(5_000)`).
            val visiblePeople = (state as? HomeUiState.Loaded)?.persons
                ?.map { it.toEntity() }
                .orEmpty()
            LaunchedEffect(visiblePeople) {
                searchViewModel.setVisiblePeople(visiblePeople)
            }
            if (query.isNotEmpty()) {
                // v1.6.3: pass the person name map so the
                // instruction group header reads as a person
                // name (e.g. "K. Ramana"), not a truncated
                // UUID like "8bc44494-016f-4cd0-8". The map
                // is built from the same `visiblePeople` we
                // feed to SearchViewModel.
                val personNameById = remember(visiblePeople) {
                    visiblePeople.associate { it.id to it.name }
                }
                HomeScreenSearchResults(
                    personResults = personResults,
                    instructionResults = results,
                    personNameById = personNameById,
                    padding = padding,
                    onPersonClick = onOpenPerson,
                    onInstructionClick = { entity -> selectedInstructionEntity = entity },
                )
            } else {
                when (val s = state) {
                    HomeUiState.Empty -> EmptyState(
                        padding = padding,
                        onAddPersonClick = { showAddPerson = true },
                        onImportFromContacts = { showContactPicker = true },
                    )
                    HomeUiState.Loading -> LoadingSkeleton(padding)
                    is HomeUiState.Loaded -> com.kaavalan.note.ui.hierarchy.HomeHierarchyAwarePersonList(
                        persons = s.persons,
                        openCountByPersonId = s.openCountByPersonId,
                        stalePersonIds = s.stalePersonIds,
                        outgoing = s.outgoingOpen,
                        incoming = s.incomingOpen,
                        popularTags = s.popularTags,
                        padding = padding,
                        onPersonClick = onOpenPerson,
                        onTagClick = { tagId -> selectedTagId = tagId; showTagSnackbar = true },
                        onInstructionClick = { entity -> selectedInstructionEntity = entity },
                    )
                    is HomeUiState.Error -> ErrorState(s.message, padding)
                }
            }
        }

    if (showAddPerson) {
        AddPersonSheet(
            onSave = { name, designation, station ->
                scope.launch {
                    viewModel.createPerson(name, designation, station)
                }
                showAddPerson = false
            },
            onDismiss = { showAddPerson = false },
        )
    }

    if (showContactPicker) {
        com.kaavalan.note.ui.hierarchy.ContactPickerSheet(
            contactSyncService = viewModel.contactSyncService(),
            onPicked = { name, phone -> viewModel.importContact(name, phone); showContactPicker = false },
            onDismiss = { showContactPicker = false },
        )
    }

    if (captureState.isVisible) {
        CaptureSheet(
            viewModel = captureViewModel,
            onDismiss = { /* sheet closed via VM */ },
            // v1.4 (PHONE-FINDING-8): when the user has no people
            // yet, the inline "Add a person first" card on the
            // capture sheet points its "Add person" button at the
            // same entry point the Home screen uses, so the user
            // lands in the same AddPerson form. The sheet is
            // dismissed before the AddPerson sheet opens so the
            // back stack is single-step.
            onOpenAddPerson = {
                captureViewModel.dismissSheet()
                showAddPerson = true
            },
            // v2.x: the note mentions an audience (@si,
            // @station:Subedari, @all) and the user accepted the
            // suggestion. Carry the text into the dispatch composer.
            // The capture sheet has already dismissed itself.
            onOpenDispatch = { text -> dispatchInitialText = text },
        )
    }
    // v2.x: the hierarchy dispatch composer. Before this, nothing in
    // the app constructed it -- the whole flow (and
    // MentionAndTagParser behind it) shipped in v2.1.1 unreachable.
    dispatchInitialText?.let { initialText ->
        DispatchComposerSheet(
            initialText = initialText,
            senderName = deviceOwnerName,
            // UserEntity carries no designation/station today; the
            // composer treats both as optional.
            senderDesignation = null,
            senderDivision = null,
            onDismiss = { dispatchInitialText = null },
            onSaved = { dispatchInitialText = null },
        )
    }
    // v1.7.0: tapping an instruction in search results now
    // opens the instruction detail sheet directly (was: nav
    // to the person screen). The sheet uses the same shared
    // component as TodayScreen and the same 1-arg
    // markDone / markDropped / reopen helpers on
    // RoomInstructionRepository that TodayViewModel uses,
    // so the search-result transition ends up in the
    // sync-engine outbox exactly as a transition from the
    // brief would.
    selectedInstructionEntity?.let { entity ->
        val ins = entity.toDomain()
        com.kaavalan.note.ui.components.InstructionDetailSheet(
            instruction = ins,
            onDismiss = { selectedInstructionEntity = null },
            onMarkDone = {
                searchDetailViewModel.markDone(ins)
                selectedInstructionEntity = null
            },
            onDrop = {
                searchDetailViewModel.markDropped(ins)
                selectedInstructionEntity = null
            },
            onReopen = {
                searchDetailViewModel.reopen(ins)
                selectedInstructionEntity = null
            },
        )
    }
}

@Composable
private fun EmptyState(
    padding: PaddingValues,
    onAddPersonClick: () -> Unit,
    onImportFromContacts: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // v1.6.3: dropped headlineMedium (24sp, too loud
            // for a document-density app). Now headlineSmall
            // (20sp) reads as a quiet section header.
            Text(
                text = stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // v1.4 (PHONE-FINDING-1): the FAB in the Scaffold above
            // uses primaryContainer which is too low-contrast against
            // the dark surface — new users miss the entry point. The
            // empty-state copy now carries its own prominent primary-
            // coloured "Add person" button so the first thing a
            // brand-new user sees gives them an obvious action. The
            // FAB is still present (so power users have a constant
            // anchor), but the empty-state button is the
            // first-impression entry point. The button uses
            // colorScheme.primary (not primaryContainer) so it stands
            // out against both the dark and the light surface.
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAddPersonClick,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.home_add_person),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            // v2.0 (Hierarchy): alternate entry — import from the
            // device's contact list. Shown in the empty state
            // (where first-impression matters most) but not on the
            // main FAB so the keyboard-first path stays the default.
            OutlinedButton(
                onClick = onImportFromContacts,
                modifier = Modifier.semantics { contentDescription = "Import from contacts" },
            ) {
                Text(stringResource(R.string.hierarchy_contact_sync_open_picker))
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun PersonList(
    persons: List<Person>,
    openCountByPersonId: Map<String, Int>,
    stalePersonIds: Set<String>,
    padding: PaddingValues,
    onPersonClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        // v1.6.3: 16dp horizontal + 8dp vertical contentPadding.
        // v1.6.4: bottom 88dp clears the Material 3 FAB
        // (56dp + 16dp margin) + 16dp visual buffer so the
        // last row's count badge is not hidden behind the
        // FAB. The end padding is the same — but a wider
        // contentPadding is applied to the rightmost column
        // in the row layout (see [PersonRow]) so the row's
        // content doesn't extend under the FAB horizontally.
        // The horizontal padding here means each row's
        // clickable hit-target extends to the screen edges
        // (better UX than rows that stop short of the edge).
        // v1.7.4 (P1-C): bottom 88dp → 112dp. The Quick
        // note bar (NoteBar) is the Scaffold's bottomBar and
        // measures ~96dp (Surface vertical padding 12dp +
        // Row height 72dp + 12dp), which is 8dp taller than
        // the FAB-only clearance. The Scaffold's content
        // `padding` should already account for the bottomBar
        // height, but in practice the NoteBar's rounded-
        // corner clip + the FAB overhang left the last row
        // half-hidden behind the bar on a 1080x2400
        // viewport (see `ui_v174_search_clip` repro). 112dp
        // = 96dp NoteBar + 16dp visual buffer. Verified
        // that the Search results code path also bumps to
        // 112dp below.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 112.dp,
        ),
    ) {
        items(items = persons, key = { it.id }) { person ->
            PersonRow(
                person = person,
                openCount = openCountByPersonId[person.id] ?: 0,
                isStale = person.id in stalePersonIds,
                onClick = { onPersonClick(person.id) },
            )
        }
    }
}

/**
 * v2.0 (Tier 1.3): the search results. Renders two sections —
 * "People" (from [personResults]) and "Instructions" (from
 * [instructionResults]) — so the placeholder "Search people
 * and instructions" is honest. v1.6.2 added the People section;
 * the Instructions section keeps the per-person grouping from
 * v1.6.0.1. v1.6.3: [personNameById] resolves the instruction's
 * `personId` UUID to a real name so each instruction group is
 * headed by the person's name, not a truncated UUID.
 */
@Composable
fun HomeScreenSearchResults(
    personResults: List<com.kaavalan.note.data.local.entities.PersonEntity>,
    instructionResults: List<com.kaavalan.note.data.local.entities.InstructionEntity>,
    personNameById: Map<String, String>,
    padding: PaddingValues,
    onPersonClick: (String) -> Unit,
    // v1.7.0: tapping an instruction in search results now
    // opens the instruction detail sheet. The previous
    // behaviour was to navigate to the person (which is
    // reachable via the person-header tap above each
    // instruction group), so this split is non-breaking.
    onInstructionClick: (com.kaavalan.note.data.local.entities.InstructionEntity) -> Unit,
) {
    if (personResults.isEmpty() && instructionResults.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.search_no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        // v1.6.3: 16dp horizontal + 88dp bottom contentPadding
        // to clear the FAB (56dp + 16dp margin) + 16dp visual
        // buffer. Same reason as PersonList — the FAB
        // overlaps the last row otherwise.
        // v1.7.4 (P1-C): bottom 88dp → 112dp. The Quick note
        // bar (NoteBar, ~96dp tall) is the Scaffold's
        // bottomBar and is 8dp taller than the FAB-only
        // clearance. 112dp = 96dp NoteBar + 16dp visual
        // buffer. See the PersonList comment for the
        // full repro details.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 112.dp,
        ),
    ) {
        if (personResults.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.search_section_people),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(items = personResults, key = { it.id }) { person ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = "Open person",
                            onClick = { onPersonClick(person.id) },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val sub = listOfNotNull(person.designation, person.station)
                        .joinToString(" · ")
                    if (sub.isNotBlank()) {
                        // v1.7.4 (P1-A): maxLines=2 + Ellipsis. Same
                        // reason as PersonRow's subtitle — the
                        // unbounded Text would wrap to N lines and
                        // break words mid-character at hyphens
                        // ("mobi|le-screens") for long designations or
                        // the stress-test station name. 2 lines gives
                        // the search result enough context (a real
                        // station like "District Traffic Wing,
                        // Warangal" fits in 1 line) without letting a
                        // single bad row eat the viewport. The
                        // trailing "..." tells the user there is
                        // more on tap.
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (instructionResults.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.search_section_instructions),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val byPerson = instructionResults.groupBy { it.personId }
            byPerson.forEach { (personId, list) ->
                item {
                    Text(
                        text = if (personId == null) {
                            stringResource(R.string.search_unassigned)
                        } else {
                            // v1.6.3: resolve UUID to a real name via
                            // the visible people map (passed in by
                            // the caller). Falls back to "(unknown
                            // person)" if the id is not in the
                            // visible set (e.g. a sensitive person
                            // that's currently hidden).
                            personNameById[personId]
                                ?: "(unknown person)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // v1.7.3 (P2-C): `heightIn(min = 32.dp)` so
                        // the (unknown person) / unassigned header
                        // never gets clipped to a few px when it lands
                        // at the very bottom of the visible
                        // LazyColumn. The same `heightIn(min=88.dp)`
                        // pattern on PersonRow (v1.7.2) and
                        // DecayRow (v1.7.3 P2-A) used `Card`; this
                        // header is a plain Text, so the 32dp minimum
                        // is the smallest value that still keeps the
                        // labelSmall glyphs legible.
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .heightIn(min = 32.dp),
                    )
                }
                items(items = list, key = { it.id }) { ins ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                // v1.7.0: tap on the instruction
                                // row opens the instruction
                                // detail sheet. Tap on the
                                // person-header above still
                                // navigates to the person.
                                onClickLabel = "Open instruction",
                                onClick = { onInstructionClick(ins) },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = ins.title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        // v1.6.2: skip the body when it duplicates the
                        // title (v1.6.1 capture stores a single line in
                        // both). Same fix as TodayScreen.InstructionCard.
                        if (ins.title != ins.rawText) {
                            Text(
                                text = ins.rawText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/**
 * v1.5.3 (VAULT-009): the loading skeleton. Three quiet
 * placeholder rows that look like real person rows but are
 * just grey rectangles. The user sees something happen on
 * screen from the first frame, instead of a blank white
 * section that looks like a crash.
 *
 * We use the project's spec §3 colour token (surfaceVariant)
 * for the placeholder fill so it works on both light and dark
 * themes without an explicit theme check.
 */
@Composable
private fun LoadingSkeleton(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        repeat(4) { index ->
            SkeletonRow()
            if (index < 3) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Title placeholder — wider bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(16.dp),
            ) {}
            // Subtitle placeholder — narrower bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(12.dp),
            ) {}
        }
    }
}

@Composable
private fun PersonRow(person: Person, openCount: Int, isStale: Boolean, onClick: () -> Unit) {
    // v1.3 (F-19): the row is a clickable list item — TalkBack needs
    // to know it opens the person detail screen. onClickLabel replaces
    // the generic "double-tap to activate" with the action name.
    val openPersonLabel = stringResource(R.string.a11y_person_row_open)
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openPersonLabel) { onClick() }
            // v1.6.3: 8dp vertical, no horizontal padding (the
            // LazyColumn's contentPadding handles horizontal).
            // Smaller vertical padding = denser list = more
            // people on screen = Obsidian document density.
            .padding(vertical = 10.dp)
            // v1.7.2 (P1-B): when a person has no designation +
            // station, the inner Column only renders the name
            // (no sub-text). Without a minHeight the row's
            // height collapses to ~72 px, which under
            // v1.6.4's 88dp contentPadding + 96dp bottom-nav
            // lift clipped the seventh row's name to h=14 in
            // the visible viewport (see
            // `ui_v172_home.xml`). 88dp = 10dp top + 49dp name
            // line + 10dp bottom + a 19dp buffer so the name
            // TextView never gets the parent-clip on a
            // 7-row initial viewport.
            .heightIn(min = 88.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    person.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // M4-T3: stale surface. After 3 days of no activity on
                // an OUTGOING instruction (spec §8.2), the row shows
                // a quiet amber dot. Not red, not a count-up — just
                // "this has been quiet".
                if (isStale) {
                    Spacer(Modifier.size(8.dp))
                    // v1.3 (F-19): the stale dot is a pure visual
                    // cue; TalkBack should announce what it means
                    // (no activity for 3+ days), not "dot".
                    val staleDesc = stringResource(R.string.a11y_person_stale_indicator)
                    Surface(
                        modifier = Modifier
                            .size(8.dp)
                            .semantics { contentDescription = staleDesc },
                        // v1.6.8: theme-aware stale dot. The old
                        // `0xFFD9A05B` was too dim against the
                        // dark `surfaceVariant` (0xFF2F2A23) so
                        // the dot disappeared. The light/dark
                        // pair keeps it visible in both modes.
                        color = com.kaavalan.note.ui.theme.KaavalanThemeTokens.staleIndicator(),
                        contentColor = androidx.compose.ui.graphics.Color.Transparent,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ) {}
                }
            }
            val sub = listOfNotNull(person.designation, person.station).joinToString(" • ")
            if (sub.isNotEmpty()) {
                // v1.7.4 (P1-A): maxLines=1 + Ellipsis. The previous
                // unbounded Text wrapped to N lines (and broke words
                // mid-character at hyphens, e.g. "mobi|le-screens")
                // when a long designation or a stress-test station
                // name like "Station-with-a-very-long-name-..."
                // overflowed. The row is a tap-target to the detail
                // screen — 1 line + ellipsis is the right shape for
                // a list item, and the "..." tells the user there
                // is more on tap (which the existing row-level
                // a11y label "Open person" already announces).
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (openCount > 0) {
            // v1.3 (F-19): the count badge. TalkBack would read
            // "3" without context; the semantics modifier
            // replaces that with "3 open instructions".
            // v1.6.3: dropped the prominent tertiaryContainer
            // CircleShape pill — it was the loudest element on the
            // row and pulled the eye away from the name. Now a
            // small labelMedium in onSurfaceVariant sits at the
            // row's right; the badge recedes and the row reads
            // as a document line (Obsidian-style).
            // v1.7.1 (P1 T3): added a visible "open" label
            // after the count so the user can read "3 open"
            // without needing TalkBack. The visible label
            // matches the a11y contentDescription exactly so
            // the meaning is consistent on screen and in
            // the screen reader.
            val countDesc = if (openCount == 1) {
                stringResource(R.string.a11y_person_count_badge_one)
            } else {
                stringResource(R.string.a11y_person_count_badge, openCount)
            }
            val openLabel = stringResource(R.string.today_count_open_short)
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 12.dp, end = 4.dp)
                    .semantics { contentDescription = countDesc },
            ) {
                Text(
                    text = openCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = " $openLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
