package com.kaavalan.note.data.subdivision

import com.kaavalan.note.data.instructions.AudienceRef
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.InstructionUpdate
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.instructions.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The review projections, tested as the pure functions they are: no Room, no Android, and
 * `now` passed in, so every count on the review screen is reproducible here.
 */
class SubdivisionProjectionsTest {

    private val nowMs = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()

    private fun work(
        id: String,
        status: Status = Status.OPEN,
        personId: String? = "p1",
        stationId: String? = "st-1",
        matterId: String? = null,
        deadlineAtMs: Long? = null,
        updatedAt: String = "2026-09-10T09:00:00Z",
        createdAt: String = "2026-09-01T09:00:00Z",
        updates: List<InstructionUpdate> = emptyList(),
        audience: AudienceRef? = null,
        isSensitive: Boolean = false,
    ) = Instruction(
        id = id,
        personId = personId,
        direction = Direction.OUTGOING,
        status = status,
        source = Source.TEXT,
        priority = Priority.NORMAL,
        title = "Title $id",
        rawText = "Body $id",
        dueAt = null,
        capturedAt = createdAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isSensitive = isSensitive,
        audience = audience,
        deadlineAtMs = deadlineAtMs,
        updates = updates,
        stationId = stationId,
        matterId = matterId,
    )

    private fun update(at: String) =
        InstructionUpdate(id = "u-$at", at = at, text = "Progress", status = "IN_PROGRESS", nextFollowUpAtMs = null)

    // ---- open / ready ----

    @Test
    fun `completed and dropped work is excluded from open but reported-done stays open`() {
        val counts = SubdivisionProjections.counts(
            listOf(
                work("open", Status.OPEN),
                work("progress", Status.IN_PROGRESS),
                work("waiting", Status.WAITING_ON_OTHER),
                work("carried", Status.CARRIED_OVER),
                work("reported", Status.REPORTED_DONE),
                work("done", Status.DONE),
                work("dropped", Status.DROPPED),
            ),
        )
        assertEquals("five open: everything except DONE and DROPPED", 5, counts.open)
        assertEquals(
            "work the subordinate reported complete is ready to verify, and still counted as open",
            1,
            counts.readyToVerify,
        )
    }

    @Test
    fun `ready to verify work remains in the open filter`() {
        val items = listOf(work("reported", Status.REPORTED_DONE))
        assertEquals(
            listOf("reported"),
            SubdivisionProjections.applyFilter(items, ReviewFilter.OPEN, nowMs).map { it.id },
        )
        assertEquals(
            listOf("reported"),
            SubdivisionProjections.applyFilter(items, ReviewFilter.READY_TO_VERIFY, nowMs).map { it.id },
        )
    }

    // ---- scopes ----

    @Test
    fun `a station scope matches the recorded station, not the contact's current posting`() {
        val items = listOf(
            work("at-kalakad", stationId = "st-kalakad"),
            work("at-nanguneri", stationId = "st-nanguneri"),
            work("no-station", stationId = null),
        )
        assertEquals(
            listOf("at-kalakad"),
            SubdivisionProjections.inScope(items, SubdivisionProjections.stationScope("st-kalakad")).map { it.id },
        )
        assertEquals(
            "the whole subdivision includes work with no station recorded",
            3,
            SubdivisionProjections.inScope(items, SubdivisionProjections.SCOPE_ALL).size,
        )
    }

    @Test
    fun `an officer scope matches both the assigned contact and a single-person audience`() {
        val items = listOf(
            work("assigned", personId = "p-ramesh"),
            work("addressed", personId = null, audience = AudienceRef.ByPerson("p-ramesh", "Ramesh")),
            work("somebody-else", personId = "p-kavitha"),
        )
        assertEquals(
            listOf("assigned", "addressed"),
            SubdivisionProjections.inScope(items, SubdivisionProjections.personScope("p-ramesh")).map { it.id },
        )
    }

    @Test
    fun `an unrecognised scope key matches nothing and is reported as unknown`() {
        assertFalse(SubdivisionProjections.isKnownScope("everything"))
        assertTrue(SubdivisionProjections.isKnownScope(SubdivisionProjections.SCOPE_ALL))
        assertTrue(SubdivisionProjections.isKnownScope(SubdivisionProjections.stationScope("st-1")))
        assertTrue(SubdivisionProjections.isKnownScope(SubdivisionProjections.personScope("p-1")))
        assertNull(SubdivisionProjections.stationIdOf("station:"))
        assertTrue(SubdivisionProjections.inScope(listOf(work("a")), "everything").isEmpty())
    }

    // ---- deadline passed ----

    @Test
    fun `deadline passed covers only open work whose deadline is in the past`() {
        val items = listOf(
            work("past", deadlineAtMs = nowMs - 1),
            work("exactly-now", deadlineAtMs = nowMs),
            work("future", deadlineAtMs = nowMs + 1),
            work("no-deadline", deadlineAtMs = null),
            work("done-and-past", status = Status.DONE, deadlineAtMs = nowMs - 1),
        )
        assertEquals(
            "a deadline exactly at now has not passed, and completed work is never listed",
            listOf("past"),
            SubdivisionProjections.applyFilter(items, ReviewFilter.DEADLINE_PASSED, nowMs).map { it.id },
        )
    }

