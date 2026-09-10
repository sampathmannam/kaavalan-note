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
    fun decode(value: String): List<InstructionUpdate> = json.decodeFromString(value)
    fun encode(updates: List<InstructionUpdate>): String = json.encodeToString(updates)
}
