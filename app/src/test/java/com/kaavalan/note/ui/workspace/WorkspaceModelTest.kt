package com.kaavalan.note.ui.workspace

import com.kaavalan.note.data.instructions.*
import com.kaavalan.note.data.person.Person
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkspaceModelTest {
    private val date = LocalDate.parse("2026-09-08")
    private val zone = ZoneId.of("Asia/Kolkata")
    private val contact = Person("p1", "Ravi Kumar", "SI", "North station", null)
    private fun note(id: String, direction: Direction = Direction.SELF, due: String? = null,
        status: Status = Status.OPEN, person: String? = null) =
        Instruction(id, person, direction, status, Source.TEXT, Priority.NORMAL, "Review patrol plan",
            "Review patrol plan for festival duty", due, "2026-09-07T10:00:00Z",
            "2026-09-07T10:00:00Z", "2026-09-07T10:00:00Z")

    @Test fun `today splits personal actions and follow ups without losing undated work`() {
        val items = listOf(note("self"), note("received", Direction.INCOMING),
            note("assigned", Direction.OUTGOING), note("waiting", status = Status.WAITING_ON_OTHER))
        val result = todayWork(items, date, zone)
        assertEquals(setOf("self", "received"), result.attention.map { it.id }.toSet())
        assertEquals(setOf("assigned", "waiting"), result.followUps.map { it.id }.toSet())
        assertTrue(result.upcoming.isEmpty())
    }

    @Test fun `today uses local midnight and includes carried over work`() {
        val items = listOf(note("past", due = "2026-09-01T10:00:00Z"),
            note("today", due = "2026-09-08T18:29:59Z"),
            note("tomorrow", due = "2026-09-08T18:30:00Z"))
        val result = todayWork(items, date, zone)
        assertEquals(listOf("past", "today"), result.attention.map { it.id })
        assertEquals(listOf("tomorrow"), result.upcoming.map { it.id })
    }

    @Test fun `reminder milliseconds win over legacy ISO field`() {
        val item = note("future", due = "2026-09-01T10:00:00Z")
            .copy(dueAtMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        assertEquals(listOf(item), todayWork(listOf(item), date, zone).upcoming)
    }

    @Test fun `closed work does not appear as an action and completed today is local`() {
        val done = note("done", status = Status.DONE).copy(completedAt = "2026-09-07T19:00:00Z")
        val dropped = note("drop", status = Status.DROPPED)
        val result = todayWork(listOf(done, dropped), date, zone)
        assertTrue(result.attention.isEmpty())
        assertEquals(listOf(done), result.completed)
    }

    @Test fun `search matches multiple words across notes and contact context`() {
        val item = note("one", person = "p1")
        assertEquals(listOf(item), filterWork(listOf(item), listOf(contact), WorkFilter.OPEN, "RAVI festival north"))
        assertTrue(filterWork(listOf(item), listOf(contact), WorkFilter.OPEN, "missing").isEmpty())
    }

    @Test fun `all categories include every matching lifecycle state`() {
        val items = Status.entries.map { note(it.name, status = it) }
        assertEquals(5, filterWork(items, emptyList(), WorkFilter.OPEN, "").size)
        assertEquals(2, filterWork(items, emptyList(), WorkFilter.CLOSED, "").size)
        assertTrue(filterWork(items, emptyList(), WorkFilter.ASSIGNED, "").isEmpty())
    }

    @Test fun `vault projections cannot leak hidden sensitive or orphaned instruction text`() {
        val visible = note("visible", person = "p1")
        val items = listOf(visible, note("hidden", person = "hidden-contact"),
            note("unlinked"), note("sensitive").copy(isSensitive = true), note("orphan", person = "deleted"))
        assertEquals(setOf("visible", "unlinked"), visibleWork(items, listOf(contact), true).map { it.id }.toSet())
        assertEquals(listOf(visible), visibleWork(items, listOf(contact), false))
        assertTrue(visibleWork(items, emptyList(), false).isEmpty())
    }

    @Test fun `malformed legacy date is usable as an undated note`() {
        val item = note("legacy", due = "later")
        assertEquals(listOf(item), todayWork(listOf(item), date, zone).attention)
    }

    @Test fun `audience links are scoped even without the legacy person column`() {
        val linked = note("linked").copy(audience = AudienceRef.ByPerson("p1", "Ravi Kumar"))
        val otherVault = note("other").copy(audience = AudienceRef.ByPerson("private", "Private officer"))
        val conflicting = note("conflict", person = "p1").copy(audience = otherVault.audience)
        assertEquals(listOf(linked), visibleWork(listOf(linked, otherVault, conflicting), listOf(contact), false))
        assertTrue(visibleWork(listOf(linked, otherVault, conflicting), emptyList(), true).isEmpty())
    }
}
