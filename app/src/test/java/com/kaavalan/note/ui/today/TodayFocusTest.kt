package com.kaavalan.note.ui.today

import com.kaavalan.note.data.brief.BriefType
import com.kaavalan.note.data.brief.DailyBrief
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.instructions.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayFocusTest {

    @Test
    fun needsYouTakesPrecedenceAndIsNotRepeated() {
        val firstNeed = instruction("need-1")
        val secondNeed = instruction("need-2")
        val waiting = instruction("waiting-1")

        val focus = focusFirst(
            brief(needs = listOf(firstNeed, secondNeed), waiting = listOf(waiting)),
        )

        assertEquals(firstNeed, focus.instruction)
        assertTrue(focus.needsYou)
        assertEquals(listOf(secondNeed), focus.remainingNeeds)
        assertEquals(listOf(waiting), focus.remainingWaiting)
        assertFalse(focus.remainingNeeds.contains(firstNeed))
    }

    @Test
    fun waitingIsFocusedWhenThereIsNoNeedAndIsNotRepeated() {
        val firstWaiting = instruction("waiting-1")
        val secondWaiting = instruction("waiting-2")

        val focus = focusFirst(
            brief(needs = emptyList(), waiting = listOf(firstWaiting, secondWaiting)),
        )

        assertEquals(firstWaiting, focus.instruction)
        assertFalse(focus.needsYou)
        assertTrue(focus.remainingNeeds.isEmpty())
        assertEquals(listOf(secondWaiting), focus.remainingWaiting)
    }

    @Test
    fun emptyBriefHasNoFocusedInstruction() {
        val focus = focusFirst(brief(needs = emptyList(), waiting = emptyList()))

        assertNull(focus.instruction)
        assertTrue(focus.remainingNeeds.isEmpty())
        assertTrue(focus.remainingWaiting.isEmpty())
    }

    private fun brief(needs: List<Instruction>, waiting: List<Instruction>) = DailyBrief(
        date = "2026-09-07",
        type = BriefType.MORNING,
        needsYouToday = needs,
        waitingOnOthers = waiting,
        carriedOver = emptyList(),
    )

    private fun instruction(id: String) = Instruction(
        id = id,
        personId = null,
        direction = Direction.SELF,
        status = Status.OPEN,
        source = Source.TEXT,
        priority = Priority.NORMAL,
        title = id,
        rawText = id,
        dueAt = null,
        capturedAt = "2026-09-07T08:00:00Z",
        createdAt = "2026-09-07T08:00:00Z",
        updatedAt = "2026-09-07T08:00:00Z",
    )
}
