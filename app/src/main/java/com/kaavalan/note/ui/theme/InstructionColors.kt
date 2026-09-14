package com.kaavalan.note.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.kaavalan.note.data.instructions.Status

/** Status colours are distinct from action colours and always accompany a written label. */
internal data class InstructionColors(val container: Color, val content: Color) {
    // Opaque, subtle tints keep card contrast independent of the parent's background.
    fun cardSurface(scheme: ColorScheme, featured: Boolean): Color =
        lerp(scheme.surface, container, if (featured) 0.28f else 0.12f)
}

internal fun instructionColors(status: Status, scheme: ColorScheme): InstructionColors = when (status) {
    Status.OPEN, Status.CARRIED_OVER -> InstructionColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    Status.IN_PROGRESS -> InstructionColors(scheme.primaryContainer, scheme.onPrimaryContainer)
    Status.ACK_PENDING, Status.WAITING_ON_OTHER -> if (isDarkSurface(scheme.surface)) {
        InstructionColors(Color(0xFF514123), Color(0xFFF1D9A8))
    } else {
        InstructionColors(Color(0xFFF3E4C5), Color(0xFF60430E))
    }
    Status.REPORTED_DONE -> InstructionColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    Status.DONE, Status.DROPPED -> InstructionColors(scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
}
