package com.kaavalan.note.ui.subdivision

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.subdivision.Matter
import com.kaavalan.note.data.subdivision.Station
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tablets get a readable column rather than a full-bleed line of text. */
internal val ReadableColumn = Modifier.widthIn(max = 840.dp)

/**
 * The labeled secondary action that opens a CRM destination from Today, Instructions or
 * Contacts. A row with a real label, supporting text and a forward chevron — not a third
 * unlabeled icon crowded into the top app bar.
 */
@Composable
fun SubdivisionEntryRow(
    label: String,
    supporting: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().testTag(testTag),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The common frame for every CRM destination: a native top app bar with Back, and the
 * three distinct non-content states (loading, unavailable in the private workspace, and a
 * recoverable read error) handled once so no screen forgets one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubdivisionScaffold(
    title: String,
    state: SubdivisionUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        // The destination fills the screen and carries no bottom navigation bar of its own,
        // so it has to keep its own content clear of the system navigation bar. Without
        // this the last row of a list - which is where the primary action of the review
        // screen sits - renders under the gesture bar, where taps go to the system.
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.testTag("subdivision_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("subdivision_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> Column(
                    Modifier.padding(48.dp).testTag("subdivision_loading"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Opening your subdivision record…", style = MaterialTheme.typography.bodyMedium)
                }
                !state.available -> SubdivisionNoticeScreen(
                    title = "Not available in the private workspace",
                    body = "Your subdivision, stations, staff and matters belong to the normal workspace. " +
                        "Switch back to the normal workspace to open them.",
                    action = "Back",
                    onAction = onBack,
                    testTag = "subdivision_unavailable",
                )
                state.error != null -> SubdivisionNoticeScreen(
                    title = "Could not open your subdivision record",
                    body = state.error,
                    action = "Try again",
                    onAction = onRetry,
                    testTag = "subdivision_error",
                )
                else -> content(padding)
            }
        }
    }
}

@Composable
fun SubdivisionNotice(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
    testTag: String = "subdivision_notice",
) {
    Column(
        ReadableColumn.fillMaxWidth().padding(24.dp).testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

/**
 * The same notice occupying a whole destination, in its own scrolling column so the action
 * stays reachable at a large system font.
 *
 * Use [SubdivisionNotice] directly for an empty state inside a list. A lazy list measures
 * its items with unbounded height, so a scroll container nested in one is a measurement
 * error, not a style choice.
 */
@Composable
fun SubdivisionNoticeScreen(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
    testTag: String = "subdivision_notice",
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SubdivisionNotice(title, body, action, onAction, testTag)
    }
}

/**
 * A refused save. Shown inline, in the calm surface colours the rest of the app uses —
 * never a red alert — because the message is an instruction about what to do next.
 */
@Composable
fun MutationErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("subdivision_mutation_error"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Got it") }
        }
    }
}

@Composable
fun SubdivisionSearch(query: String, onQuery: (String) -> Unit, hint: String, testTag: String = "subdivision_search") {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag(testTag),
        label = { Text(hint) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                }
            }
        } else {
            null
        },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
