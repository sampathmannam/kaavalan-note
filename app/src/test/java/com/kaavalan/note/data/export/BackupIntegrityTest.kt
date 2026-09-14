package com.kaavalan.note.data.export

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupIntegrityTest {

    @Test
    fun `bounded reader accepts a file at the byte limit`() {
        val payload = "தமிழ்".toByteArray(Charsets.UTF_8)
        val decoded = ByteArrayInputStream(payload).use { input ->
            BackupIntegrity.readUtf8(input, maxBytes = payload.size)
        }
        assertEquals("தமிழ்", decoded)
    }

    @Test
    fun `bounded reader refuses oversized input before parsing`() {
        val failure = ByteArrayInputStream(ByteArray(9)).use { input ->
            runCatching { BackupIntegrity.readUtf8(input, maxBytes = 8) }.exceptionOrNull()
        }
        assertNotNull(failure)
        assertTrue(failure?.message?.contains("too large") == true)
        assertTrue(failure?.message?.contains("Nothing has been changed") == true)
    }
}
