package com.kaavalan.note.ui.today.decay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import com.kaavalan.note.ui.theme.KaavalanColors
import kotlin.math.roundToInt

/**
 * v2.0 Tier 2 (§2.1, §2.13, §2.14): the "Haven't touched in N
 * days" section rendered above the existing Today brief. Calm
 * palette, no red — the [ReachOutPill] uses
 * [KaavalanColors.Quiet] / [KaavalanColors.Done] / muted brown, never
 * error colour.
 *
 * The section is hidden entirely when the list is empty; the
 * `EmptyState` line lives in the parent Today brief. Filter chip
 * row is always visible so the user can switch between
 * 14 / 30 / 60 / 90 d.
 */
@Composable
fun DecaySection(
    onOpenPerson: (String) -> Unit,
    viewModel: DecayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showGestureHint by viewModel.gestureHintVisible.collectAsStateWithLifecycle()
    var showRedistribute by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // v1.9.4 (drive-verify polish #4): outer vertical
            // padding 8dp -> 4dp. The Decay section sits right
            // under Today's win; shaving 8dp off the top +
            // bottom gives the visible list 16dp more of
            // breathing room without changing the card sizes.
            .padding(horizontal = 16.dp, vertical = 4.dp),
        // v1.9.4: inter-element spacing 8dp -> 6dp. Five
        // gaps (header / subtitle / chips / banner / rows) at
        // 6dp instead of 8dp = 10dp saved on the section as a
        // whole. Below the "noise floor" the eye notices.
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.decay_section_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.decay_section_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v1.9.6 (drive-verify polish #6): one-time
        // discoverability hint for the v1.9.5 swipe-right +
        // long-press gestures. Renders above the filter chip
        // row so the user reads the hint before they try to
        // filter / sort. The hint is a small AssistChip with
        // the hint text and a "Got it" close affordance; the
        // same calm tertiaryContainer palette as the
        // redistribution banner above. The hint is gated on
        // (a) >= 3 quiet contacts (so the gesture is worth
        // surfacing) AND (b) the DataStore flag
        // `decay_gesture_hint_shown_v1` being `false`. Once
        // dismissed, the flag flips to `true` and the chip
        // never re-appears in this APK version.
        if (showGestureHint) {
            DecayGestureHint(onDismiss = { viewModel.dismissGestureHint() })
        }
        FilterChipsRow(
            current = state.filterDays,
            onPick = viewModel::setFilter,
        )
        // §2.14: redistribution banner — only when the quiet
        // pile is > 5 (matches the spec).
        if (state.quietCount > 5) {
            AssistChip(
                onClick = { showRedistribute = true },
                label = {
                    // v1.6.4: pluralised (was hard-coded "%1$d quiet
                    // contacts" → "1 quiet contact" / "N quiet contacts").
                    Text(pluralStringResource(R.plurals.bulk_snooze_banner, state.quietCount, state.quietCount))
                },
            )
        }
        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.decay_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.rows.forEach { row ->
                DecayRow(
                    row = row,
                    onClick = { onOpenPerson(row.id) },
                    onMarkRecent = { viewModel.markRecent(row) },
                )
            }
        }
    }

    if (showRedistribute) {
        RedistributeDialog(
            count = state.quietCount,
            onConfirm = {
                viewModel.redistribute()
                showRedistribute = false
            },
            onDismiss = { showRedistribute = false },
        )
    }
}

