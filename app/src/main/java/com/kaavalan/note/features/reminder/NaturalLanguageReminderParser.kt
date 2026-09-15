package com.kaavalan.note.features.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Recognises a deliberately small, explicit set of spoken/typed reminder phrases.
 *
 * This is local-only and conservative: it needs both a relative day and an explicit
 * clock time before it changes a reminder. The original note is never rewritten, so a
 * phrase such as "tomorrow's procession" stays useful context after the reminder fires.
 */
internal object NaturalLanguageReminderParser {

    private val dayPattern = Regex(
        "\\b(day after tomorrow|tomorrow|today|tonight)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val twelveHourTimePattern = Regex(
        "\\b(?:at\\s+)?(?:(?:early|late)\\s+)?(?:(?:in\\s+the\\s+)?(?:morning|afternoon|evening|night)\\s+)?" +
            "([1-9]|1[0-2])(?::([0-5]\\d))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )
    private val twentyFourHourTimePattern = Regex(
        "\\b(?:at\\s+)?([01]?\\d|2[0-3]):([0-5]\\d)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Returns a future local reminder instant, or null when the wording is incomplete or stale. */
    fun parse(
        text: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val dateMatch = dayPattern.find(text) ?: return null
        val localNow = now.atZone(zone)
        val date = relativeDate(dateMatch.value, localNow.toLocalDate()) ?: return null
        val time = parseTime(text) ?: return null
        val candidate = LocalDateTime.of(date, time).atZone(zone).toInstant()
        return candidate.takeIf { it.isAfter(now) }?.toEpochMilli()
    }

    private fun relativeDate(raw: String, today: LocalDate): LocalDate? = when (raw.lowercase()) {
        "today", "tonight" -> today
        "tomorrow" -> today.plusDays(1)
        "day after tomorrow" -> today.plusDays(2)
        else -> null
    }

    private fun parseTime(text: String): LocalTime? {
        twelveHourTimePattern.find(text)?.let { match ->
            val displayedHour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifBlank { "0" }.toInt()
            val meridiem = match.groupValues[3].lowercase()
            val hour = (displayedHour % 12) + if (meridiem.startsWith('p')) 12 else 0
            return LocalTime.of(hour, minute)
        }
        twentyFourHourTimePattern.find(text)?.let { match ->
            return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        return null
    }
}
