package com.kaavalan.note.data.subdivision

import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Status
import java.time.Instant

/**
 * The review filters offered on the subdivision review surface. Each one states exactly
 * what it measures; nothing here infers performance or ranks anyone.
 */
enum class ReviewFilter { OPEN, READY_TO_VERIFY, DEADLINE_PASSED, NO_UPDATE_7_DAYS, CHANGED_SINCE_LAST_REVIEW }

/** Point-in-time counts. Recorded on a review so a stored number is never mistaken for live data. */
data class ReviewCounts(val open: Int, val readyToVerify: Int)

/**
 * Screen-independent, side-effect-free projections shared by the review screen, the
 * station / matter work lists and the repository's review snapshot, so a saved review
 * can never disagree with the number the officer was looking at.
 *
 * `now` is always passed in. Nothing here reads the clock, touches Room or Android, or
 * throws on a malformed timestamp: a single unparseable date must degrade one row, not
 * take down a whole screen.
 */
object SubdivisionProjections {

    const val SCOPE_ALL = "all"
    private const val SCOPE_STATION = "station:"
    private const val SCOPE_PERSON = "person:"

    /** Seven days, in milliseconds. The UI states the window in words next to the filter. */
    const val STALE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    fun stationScope(stationId: String) = SCOPE_STATION + stationId
    fun personScope(personId: String) = SCOPE_PERSON + personId

    fun stationIdOf(scopeKey: String): String? =
        scopeKey.takeIf { it.startsWith(SCOPE_STATION) }?.removePrefix(SCOPE_STATION)?.takeIf { it.isNotBlank() }

    fun personIdOf(scopeKey: String): String? =
        scopeKey.takeIf { it.startsWith(SCOPE_PERSON) }?.removePrefix(SCOPE_PERSON)?.takeIf { it.isNotBlank() }

    fun isKnownScope(scopeKey: String): Boolean =
        scopeKey == SCOPE_ALL || stationIdOf(scopeKey) != null || personIdOf(scopeKey) != null

    /** Never throws. An unparseable or absent timestamp is simply unknown. */
    fun instantOrNull(value: String?): Instant? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun epochMillisOrNull(value: String?): Long? = instantOrNull(value)?.toEpochMilli()

    val Instruction.isOpenWork: Boolean
        get() = status != Status.DONE && status != Status.DROPPED

    val Instruction.isReadyToVerify: Boolean
        get() = status == Status.REPORTED_DONE

    /**
     * Which review scope an instruction belongs to.
     *
     * A station scope matches the instruction's **recorded** station, not the currently
     * assigned contact's posting: moving an officer must not silently move their history.
     * An officer scope matches the responsible contact, including the audience pointer
     * when a single person is the audience.
     */
    fun matchesScope(item: Instruction, scopeKey: String): Boolean = when {
        scopeKey == SCOPE_ALL -> true
        stationIdOf(scopeKey) != null -> item.stationId == stationIdOf(scopeKey)
        personIdOf(scopeKey) != null -> {
            val id = personIdOf(scopeKey)
            item.personId == id ||
                (item.audience as? com.kaavalan.note.data.instructions.AudienceRef.ByPerson)?.personId == id
        }
        else -> false
    }

    fun inScope(items: List<Instruction>, scopeKey: String): List<Instruction> =
        items.filter { matchesScope(it, scopeKey) }

    /**
     * Completed and dropped work is excluded from the open categories. Work the
     * subordinate has reported complete stays open — and ready to verify — until the
     * officer verifies it themselves.
     */
    fun counts(items: List<Instruction>): ReviewCounts = ReviewCounts(
        open = items.count { it.isOpenWork },
        readyToVerify = items.count { it.isReadyToVerify },
    )

    /**
     * The most recent real activity on an instruction: the last recorded journal update,
     * falling back to when it was created. Deliberately not `updatedAt` — that field also
     * moves when a reminder or a work-context link changes, which is not an update on the
     * work itself.
     */
    fun lastActivityMs(item: Instruction): Long? =
        item.updates.mapNotNull { epochMillisOrNull(it.at) }.maxOrNull()
            ?: epochMillisOrNull(item.createdAt)
            ?: epochMillisOrNull(item.capturedAt)

    fun deadlinePassed(item: Instruction, nowMs: Long): Boolean =
        item.isOpenWork && (item.deadlineAtMs?.let { it < nowMs } == true)

    fun noUpdateWithinWindow(item: Instruction, nowMs: Long, windowMs: Long = STALE_WINDOW_MS): Boolean {
        if (!item.isOpenWork) return false
        val last = lastActivityMs(item) ?: return false
        return nowMs - last >= windowMs
    }

    /**
     * Changed since a previous review. Compares the instruction's own `updatedAt` against
     * the review timestamp using the same [Instant] parsing on both sides. Completed and
     * dropped work is included here on purpose: "what moved since I last looked" is
     * exactly where a completion belongs.
     */
    fun changedSince(item: Instruction, since: Instant): Boolean {
        val updated = instantOrNull(item.updatedAt) ?: return false
        return updated.isAfter(since)
    }

    /**
     * Apply one review filter. [lastReviewAt] is `null` when the officer has never
     * recorded a review for this scope; the caller says so in words rather than inventing
     * a since-date, and this returns an empty list instead of guessing.
     */
    fun applyFilter(
        items: List<Instruction>,
        filter: ReviewFilter,
        nowMs: Long,
        lastReviewAt: Instant? = null,
    ): List<Instruction> = when (filter) {
        ReviewFilter.OPEN -> items.filter { it.isOpenWork }
        ReviewFilter.READY_TO_VERIFY -> items.filter { it.isReadyToVerify }
        ReviewFilter.DEADLINE_PASSED -> items.filter { deadlinePassed(it, nowMs) }
        ReviewFilter.NO_UPDATE_7_DAYS -> items.filter { noUpdateWithinWindow(it, nowMs) }
        ReviewFilter.CHANGED_SINCE_LAST_REVIEW ->
            if (lastReviewAt == null) emptyList() else items.filter { changedSince(it, lastReviewAt) }
    }

    /**
     * The normal-workspace visibility gate for every CRM list, count, picker and review.
     *
     * Fails closed: a sensitive instruction, a contact outside the normal vault, a dangling
     * person reference and an audience-only link to a hidden person are all excluded. An
     * unassigned capture is only ever normal-workspace work, so it is included here and
     * never leaked into a private-mode projection.
     */
    fun normalWorkspaceWork(items: List<Instruction>, normalContactIds: Set<String>): List<Instruction> =
        items.filter { item ->
            val audienceId = (item.audience as? com.kaavalan.note.data.instructions.AudienceRef.ByPerson)?.personId
            !item.isSensitive &&
                (item.personId == null || item.personId in normalContactIds) &&
                (audienceId == null || audienceId in normalContactIds)
        }
}
