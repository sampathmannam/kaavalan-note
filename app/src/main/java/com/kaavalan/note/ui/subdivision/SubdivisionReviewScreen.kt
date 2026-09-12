package com.kaavalan.note.ui.subdivision

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.subdivision.ReviewFilter
import com.kaavalan.note.data.subdivision.SubdivisionProjections
import com.kaavalan.note.ui.workspace.WorkCard
import java.time.LocalDate

/** One selectable review scope: the whole subdivision, one station / unit, or one staff officer. */
data class ScopeOption(val key: String, val label: String, val detail: String?)

fun scopeOptions(state: SubdivisionUiState): List<ScopeOption> = buildList {
    add(ScopeOption(SubdivisionProjections.SCOPE_ALL, "Whole subdivision", null))
    state.stations.filterNot { it.archived }.forEach {
        add(ScopeOption(SubdivisionProjections.stationScope(it.id), it.name, it.kind))
    }
    state.contacts.filter { it.isStaff }.forEach { person ->
        add(
            ScopeOption(
                SubdivisionProjections.personScope(person.id),
                person.name,
                listOfNotNull(
                    person.designation,
                    person.station,
                    if (person.staffActive) null else "Posting inactive",
                ).joinToString(" · ").ifBlank { null },
            ),
        )
    }
}

fun scopeLabel(state: SubdivisionUiState, scopeKey: String): String =
    scopeOptions(state).firstOrNull { it.key == scopeKey }?.label ?: "Whole subdivision"

fun filterLabel(filter: ReviewFilter): String = when (filter) {
    ReviewFilter.OPEN -> "Open"
    ReviewFilter.READY_TO_VERIFY -> "Ready to verify"
    ReviewFilter.DEADLINE_PASSED -> "Deadline passed"
    ReviewFilter.NO_UPDATE_7_DAYS -> "No update in 7 days"
    ReviewFilter.CHANGED_SINCE_LAST_REVIEW -> "Changed since last review"
}

/** Exactly what each filter measures, said in words next to the list it produces. */
fun filterExplanation(filter: ReviewFilter): String = when (filter) {
    ReviewFilter.OPEN -> "Everything not yet completed or closed."
    ReviewFilter.READY_TO_VERIFY -> "Reported complete, waiting for you to verify. Still counted as open."
    ReviewFilter.DEADLINE_PASSED -> "Open work whose recorded deadline is in the past."
    ReviewFilter.NO_UPDATE_7_DAYS ->
        "Open work with no recorded update for seven days or more, measured from its last update, " +
            "or from when it was created if it has none."
    ReviewFilter.CHANGED_SINCE_LAST_REVIEW ->
        "Anything updated after the date of your last recorded review for this scope, including work " +
            "completed since then."
}

/**
 * The subdivision review home. A scrollable, vertically ordered surface — profile, scope,
 * a short textual summary, filters, the work itself, then the review record — rather than
 * charts or a wall of coloured metric cards.
 */
