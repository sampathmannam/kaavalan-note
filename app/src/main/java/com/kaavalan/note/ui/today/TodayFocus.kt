package com.kaavalan.note.ui.today

import com.kaavalan.note.data.brief.DailyBrief
import com.kaavalan.note.data.instructions.Instruction

/**
 * Separates a single calm starting point from the remaining brief. The
 * BriefGenerator already orders each lane; this selector preserves that order
 * and never silently removes an instruction from the lower sections.
 */
internal data class TodayFocus(
    val instruction: Instruction?,
    val needsYou: Boolean,
    val remainingNeeds: List<Instruction>,
    val remainingWaiting: List<Instruction>,
)

internal fun focusFirst(brief: DailyBrief): TodayFocus {
    val needsFirst = brief.needsYouToday.firstOrNull()
    if (needsFirst != null) {
        return TodayFocus(
            instruction = needsFirst,
            needsYou = true,
            remainingNeeds = brief.needsYouToday.drop(1),
            remainingWaiting = brief.waitingOnOthers,
        )
    }

    val waitingFirst = brief.waitingOnOthers.firstOrNull()
    return TodayFocus(
        instruction = waitingFirst,
        needsYou = false,
        remainingNeeds = brief.needsYouToday,
        remainingWaiting = if (waitingFirst != null) brief.waitingOnOthers.drop(1) else brief.waitingOnOthers,
    )
}
