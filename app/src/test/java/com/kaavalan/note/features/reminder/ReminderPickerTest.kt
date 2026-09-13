package com.kaavalan.note.features.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPickerTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-09-08T12:45:00Z").toEpochMilli()

    @Test
    fun `one-hour preset is exactly one hour later`() {
        assertEquals(now + 3_600_000L, ReminderPresets.inOneHour(now))
    }

    @Test
    fun `tomorrow preset is 9 AM in the device zone`() {
        val result = Instant.ofEpochMilli(ReminderPresets.tomorrowMorning(now, zone))
            .atZone(zone)

        assertEquals("2026-09-09", result.toLocalDate().toString())
        assertEquals(9, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `next-week preset is seven days later at 9 AM`() {
        val result = Instant.ofEpochMilli(ReminderPresets.nextWeekMorning(now, zone))
            .atZone(zone)

        assertEquals("2026-09-15", result.toLocalDate().toString())
        assertEquals(9, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `this-week choices run from today through Sunday`() {
        assertEquals(
            listOf("2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-12", "2026-09-13"),
            ReminderPresets.thisWeekDates(now, zone).map(LocalDate::toString),
        )
    }

    @Test
    fun `today hides clock choices that have already passed`() {
        val today = ReminderPresets.localDate(now, zone)
        assertEquals(
            listOf(LocalTime.of(20, 0)),
            ReminderPresets.suggestedTimes(today, now, zone),
        )
    }

    @Test
    fun `future dates offer morning afternoon and evening times`() {
        val tomorrow = ReminderPresets.localDate(now, zone).plusDays(1)
        assertEquals(
            listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0), LocalTime.of(20, 0)),
            ReminderPresets.suggestedTimes(tomorrow, now, zone),
        )
    }

    @Test
    fun `formatter uses the requested local zone`() {
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals(
                "Tue, 8 Sep · 18:15",
                formatReminderTime(now, zone),
            )
        } finally {
            java.util.Locale.setDefault(previousLocale)
        }
    }
}