@Composable
fun SubdivisionReviewScreen(
    state: SubdivisionUiState,
    busy: Boolean,
    mutationError: String?,
    today: LocalDate,
    nowMs: Long,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSaveProfile: (String, String, String, () -> Unit) -> Unit,
    onRecordReview: (String, String, () -> Unit) -> Unit,
    onOpenInstruction: (String) -> Unit,
    // Hoisted so "Review this station" / "Review this officer" from a detail screen can
    // set the scope before navigating here, and so the choice survives recreation.
    scopeKey: String,
    onScopeKeyChange: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ReviewFilter.OPEN) }
    var choosingScope by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }
    var recordingReview by remember { mutableStateOf(false) }
    var showAllHistory by rememberSaveable { mutableStateOf(false) }

    SubdivisionScaffold("Subdivision review", state, onBack, onRetry) {
        // A scope that has since been archived or unclassified must not silently widen to
        // the whole subdivision without saying so; fall back and let the officer re-pick.
        val options = scopeOptions(state)
        val effectiveScope = if (options.any { it.key == scopeKey }) scopeKey else SubdivisionProjections.SCOPE_ALL
        val scoped = remember(state.instructions, effectiveScope) {
            SubdivisionProjections.inScope(state.instructions, effectiveScope)
        }
        val counts = remember(scoped) { SubdivisionProjections.counts(scoped) }
        val reviews = remember(state.reviews, effectiveScope) {
            state.reviews.filter { it.scopeKey == effectiveScope }
        }
        val lastReview = reviews.firstOrNull()
        val lastReviewAt = SubdivisionProjections.instantOrNull(lastReview?.recordedAt)
        val results = remember(scoped, filter, nowMs, lastReviewAt) {
            SubdivisionProjections.applyFilter(scoped, filter, nowMs, lastReviewAt)
        }

        LazyColumn(
            ReadableColumn.fillMaxSize().testTag("subdivision_review_list"),
            contentPadding = PaddingValues(0.dp, 0.dp, 0.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MutationErrorBanner(mutationError, onDismissError) }
            item {
                ProfileHeader(
                    state = state,
                    busy = busy,
                    onEdit = { editingProfile = true },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubdivisionSectionHeading("Scope", "Choose what this review covers.")
                    OutlinedButton(
                        onClick = { choosingScope = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review_scope_chooser"),
                    ) {
                        Text(options.firstOrNull { it.key == effectiveScope }?.label ?: "Whole subdivision")
                    }
                    Text(
                        "${counts.open} open · ${counts.readyToVerify} ready to verify",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("review_summary"),
                    )
                    Text(
                        "Counts cover only the records available in this workspace right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewFilterRow(filter) { filter = it }
                    Text(
                        filterExplanation(filter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (filter == ReviewFilter.CHANGED_SINCE_LAST_REVIEW && lastReview == null) {
                item {
                    SubdivisionNotice(
                        title = "No earlier review for this scope",
                        body = "You have not recorded a review here yet, so there is no date to compare against. " +
                            "Record one below and this filter will work from then on.",
                        action = null,
                        onAction = {},
                        testTag = "review_no_prior",
                    )
                }
            } else if (results.isEmpty()) {
                item {
                    SubdivisionNotice(
                        title = "Nothing in this view",
                        body = "No instruction in this scope matches \"${filterLabel(filter)}\" right now.",
                        action = null,
                        onAction = {},
                        testTag = "review_empty",
                    )
                }
            } else {
                item {
                    Text(
                        "${results.size} ${if (results.size == 1) "instruction" else "instructions"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(results, key = { "review:${it.id}" }) { item ->
                    Column(
                        Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WorkCard(item, state.contacts, today, onClick = { onOpenInstruction(item.id) })
                        RecordedContextLine(state, item)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    SubdivisionSectionHeading(
                        "Review record",
                        "Your dated notes for this scope. Recording a review never changes any instruction.",
                    )
                    Text(
                        lastReview?.let { "Last recorded ${formatDayTime(it.recordedAt)}" }
                            ?: "No review recorded for this scope yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("review_last"),
                    )
                    Button(
                        onClick = { recordingReview = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review_record"),
                    ) {
                        Text("Record review")
                    }
                }
            }
            val history = if (showAllHistory) reviews else reviews.take(3)
            items(history, key = { "history:${it.id}" }) { review ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                        .testTag("review_history_${review.id}"),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(formatDayTime(review.recordedAt), style = MaterialTheme.typography.labelLarge)
                        Text(review.scopeTitle, style = MaterialTheme.typography.titleSmall)
                        Text(review.notes, style = MaterialTheme.typography.bodyLarge)
                        // Explicitly labeled: a stored count is a record of that moment, not
                        // a live number, and must never be read as one.
                        Text(
                            "At this review · ${review.openCount} open · ${review.readyCount} ready to verify",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (reviews.size > 3) {
                item {
                    TextButton(
                        onClick = { showAllHistory = !showAllHistory },
                        modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp),
                    ) {
                        Text(if (showAllHistory) "Show fewer reviews" else "See all ${reviews.size} reviews")
                    }
                }
            }
        }

        if (choosingScope) {
            SearchableChooser(
                title = "Review scope",
                supporting = "The whole subdivision, one station or unit, or one staff officer.",
                items = options,
                label = { it.label },
                detail = { it.detail },
                onPick = { onScopeKeyChange(it.key); choosingScope = false },

                onDismiss = { choosingScope = false },
                emptyMessage = "No matching station, unit or staff officer.",
                searchHint = "Search stations and staff",
            )
        }
        if (editingProfile) {
            SubdivisionProfileEditor(
                profile = state.profile,
                busy = busy,
                onDismiss = { editingProfile = false },
                onSave = { name, district, officer ->
                    onSaveProfile(name, district, officer) { editingProfile = false }
                },
            )
        }
        if (recordingReview) {
            ReviewEditor(
                scopeTitle = options.firstOrNull { it.key == effectiveScope }?.label ?: "Whole subdivision",
                openCount = counts.open,
                readyCount = counts.readyToVerify,
                busy = busy,
                onDismiss = { recordingReview = false },
                onSave = { notes -> onRecordReview(effectiveScope, notes) { recordingReview = false } },
            )
        }
    }
}

@Composable
private fun ProfileHeader(state: SubdivisionUiState, busy: Boolean, onEdit: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            state.profile?.name ?: "Your subdivision is not set up yet",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("review_profile_name"),
        )
        val subtitle = listOfNotNull(
            state.profile?.district?.takeIf { it.isNotBlank() },
            state.profile?.officerName?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Your private work record. Staff do not need an account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onEdit,
            enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("review_edit_profile"),
        ) {
            Text(if (state.isConfigured) "Edit subdivision" else "Set up subdivision")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewFilterRow(selected: ReviewFilter, onSelect: (ReviewFilter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReviewFilter.entries.forEach { item ->
            FilterChip(
                selected = selected == item,
                onClick = { onSelect(item) },
                label = { Text(filterLabel(item)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("review_filter_${item.name}"),
            )
        }
    }
}

/**
 * The station and matter recorded ON THE INSTRUCTION, plus who is responsible for it now.
 * The station shown is deliberately the recorded one — not the assigned contact's current
 * posting — so a transfer does not appear to have moved old work.
 */
@Composable
internal fun RecordedContextLine(state: SubdivisionUiState, item: Instruction) {
    val context = workContextOf(state, item)
    val responsible = state.contact(item.personId)?.name
        ?: (item.audience as? com.kaavalan.note.data.instructions.AudienceRef.ByPerson)
            ?.personId?.let { state.contact(it)?.name }
    Text(
        listOfNotNull(
            "Recorded at ${context.stationName ?: "no station"}",
            context.matterTitle?.let { "Matter: $it" },
            responsible?.let { "Responsible: $it" } ?: "No contact linked",
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("context_line_${item.id}"),
    )
}
