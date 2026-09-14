package com.kaavalan.note.data.instructions

import com.kaavalan.note.data.local.entities.InstructionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionCompatibilityTest {
    private val row = InstructionEntity(
        id = "legacy", personId = null, direction = "SELF", status = "OPEN",
        source = "OCR", priority = "NORMAL", title = "Photo note", rawText = "Extracted text",
        dueAt = null, capturedAt = "2026-09-08T10:00:00Z",
        createdAt = "2026-09-08T10:00:00Z", updatedAt = "2026-09-08T10:00:00Z",
    )

    @Test fun legacyOcr_keepsRecordAndMapsToPhoto() {
        val note = row.toDomain()
        assertEquals(Source.PHOTO, note.source)
        assertEquals(row.id, note.id)
        assertEquals(row.rawText, note.rawText)
    }

    @Test fun currentSources_roundTripWithoutChange() {
        Source.entries.forEach { source ->
            assertEquals(source, row.copy(source = source.name).toDomain().source)
        }
    }

    @Test fun unknownWireValues_keepTheRecordVisibleWithConservativeDefaults() {
        val note = row.copy(
            direction = "SIDEWAYS",
            status = "FUTURE_STATUS",
            source = "SCANNER_V2",
            priority = "CRITICAL_V2",
        ).toDomain()

        assertEquals(Direction.OUTGOING, note.direction)
        assertEquals(Status.OPEN, note.status)
        assertEquals(Source.TEXT, note.source)
        assertEquals(Priority.NORMAL, note.priority)
        assertEquals(row.id, note.id)
    }

    @Test fun malformedJournal_doesNotBlankReadOnlyUiButStrictWritesStillRefuseIt() {
        val note = row.copy(updatesJson = "{not-json").toDomain()
        assertTrue(note.updates.isEmpty())

        val strictFailure = runCatching { InstructionJournal.decode("{not-json") }.exceptionOrNull()
        assertNotNull("write paths must not silently discard a damaged journal", strictFailure)
    }
}