fun SubdivisionSectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.semantics { heading() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A list row with a label, supporting lines and a chevron. Used by the station, staff and matter lists. */
@Composable
fun SubdivisionListRow(
    headline: String,
    supporting: List<String>,
    onClick: () -> Unit,
    testTag: String,
    trailing: String? = null,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp).testTag(testTag),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                supporting.filter { it.isNotBlank() }.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A searchable chooser. Every place the officer picks a station, a matter, an officer or a
 * review scope uses this, so none of them degrades into an unbounded menu or a wall of
 * chips as the record grows.
 */
@Composable
fun <T> SearchableChooser(
    title: String,
    supporting: String?,
    items: List<T>,
    label: (T) -> String,
    detail: (T) -> String?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    emptyMessage: String,
    leadingOption: Pair<String, () -> Unit>? = null,
    searchHint: String = "Search",
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(items, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            items
        } else {
            items.filter { item ->
                val haystack = listOfNotNull(label(item), detail(item)).joinToString(" ")
                needle.split(Regex("\\s+")).all { haystack.contains(it, ignoreCase = true) }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(searchHint) },
                    modifier = Modifier.fillMaxWidth().testTag("chooser_search"),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    leadingOption?.let { (text, action) ->
                        item {
                            ListItem(
                                headlineContent = { Text(text) },
                                modifier = Modifier.clickable(onClick = action).testTag("chooser_leading"),
                            )
                        }
                    }
                    if (matches.isEmpty()) {
                        item {
                            Text(
                                emptyMessage,
                                Modifier.padding(vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(matches) { item ->
                        ListItem(
                            headlineContent = { Text(label(item)) },
                            supportingContent = detail(item)?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            modifier = Modifier.clickable { onPick(item) }.testTag("chooser_item_${label(item)}"),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun StationChooser(
    stations: List<Station>,
    includeSubdivisionWide: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) = SearchableChooser(
    title = "Choose a station or unit",
    supporting = "Archived stations are not listed. Reopen one from Stations & staff if you need it.",
    items = stations.filterNot { it.archived },
    label = { it.name },
    detail = { it.kind },
    onPick = { onPick(it.id) },
    onDismiss = onDismiss,
    emptyMessage = "No matching station or unit. Add one from Stations & staff.",
    leadingOption = if (includeSubdivisionWide) "Subdivision-wide (no station)" to { onPick(null) } else null,
    searchHint = "Search stations and units",
)

@Composable
fun MatterChooser(
    matters: List<Matter>,
    stationNameOf: (String?) -> String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) = SearchableChooser(
    title = "Choose a matter",
    supporting = "Group related instructions and their history.",
    items = matters.filterNot { it.archived },
    label = { it.title },
    detail = { listOfNotNull(it.reference.takeIf(String::isNotBlank), stationNameOf(it.stationId)).joinToString(" · ") },
    onPick = { onPick(it.id) },
    onDismiss = onDismiss,
    emptyMessage = "No matching matter. Add one from Matters.",
    leadingOption = "No matter" to { onPick(null) },
    searchHint = "Search matters",
)

@Composable
fun OfficerChooser(staff: List<Person>, onPick: (Person) -> Unit, onDismiss: () -> Unit) = SearchableChooser(
    title = "Choose an officer",
    supporting = null,
    items = staff,
    label = { it.name },
    detail = {
        listOfNotNull(it.designation, it.station, if (it.staffActive) null else "Posting inactive").joinToString(" · ")
    },
    onPick = onPick,
    onDismiss = onDismiss,
    emptyMessage = "No matching staff officer.",
    searchHint = "Search staff",
)

/** Dates are shown to the officer, never a raw ISO string. Unparseable input falls back to itself. */
fun formatDayTime(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault(iso)

fun formatDay(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault(iso)

/**
 * The staff block on an ordinary contact's detail screen: their current posting,
 * responsibilities, and the entries into the staff editor and a review scoped to them.
 *
 * Shown only for a contact the officer has explicitly classified as staff. An unclassified
 * contact stays an ordinary contact and gets a single quiet invitation instead, so the
 * Contacts tab never implies that everyone in it is a subordinate.
 */
@Composable
fun ContactStaffSection(
    person: Person,
    stationName: String?,
    postings: List<com.kaavalan.note.data.subdivision.StaffPosting>,
    onOpenStaff: () -> Unit,
    onReviewOfficer: () -> Unit,
) {
    var showHistory by rememberSaveable(person.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("contact_staff_section"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!person.isStaff) {
                Text("Subdivision staff", style = MaterialTheme.typography.titleSmall)
                Text(
                    "This is an ordinary contact. Marking them as staff records their posting and " +
                        "responsibilities in your subdivision; it does not create an account for them and " +
                        "sends them nothing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onOpenStaff,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("contact_add_to_staff"),
                ) {
                    Text("Add to staff")
                }
                return@Column
            }
            Text("Staff posting", style = MaterialTheme.typography.titleSmall)
            Text(
                stationName ?: "No station assigned",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("contact_staff_station"),
            )
            Text(
                if (person.staffActive) "Posting active" else "Posting inactive. History stays available.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Responsibilities", style = MaterialTheme.typography.titleSmall)
            Text(
                person.responsibilities.takeIf { it.isNotBlank() } ?: "No responsibilities recorded yet.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("contact_staff_responsibilities"),
            )
            if (postings.isNotEmpty()) {
                TextButton(
                    onClick = { showHistory = !showHistory },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("contact_staff_history_toggle"),
                ) {
                    Text(if (showHistory) "Hide posting history" else "Posting history (${postings.size})")
                }
                if (showHistory) {
                    postings.forEach { posting ->
                        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                formatDay(posting.recordedAt),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${posting.fromStation.ifBlank { "No station" }} → " +
                                    posting.toStation.ifBlank { "No station" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                posting.note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenStaff,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("contact_edit_staff"),
                ) {
                    Text("Edit staff details")
                }
                TextButton(
                    onClick = onReviewOfficer,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("contact_review_officer"),
                ) {
                    Text("Review this officer")
                }
            }
        }
    }
}