    // ---- seven-day stale window ----

    @Test
    fun `no update in seven days measures the last recorded update and is inclusive at the boundary`() {
        val sevenDaysAgo = Instant.ofEpochMilli(nowMs - SubdivisionProjections.STALE_WINDOW_MS).toString()
        val justInside = Instant.ofEpochMilli(nowMs - SubdivisionProjections.STALE_WINDOW_MS + 1).toString()
        val items = listOf(
            work("exactly-seven-days", updates = listOf(update(sevenDaysAgo))),
            work("one-ms-short", updates = listOf(update(justInside))),
            work("updated-today", updates = listOf(update("2026-09-10T11:00:00Z"))),
        )
        assertEquals(
            "exactly seven days counts as stale; one millisecond short does not",
            listOf("exactly-seven-days"),
            SubdivisionProjections.applyFilter(items, ReviewFilter.NO_UPDATE_7_DAYS, nowMs).map { it.id },
        )
    }

    @Test
    fun `work with no update at all is measured from when it was created`() {
        val old = work("old", createdAt = "2026-08-01T09:00:00Z", updates = emptyList())
        val fresh = work("fresh", createdAt = "2026-09-09T09:00:00Z", updates = emptyList())
        assertEquals(
            listOf("old"),
            SubdivisionProjections.applyFilter(listOf(old, fresh), ReviewFilter.NO_UPDATE_7_DAYS, nowMs)
                .map { it.id },
        )
    }

    @Test
    fun `the newest update wins even when the journal is out of order`() {
        val item = work(
            "reordered",
            updates = listOf(update("2026-08-01T09:00:00Z"), update("2026-09-10T11:00:00Z")),
        )
        assertFalse(
            "an out-of-order journal must not make recently-updated work look stale",
            SubdivisionProjections.noUpdateWithinWindow(item, nowMs),
        )
    }

    @Test
    fun `an unparseable timestamp degrades one row instead of failing the screen`() {
        val broken = work("broken", createdAt = "not-a-date", updates = listOf(update("also-not-a-date")))
        // The point is that this returns rather than throwing.
        assertNull(SubdivisionProjections.lastActivityMs(broken))
        assertFalse(SubdivisionProjections.noUpdateWithinWindow(broken, nowMs))
        assertEquals(
            "the other rows still project normally",
            listOf("old"),
            SubdivisionProjections.applyFilter(
                listOf(broken, work("old", createdAt = "2026-08-01T09:00:00Z")),
                ReviewFilter.NO_UPDATE_7_DAYS,
                nowMs,
            ).map { it.id },
        )
    }

    // ---- changed since last review ----

    @Test
    fun `changed since last review compares against the review timestamp and includes completions`() {
        val lastReview = Instant.parse("2026-09-05T00:00:00Z")
        val items = listOf(
            work("touched-after", updatedAt = "2026-09-06T00:00:00Z"),
            work("touched-before", updatedAt = "2026-09-04T00:00:00Z"),
            work("exactly-at-review", updatedAt = "2026-09-05T00:00:00Z"),
            work("completed-after", status = Status.DONE, updatedAt = "2026-09-07T00:00:00Z"),
        )
        assertEquals(
            "strictly after the review; work completed since then is exactly what belongs here",
            listOf("touched-after", "completed-after"),
            SubdivisionProjections.applyFilter(items, ReviewFilter.CHANGED_SINCE_LAST_REVIEW, nowMs, lastReview)
                .map { it.id },
        )
    }

    @Test
    fun `with no earlier review the changed filter returns nothing rather than inventing a since-date`() {
        val items = listOf(work("a", updatedAt = "2026-09-09T00:00:00Z"))
        assertTrue(
            SubdivisionProjections.applyFilter(items, ReviewFilter.CHANGED_SINCE_LAST_REVIEW, nowMs, null).isEmpty(),
        )
    }

    // ---- normal-workspace visibility ----

    @Test
    fun `the visibility gate excludes sensitive, hidden-linked and audience-only leaks`() {
        val normalIds = setOf("p-normal")
        val items = listOf(
            work("visible", personId = "p-normal"),
            work("sensitive", personId = "p-normal", isSensitive = true),
            work("hidden-contact", personId = "p-hidden"),
            work("dangling-contact", personId = "p-deleted"),
            work(
                "audience-to-hidden",
                personId = null,
                audience = AudienceRef.ByPerson("p-hidden", "Hidden"),
            ),
            work("unassigned", personId = null),
        )
        assertEquals(
            "a sensitive record, a contact in the other vault, a deleted contact and an " +
                "audience-only link to a hidden person are all excluded; an unassigned capture is not",
            listOf("visible", "unassigned"),
            SubdivisionProjections.normalWorkspaceWork(items, normalIds).map { it.id },
        )
    }
}
