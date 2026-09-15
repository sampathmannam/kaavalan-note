package com.kaavalan.note.features.reminder

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaturalLanguageReminderParserTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 9, 15, 10, 0).atZone(zone).toInstant()

    @Test
    fun `traffic recce phrase detects tomorrow evening time without rewriting the note`() {
        val note = "Go with Traffic Inspector to review the road traffic recce for tomorrow's procession at evening 4 PM"

        assertEquals(
            at(2026, 9, 16, 16, 0),
            NaturalLanguageReminderParser.parse(note, now, zone),
        )
    }

    @Test
    fun `follow up phrase detects tomorrow morning time`() {
        assertEquals(
            at(2026, 9, 16, 6, 0),
            NaturalLanguageReminderParser.parse("Follow up tomorrow morning 6 AM", now, zone),
        )
    }

    @Test
    fun `a relative day without an explicit time is left untouched`() {
        assertNull(NaturalLanguageReminderParser.parse("Review the route tomorrow morning", now, zone))
    }

    @Test
    fun `a time already passed today is not scheduled`() {
        assertNull(NaturalLanguageReminderParser.parse("Call control room today at 9 AM", now, zone))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