/**
 * v1.9.6 (drive-verify polish #6): the discoverability
 * hint. Wraps the hint text + a "Got it" close affordance
 * in a Row so both pieces of UI are clickable. The chip
 * itself uses [AssistChip] (Material 3) with a leading
 * close icon so the user has two ways to dismiss: tap
 * the label body or tap the close icon. Either dispatches
 * the same `onDismiss` lambda, which calls
 * [DecayViewModel.dismissGestureHint] and persists the
 * "shown" flag to DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecayGestureHint(onDismiss: () -> Unit) {
    val hintText = stringResource(R.string.decay_gesture_hint)
    val dismissLabel = stringResource(R.string.decay_gesture_hint_dismiss)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onDismiss,
            label = {
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            // v1.9.6: leading close icon so the user has an
            // obvious dismiss affordance. The icon is
            // intentional, not the default `Icons.Filled.Close`
            // because Material 3 leads the chip with
            // something actionable — Close is the action.
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = dismissLabel,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }
}

@Composable
private fun FilterChipsRow(current: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(DecayViewModel.FILTER_OPTIONS) { days ->
            FilterChip(
                selected = current == days,
                onClick = { onPick(days) },
                label = { Text(stringResource(filterResFor(days))) },
            )
        }
    }
}

private fun filterResFor(days: Int): Int = when (days) {
    14 -> R.string.decay_filter_14d
    30 -> R.string.decay_filter_30d
    60 -> R.string.decay_filter_60d
    90 -> R.string.decay_filter_90d
    else -> R.string.decay_filter_30d
}

/**
 * v1.9.5 (Compact DecayRow): the per-row card. Replaces the
 * v1.9.4 `Mark recent` TextButton (which ate ~80dp of horizontal
 * space and forced the days-quiet text to ellipsize) with two
 * gesture affordances:
 *  1. Swipe-right past a 96dp threshold fires `onMarkRecent`
 *     (with the standard Undo snackbar wired through
 *     [DecayViewModel.markRecent]).
 *  2. Long-press opens a [ModalBottomSheet] with a single
 *     "Mark as recent" action (for users who don't know the
 *     swipe gesture).
 *
 * The status pill ([ReachOutPill]) is the only right-side
 * control, so the left column has the full width to render
 * "haven't touched in 93 days" without truncation.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DecayRow(
    row: DecayRow,
    onClick: () -> Unit,
    onMarkRecent: () -> Unit,
) {
    val openLabel = stringResource(R.string.a11y_decay_row_open)
    val markRecentLabel = stringResource(R.string.a11y_decay_row_mark_recent)
    var showActionSheet by remember { mutableStateOf(false) }
    var markedRecent by remember { mutableStateOf(false) }

    // v2.1.2 (release-integrity): BoxWithConstraints -> Box. The swipe
    // threshold is a fixed dp constant converted via LocalDensity, not
    // a constraint read from BoxWithConstraintsScope (maxWidth /
    // maxHeight / constraints are never referenced anywhere in this
    // composable). BoxWithConstraints forces a subcomposition pass to
    // supply that scope; paying that cost on every DecayRow with
    // nothing reading it was pure waste on a row rendered in a
    // LazyColumn. Lint's UnusedBoxWithConstraintsScope check is the
    // one that caught it.
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val density = LocalDensity.current
        val thresholdPx = with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }
        var offsetX by remember { mutableStateOf(0f) }

        // v2.1.3 (adversarial-QA): the `pointerInput { detectHorizontalDragGestures }`
        // used to live on this wrapping Box, as the *ancestor* of the
        // Card that carries `combinedClickable` below. Two independent,
        // uncoordinated low-level gesture recognizers competing for the
        // same touch stream across a parent/child boundary is a known
        // Compose pitfall: on the `Main` pointer-event pass, the
        // deeper node (the Card's combinedClickable) is dispatched to
        // *first*, and its own press/long-press tracking consumes the
        // pointer's position-change deltas before they ever bubble up
        // to this Box's `awaitHorizontalTouchSlopOrCancellation` — so
        // the ancestor's drag detector could never observe an
        // unconsumed horizontal move and the 96dp swipe threshold was
        // never reached. That silently killed swipe-to-mark-recent on
        // every device (verified: on-device tap/long-press still
        // reached the child's combinedClickable and worked, while an
        // over-threshold swipe on the same row produced no removal, no
        // Undo snackbar, and no background reveal).
        //
        // The fix keeps `combinedClickable` and this drag detector as
        // separate modifiers (so Card keeps its Material ripple +
        // TalkBack semantics from combinedClickable untouched) but
        // moves both onto the *same* Card node below, with
        // `pointerInput` chained *after* `combinedClickable`. Modifier
        // order on a single node nests the same way parent/child does:
        // the later element is "inner" and is dispatched to first on
        // the Main pass. That gives the drag detector first refusal on
        // any horizontal movement — so a real swipe is consumed and
        // reported before combinedClickable's own tracking can steal
        // it — while a stationary tap/long-press (no movement to
        // consume) still reaches combinedClickable exactly as before.
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Background layer (visible while swiping right): a
            // quiet tertiary-container "Mark recent" label.
            // Hidden when the offset is <= 0.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(start = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.decay_mark_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            // Foreground Card. combinedClickable wires the tap
            // (open PersonDetail) and long-press (open action
            // sheet); the swipe-right pointerInput is chained
            // right after it on this same node (see the v2.1.3
            // comment above) so the background "Mark recent"
            // label — a sibling Box underneath — stays visible
            // while this Card is dragged over it.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    // v1.7.3 (P2-A): same `heightIn(min = 88.dp)` shape as
                    // the v1.7.2 HomeScreen PersonRow fix. The default
                    // card contentPadding + the natural height of the
                    // 3-line row (name + designation + days-quiet) is
                    // close to 88dp; without the min, the bottom of the
                    // last visible row gets clipped by ~16dp when the
                    // user scrolls to the very end of the list. The
                    // dump shows the clipped text at h=21 instead of
                    // h=37 (the natural height of `bodySmall`).
                    //
                    // v1.9.2: the right-side `TextButton` + `ReachOutPill`
                    // were laid out as siblings in the parent `Row`,
                    // taking ~250dp of horizontal space on a 1080px
                    // device. That squeezed the left text column to
                    // ~600dp, forcing the name to wrap to 2 lines
                    // ("B. Ramesh" / "Naidu") and the designation to
                    // its own line — the cards looked cramped and
                    // unbalanced. The right-side controls are now
                    // stacked in their own `Column` so the left
                    // column gets the full available width and the
                    // name stays on one line. The total card height
                    // grows from ~88dp to ~110dp, which is still
                    // well within the LazyColumn item budget and
                    // gives the cards room to breathe.
                    //
                    // v1.9.4 (drive-verify polish #4): min height
                    // 88dp -> 72dp. The 88dp floor was set in v1.7.3
                    // to prevent a layout-collapse bug at the bottom
                    // of the list, but with the v1.9.2 maxLines caps
                    // (name 2 + designation 1 + days-quiet 1, all
                    // ellipsized) the natural card height is already
                    // ~70dp. 72dp keeps the touch target comfortable
                    // without padding empty space. Cards now sit ~16dp
                    // closer together, so 5+ rows fit on the Today
                    // screen instead of 4.
                    //
                    // v1.9.5: the right-side controls collapse from
                    // a TextButton + Pill (~420dp) to just the Pill
                    // (~120dp). The left column gets ~300dp more
                    // horizontal space, so the days-quiet text now
                    // fits in one line without ellipsis on a 1080px
                    // device. The `maxLines = 1` cap stays as a
                    // safety net for very long day counts.
                    .heightIn(min = 72.dp)
                    .combinedClickable(
                        onClickLabel = openLabel,
                        onClick = onClick,
                        onLongClickLabel = markRecentLabel,
                        onLongClick = { showActionSheet = true },
                    )
                    // v2.1.3 (adversarial-QA): chained *after*
                    // combinedClickable (not on the ancestor Box — see
                    // the comment above) so this drag detector is the
                    // "inner" node and gets first refusal on
                    // horizontal movement during the Main pointer-event
                    // pass, instead of losing the touch stream to
                    // combinedClickable's own press tracking.
                    .pointerInput(row.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (offsetX > thresholdPx && !markedRecent) {
                                    markedRecent = true
                                    onMarkRecent()
                                }
                                // Snap back to 0 (offset is no longer
                                // tracked; the card itself disappears
                                // from the Quiet-a-while list once the
                                // DAO touch completes, so there is no
                                // visible "card slid right" state).
                                offsetX = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                offsetX += dragAmount
                            },
                        )
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // v1.9.4: inner vertical padding 12dp -> 10dp.
                        // 4dp saved per card, 20dp+ saved across 5
                        // visible rows.
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            // v1.9.2: cap the name to 2 lines (it can
                            // wrap to 3 for very long names like
                            // "Superintendent of Police"); ellipsize the
                            // tail so a long name does not push the
                            // designation off the card.
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        row.designation?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        // v1.9.4: Spacer 2dp -> 1dp. The 2dp gap was
                        // a 1.5-line-scaling-rounding-effect; 1dp is
                        // enough vertical separation between the
                        // designation and the days-quiet lines.
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = pluralStringResource(
                                // v1.6.4: pluralised (was hard-coded
                                // "haven't touched in %1$d days" → "in 1 day" /
                                // "in N days").
                                R.plurals.decay_days_quiet,
                                row.daysQuiet.toInt(),
                                row.daysQuiet.toInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // v1.9.5: keep the 1-line cap as a
                            // safety net for very long day counts
                            // (e.g. 365d). The right-side
                            // ReachOutPill is the only right-side
                            // control now (~120dp instead of
                            // ~420dp), so the left column has
                            // ~900dp on a 1080px device — enough
                            // to render "haven't touched in 365
                            // days" without ellipsis.
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    // v1.9.5: the Mark recent TextButton + spacer
                    // are gone. Only the ReachOutPill remains on
                    // the right side, giving the left text column
                    // the full available width and letting
                    // "haven't touched in 93 days" render without
                    // ellipsis. The "Mark recent" affordance moved
                    // to swipe-right (gesture) and long-press
                    // (action sheet).
                    ReachOutPill(row.status)
                }
            }
        }
    }

    if (showActionSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(
                    onClick = {
                        onMarkRecent()
                        showActionSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.decay_mark_recent),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = { showActionSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** v1.9.5: the minimum drag distance (in dp) for the
 *  swipe-right-to-mark-recent gesture to fire. Material 3
 *  standard threshold for list-item side-effect actions. */
private const val SWIPE_THRESHOLD_DP = 96

/**
 * v2.0 Tier 2 (§2.13): the reach-out status pill. The colour
 * tokens are taken from the existing calm palette
 * ([KaavalanColors.Quiet] = amber for "Quiet a while",
 * `tertiaryContainer` for "Getting due", [KaavalanColors.Done] for
 * "On track"). We never use `MaterialTheme.colorScheme.error` or
 * `Color.Red` here - spec §3.3 forbids red "overdue" semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReachOutPill(state: ReachOutStatus) {
    val (color, label) = when (state) {
        ReachOutStatus.QuietAWhile -> KaavalanColors.Quiet to R.string.status_quiet_a_while
        ReachOutStatus.GettingDue -> KaavalanColors.PriorityLow to R.string.status_getting_due
        ReachOutStatus.OnTrack -> KaavalanColors.Done to R.string.status_on_track
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun RedistributeDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_snooze_title)) },
        text = { Text(stringResource(R.string.bulk_snooze_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.bulk_snooze_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bulk_snooze_cancel))
            }
        },
    )
}
