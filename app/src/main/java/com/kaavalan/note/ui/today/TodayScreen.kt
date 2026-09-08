package com.kaavalan.note.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import com.kaavalan.note.data.brief.DailyBrief
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.person.toEntity
import com.kaavalan.note.features.search.SearchBar
import com.kaavalan.note.features.search.SearchViewModel
import com.kaavalan.note.ui.components.InstructionDetailSheet
import com.kaavalan.note.ui.components.formatTimeIso
import com.kaavalan.note.ui.today.brief.MeetingBriefCard
import com.kaavalan.note.ui.today.decay.DecaySection
import com.kaavalan.note.ui.today.win.TodaysWinCard
import com.kaavalan.note.ui.today.worry.WorryBoxSection

/**
 * M4-T1: Today screen. The morning brief content lives here.
 *
 * M4-T5: a "Review" button in the top app bar opens the evening
 * review sheet.
 *
 * v1.5.3 (VAULT-010): tapping a note row opens an Instruction
 * detail sheet with Mark done / Drop / Reopen actions. Without
 * this, the day-2 user flow is broken — the user can capture
 * a note but cannot close the loop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel = hiltViewModel(),
    onOpenPerson: (String) -> Unit = {},
    openInstructionId: String? = null,
    onReminderInstructionOpened: () -> Unit = {},
) {
    val brief by viewModel.brief.collectAsStateWithLifecycle()
    var showReview by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Instruction?>(null) }
    val searchViewModel: SearchViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val query by searchViewModel.query.collectAsStateWithLifecycle()
    val results by searchViewModel.results.collectAsStateWithLifecycle()
    LaunchedEffect(openInstructionId, brief) {
        val instructionId = openInstructionId ?: return@LaunchedEffect
        val match = (
            brief.needsYouToday + brief.waitingOnOthers + brief.carriedOver
            ).firstOrNull { it.id == instructionId }
            ?: viewModel.findInstruction(instructionId)
        if (match != null) {
            selected = match
        }
        onReminderInstructionOpened()
    }
    Scaffold(
        topBar = {
            Column {
                // v1.6.3: Obsidian-style title (see HomeScreen
                // for the rationale). Smaller, quieter, reads as
                // a section label.
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.tab_today),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            // v1.6.3: explicit start padding to
                            // compensate for `windowInsets(0)` which
                            // strips the leading inset the
                            // TopAppBar would normally add.
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    },
                    actions = {
                        // v1.6.3: the "Review" button was an
                        // OutlinedButton which competed visually
                        // with the title. A TextButton is more
                        // Obsidian-like (the action is secondary
                        // to the content).
                        TextButton(onClick = { showReview = true }) {
                            Text(stringResource(R.string.today_review_action))
                        }
                    },
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                )
                SearchBar(viewModel = searchViewModel)
            }
        },
    ) { padding ->
        // v1.6.2: also pull the person filter from the search VM.
        // The visible people list is fed in by the
        // TodayViewModel.persons flow (added in v1.6.2 so the
        // search placeholder is honest on Today too).
        val personResults by searchViewModel.personResults.collectAsStateWithLifecycle()
        val persons by viewModel.persons.collectAsStateWithLifecycle()
        LaunchedEffect(persons) {
            searchViewModel.setVisiblePeople(persons.map { it.toEntity() })
        }
        if (query.isNotEmpty()) {
            // v1.6.3: pass the person name map so the instruction
            // group header reads as a person name (e.g. "K. Ramana"),
            // not a truncated UUID like "8bc44494-016f-4cd0-8".
            // The map is computed from the same `persons` flow
            // we already feed to SearchViewModel.
            val personNameById = remember(persons) {
                persons.associate { it.id to it.name }
            }
            com.kaavalan.note.ui.home.HomeScreenSearchResults(
                personResults = personResults,
                instructionResults = results,
                personNameById = personNameById,
                padding = padding,
                // v1.7.2 (P1-D): a tap on a person result used to
                // be a no-op (the comment said "search is read-only
                // on Today"). The HomeScreenSearchResults Composable
                // is the same component used on Home, where person
                // taps route to PersonDetail. Routing the Today-side
                // tap to the same `onOpenPerson(id)` makes the two
                // search surfaces behave identically — a quiet
                // affordance parity, not a feature.
                onPersonClick = { id -> onOpenPerson(id) },
                // v1.7.0: instruction tap opens the existing
                // InstructionDetailSheet (same component the
                // brief cards open). Reuses TodayViewModel
                // handlers — the same markDone / markDropped
                // / reopen used for the brief cards.
                onInstructionClick = { entity -> selected = entity.toDomain() },
            )
        } else if (brief.isEmpty) {
            // v1.6.3: the EmptyBriefContent used to render at the
            // top of the body, overlapping the topBar Column (the
            // headline "Nothing on your plate." was hidden behind
            // the search bar). Wrap in a Box with the Scaffold
            // padding so the empty state is centered BELOW the
            // search bar.
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyBriefContent()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                // Each Today component owns the same 16dp side inset.
                // Keep the list itself vertical-only so a focused
                // instruction, section header, meeting brief, and
                // reflection card align to one shared content edge
                // instead of receiving a hidden double inset.
                //
                // v1.9.2: bottom contentPadding reset to 8dp
                // (the v1.6.3 default). The earlier v1.9.2 bump
                // to 96dp was meant to clear the bottom nav
                // (Home / Today / Settings) which is rendered
                // by MainActivity OUTSIDE the Scaffold, but
                // the Scaffold's `padding` parameter already
                // accounts for the bottom nav's height (it is
                // the Scaffold's `bottomBar`). Adding 96dp on
                // top of that produced a visible dark gap of
                // ~288px between the last card and the nav.
                // Removing the extra contentPadding eliminates
                // the gap and the last card (e.g. "K. Mahesh"
                // in the Decay list) now sits flush against
                // the top of the bottom nav instead of clipping
                // behind it.
                //
                // v1.9.4 (drive-verify polish #4): vertical
                // contentPadding 8dp -> 4dp + spacedBy 8dp -> 4dp.
                // The user's "UI should use the screen
                // properly" feedback. 4dp of vertical
                // contentPadding + 4dp of inter-item spacing
                // gives the surface room to breathe without
                // wasting 1/4 of the screen on gaps. 5+ cards
                // now fit on the Today screen instead of 4.
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            // Focus-first: the existing BriefGenerator ordering is
            // the source of truth. We surface one calm starting point
            // before summaries, quiet contacts, or reflective tools.
            val focus = focusFirst(brief)
            focus.instruction?.let { focusInstruction ->
                item {
                    FocusInstructionCard(
                        instruction = focusInstruction,
                        needsYou = focus.needsYou,
                        onClick = { selected = focusInstruction },
                    )
                }
            }

            if (focus.remainingNeeds.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.today_section_needs_you)) }
                items(items = focus.remainingNeeds, key = { it.id }) { ins ->
                    InstructionCard(ins, onClick = { selected = ins })
                }
            }
            if (focus.remainingWaiting.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.today_section_waiting)) }
                items(items = focus.remainingWaiting, key = { it.id }) { ins ->
                    InstructionCard(ins, onClick = { selected = ins })
                }
            }
            if (brief.carriedOver.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.today_section_carried_over)) }
                items(items = brief.carriedOver, key = { it.id }) { ins ->
                    InstructionCard(ins, onClick = { selected = ins })
                }
            }

            // Context is deliberately below the action path. These
            // tools are useful, but should never hide the instruction
            // that lets the officer make progress now.
            item { SectionHeader(stringResource(R.string.today_section_context)) }
            item { TodaysWinCard() }
            item { MeetingBriefCard() }
            item { DecaySection(onOpenPerson = onOpenPerson) }
            item { WorryBoxSection() }
            // v1.6.3: removed the trailing 80dp Spacer; the
            // LazyColumn's contentPadding(bottom) is the only
            // bottom buffer now (Scaffold.bottomBar is empty
            // on Today, so no overlap to clear).
        }
        }
    }
    if (showReview) {
        val review by viewModel.review.collectAsStateWithLifecycle()
        EveningReviewSheet(
            review = review,
            onDismiss = { showReview = false },
        )
    }
    selected?.let { ins ->
        InstructionDetailSheet(
            instruction = ins,
            onDismiss = { selected = null },
            onMarkDone = {
                viewModel.markDone(ins.id)
                selected = null
            },
            onDrop = {
                viewModel.markDropped(ins.id)
                selected = null
            },
            onReopen = {
                viewModel.reopen(ins.id)
                selected = null
            },
            onReminderChanged = { reminderAtMs ->
                viewModel.updateReminder(ins.id, reminderAtMs)
                selected = ins.copy(
                    dueAtMs = reminderAtMs,
                    dueAt = reminderAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
                )
            },
        )
    }
}

@Composable
private fun EmptyBriefContent() {
    // v1.6.3: Box + center-align so the empty state sits in the
    // visual middle of the available space, not the top. Same
    // Obsidian-document pattern as the HomeScreen empty state.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.today_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.today_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The focused first action for the day. It deliberately reuses the
 * instruction detail sheet instead of inventing a second completion
 * flow, so every entry point has the same update / done / drop path.
 */
