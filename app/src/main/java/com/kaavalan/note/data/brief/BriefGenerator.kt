package com.kaavalan.note.data.brief

import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M4-T1: brief generator. Reads the user's open instructions from
 * the local Room mirror (M2-T6) and groups them into the three
 * sections per spec §8.1.
 *
 * **Local generation.** The spec calls for a Supabase cron at 4 AM
 * user-local that writes a `daily_briefs` row. For v1 we generate
 * the brief client-side from the local mirror on demand — same
 * result, no round-trip, no cron to manage. The `daily_briefs`
 * table exists in the schema (for future server-side push) and
 * the spec still wins on content shape.
 *
 * **No counts in titles.** Per spec §3.3, the section titles are
 * the labels, not "3 things waiting on you".
 */
@Singleton
open class BriefGenerator @Inject constructor(
    private val instructionDao: InstructionDao,
) {
    /**
     * v1.6.5: app-scope for the shared brief flow. The
     * generator is a `@Singleton` so this scope lives for
     * the lifetime of the application. A [SupervisorJob]
     * means a single failure doesn't tear down the whole
     * pipeline.
     */
    private val scope = CoroutineScope(SupervisorJob())

    /**
     * v1.6.5: the cold [observeDailyBrief] is wrapped in
     * [shareIn] so multiple subscribers (TodayViewModel's
     * `brief` and `review` both subscribe) see the SAME
     * Room cursor and the same downstream emissions. Without
     * this, every subscriber opens its own Room cursor and
     * the binder traffic doubles (200 entities × N
     * subscribers). With 200 instructions and 2 subscribers
     * (brief + review) the Today screen hit "excessive
     * binder traffic during cached" and was killed.
     */
    private val sharedBrief: Flow<DailyBrief> = observeDailyBriefInternal()
        .shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1,
        )

    private fun observeDailyBriefInternal(): Flow<DailyBrief> =
        // v1.6.5: use [observeForBrief] (excludes DONE/DROPPED,
        // caps at 100) instead of [observeAll] (returns all 200+
        // entities). The brief only needs OPEN/ACK_PENDING/
        // IN_PROGRESS, so DONE/DROPPED rows are dead-weight on
        // the binder. The cap prevents ANR when the fixture is
        // large (200 instructions caused "excessive binder
        // traffic during cached" kill on the Today screen).
        // distinctUntilChanged() prevents redundant downstream
        // emissions when the Flow re-fires on a write that
        // didn't change the brief content.
        instructionDao.observeForBrief()
            .distinctUntilChanged()
            .map { entities ->
                val instructions = entities.map { it.toDomain() }
                build(type = BriefType.MORNING, date = LocalDate.now(ZoneId.systemDefault()), instructions = instructions, now = Instant.now())
            }

    /**
     * Build a brief for [date] (defaults to today in the device
     * timezone) from the open instructions in the local mirror.
     * Reactive: a Room update re-emits a new brief.
     *
     * v1.6.5: returns the singleton [sharedBrief] flow so multiple
     * subscribers (TodayViewModel's `brief` and `review` both
     * subscribe here) share ONE Room cursor. The previous body
     * re-opened [instructionDao.observeForBrief] for every
     * subscriber — with 2 subscribers and 200-instruction fixture,
     * the Today screen hit "excessive binder traffic during
     * cached" and was killed by the system. The [type] and
     * [date] params are accepted for API compatibility but no
     * longer steer the shared flow (both call sites use today's
     * MORNING brief; [type] is purely cosmetic in [build]).
     */
    fun observeDailyBrief(
        type: BriefType = BriefType.MORNING,
        date: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): Flow<DailyBrief> = sharedBrief

    /**
     * Synchronous build for tests and the review-screen on-demand
     * path. The reactive [observeDailyBrief] is the production path.
     */
    fun build(
        type: BriefType,
        date: LocalDate,
        instructions: List<Instruction>,
        now: Instant = Instant.now(),
    ): DailyBrief {
        // v1.8.0 (PROD-READINESS-P1-#4): the privacy gate.
        // Sensitive rows (is_sensitive = true) never appear in
        // the brief. They live in the local SQLCipher mirror
        // only and are excluded from the morning / evening
        // brief by spec §13. A row that is "open" + "needs
        // you" but is_sensitive is intentionally not surfaced
        // — the user's most sensitive cases are not what
        // they want to see at 8am. The flag is also the
        // sync-engine filter, so the row never leaves the
        // device in the first place; this filter is the
        // UI-side backstop.
        val visible = instructions.filterNot { it.isSensitive }
        val needs = visible
            .filter { it.needsYouToday(date, now) }
            .sortedWith(needsYouComparator)
        val waiting = visible
            .filter { it.waitingOnOthers() }
            .sortedBy { it.updatedAt }  // oldest first
        val carried = visible
            .filter { it.carriedOver(now) }
            .sortedBy { it.updatedAt }  // oldest first
        // DATA-FINDING-03: carriedOver (8-30d INCOMING OPEN) and
        // needsYouToday (the >7d rule) overlap on rows 8-30d old.
        // Without this dedup the same row appears in both sections
        // of the Today list. Carried is the "older" bucket, so we
        // drop the overlap from needs before assembling the brief.
        val carriedIds = carried.mapTo(mutableSetOf()) { it.id }
        val needsDeduped = if (carriedIds.isEmpty()) needs
            else needs.filterNot { it.id in carriedIds }
        return DailyBrief(
            date = date.toString(),
            type = type,
            needsYouToday = needsDeduped,
            waitingOnOthers = waiting,
            carriedOver = carried,
        )
    }

    // M4-T1: HIGH first, then by dueAt ASC NULLS LAST, then oldest.
    private val needsYouComparator: Comparator<Instruction> =
        compareByDescending<Instruction> { it.priority == com.kaavalan.note.data.instructions.Priority.HIGH }
            .thenBy(nullsLast<String>()) { it.dueAt }
            .thenBy { it.updatedAt }
}

private fun InstructionEntity.toDomain(): Instruction = Instruction(
    id = id,
    personId = personId,
    direction = runCatching { com.kaavalan.note.data.instructions.Direction.valueOf(direction) }
        .getOrDefault(com.kaavalan.note.data.instructions.Direction.OUTGOING),
    status = runCatching { com.kaavalan.note.data.instructions.Status.valueOf(status) }
        .getOrDefault(com.kaavalan.note.data.instructions.Status.OPEN),
    source = if (source == "OCR") {
        com.kaavalan.note.data.instructions.Source.PHOTO
    } else {
        runCatching { com.kaavalan.note.data.instructions.Source.valueOf(source) }
            .getOrDefault(com.kaavalan.note.data.instructions.Source.TEXT)
    },
    priority = runCatching { com.kaavalan.note.data.instructions.Priority.valueOf(priority) }
        .getOrDefault(com.kaavalan.note.data.instructions.Priority.NORMAL),
    title = title,
    rawText = rawText,
    dueAt = dueAt,
    capturedAt = capturedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// helper for nullsLast on nullable String
private fun <T : Comparable<T>> nullsLast(): Comparator<in T?> = Comparator { a, b ->
    when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> a.compareTo(b)
    }
}
