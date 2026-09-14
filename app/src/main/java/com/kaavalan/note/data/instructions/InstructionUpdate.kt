package com.kaavalan.note.data.instructions

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private journal owned by one instruction; never a delivery receipt or team conversation. */
@Serializable
data class InstructionUpdate(
    val id: String,
    val at: String,
    val text: String,
    val status: String,
    val nextFollowUpAtMs: Long?,
    val previousText: String? = null,
)

object InstructionJournal {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Strict decoder for every path that may write the journal back to disk. A damaged
     * history must stop an edit instead of being silently replaced with a shorter one.
     */
    fun decode(value: String): List<InstructionUpdate> = json.decodeFromString(value)

    /**
     * Best-effort decoder for read-only UI projections. One damaged legacy row should
     * not make every healthy instruction disappear from the workspace.
     */
    fun decodeForDisplay(value: String): List<InstructionUpdate> =
        runCatching { decode(value) }.getOrDefault(emptyList())

    fun encode(updates: List<InstructionUpdate>): String = json.encodeToString(updates)
}