@Composable
private fun FocusInstructionCard(
    instruction: Instruction,
    needsYou: Boolean,
    onClick: () -> Unit,
) {
    val openLabel = stringResource(R.string.a11y_today_row_open)
    val focusDescription = if (needsYou) {
        stringResource(R.string.today_focus_needs_you)
    } else {
        stringResource(R.string.today_focus_waiting)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClickLabel = openLabel, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.today_focus_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = instruction.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (instruction.title != instruction.rawText) {
                Text(
                    text = instruction.rawText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                text = focusDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.today_focus_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * v1.5.3 (VAULT-010): the card is now clickable. Tapping it
 * opens the InstructionDetailSheet. The `clickable` modifier
 * on the outer Card is the smallest change that gets the
 * hit-target correct (the whole row, not just the text).
 */
@Composable
private fun InstructionCard(ins: Instruction, onClick: () -> Unit) {
    val openLabel = stringResource(R.string.a11y_today_row_open)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // The card owns the shared 16dp side inset so it
            // stays aligned with headers and contextual cards.
            .clickable(onClickLabel = openLabel, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = ins.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // v1.6.2: skip the body when it duplicates the title.
            // v1.6.1 capture stores a single line of text in both
            // `title` and `rawText` (no separate title/body fields);
            // rendering both makes the card look like it lost a
            // line. When they differ, `rawText` carries the rest
            // of the note.
            if (ins.title != ins.rawText) {
                Text(
                    text = ins.rawText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val dueText = when {
                ins.dueAtMs != null -> stringResource(
                    R.string.today_due_at,
                    com.kaavalan.note.features.reminder.formatReminderTime(ins.dueAtMs),
                )
                ins.dueAt != null -> stringResource(R.string.today_due_at, formatTimeIso(ins.dueAt))
                else -> null
            }
            val capturedText = stringResource(R.string.today_captured_at, formatTimeIso(ins.capturedAt))
            Text(
                text = listOfNotNull(dueText, capturedText).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * M4-T5: evening review sheet. One screen. "What got done today"
 * + "What's still open" + a single dismiss tap. No streak, no
 * count-up, no punishment for missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EveningReviewSheet(review: EveningReview, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.today_evening_review), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = review.date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (review.stillOpen.isEmpty()) {
                Text(
                    text = stringResource(R.string.today_review_nothing_carried),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(stringResource(R.string.today_review_still_open), style = MaterialTheme.typography.titleSmall)
                review.stillOpen.forEach { ins ->
                    Text(
                        text = stringResource(R.string.today_review_bullet_prefix, ins.title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.today_review_dismiss_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
