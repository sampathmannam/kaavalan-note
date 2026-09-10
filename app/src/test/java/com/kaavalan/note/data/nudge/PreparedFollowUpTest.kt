package com.kaavalan.note.data.nudge

import com.kaavalan.note.data.local.NudgeDraftDao
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PreparedFollowUpTest {
    @Test fun `copy and share are preparation not confirmed delivery`() = runTest {
        val dao = mockk<NudgeDraftDao>(relaxed = true)
        val generator = NudgeDraftGenerator(dao)
        generator.recordPreparation("copy", "COPY")
        generator.recordPreparation("share", "SHARE")
        coVerify { dao.recordPreparation("copy", "COPIED", "COPY", any()) }
        coVerify { dao.recordPreparation("share", "SHARE_OPENED", "SHARE", any()) }
        coVerify(exactly = 0) { dao.markSent(any(), any(), any()) }
    }
}
