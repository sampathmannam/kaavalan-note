package com.kaavalan.note.data.instructions

import com.kaavalan.note.data.local.entities.InstructionEntity
import org.junit.Assert.assertEquals
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
}
