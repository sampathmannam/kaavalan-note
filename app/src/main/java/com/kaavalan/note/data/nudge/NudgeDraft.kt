package com.kaavalan.note.data.nudge

import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.local.NudgeDraftDao
import com.kaavalan.note.data.local.entities.NudgeDraftEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.person.Person
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M4-T4 + v1.1: nudge draft generator. Template-based v1 with
 * per-tone templates (polite / urgent / casual) — the same three
 * tones the cloud MCP `draft_nudge` tool exposes. The on-device
 * llama.cpp LLM is a refinement path that doesn't change the
 * [NudgeDraftEntity] shape.
 */
@Singleton
open class NudgeDraftGenerator @Inject constructor(
    private val dao: NudgeDraftDao,
) {

    fun observeFor(instructionId: String): Flow<List<NudgeDraft>> =
        dao.observeForInstruction(instructionId)
            .map { rows -> rows.map { it.toDomain() } }

    /**
     * Generate a draft with the default [Tone.POLITE]. The on-device
     * LLM refinement is a drop-in; for now the template is good
     * enough to ship.
     */
    suspend fun generate(
        instruction: Instruction,
        person: Person?,
        tone: Tone = Tone.POLITE,
    ): NudgeDraft {
        val draftText = buildTemplate(
            instruction = instruction,
            person = person,
            tone = tone,
        )
        val now = java.time.Instant.now().toString()
        val row = NudgeDraftEntity(
            id = java.util.UUID.randomUUID().toString(),
            instructionId = instruction.id,
            draftText = draftText,
            status = "DRAFT",
            sentVia = null,
            sentAt = null,
            createdAt = now,
            syncStatus = SyncStatus.SYNCED,
        )
        dao.upsert(row)
        return row.toDomain()
    }

    /**
     * v1.1: re-generate the draft with a different tone. Replaces
     * the current draft text (the user hasn't edited it — if they
     * have, the VM saves the new text via [updateText] and this
     * call is ignored at the call site).
     */
    suspend fun regenerate(
        id: String,
        instruction: Instruction,
        person: Person?,
        tone: Tone,
    ): NudgeDraft {
        val draftText = buildTemplate(
            instruction = instruction,
            person = person,
            tone = tone,
        )
        dao.updateText(id, draftText)
        // Return a NudgeDraft reflecting the new text. The DAO
        // doesn't expose a getById for the draft row; the caller
        // is expected to re-observe the flow.
        return NudgeDraft(
            id = id,
            instructionId = instruction.id,
            draftText = draftText,
            status = "DRAFT",
            sentVia = null,
            sentAt = null,
            createdAt = java.time.Instant.now().toString(),
        )
    }

    suspend fun updateText(id: String, text: String) {
        dao.updateText(id, text)
    }

    suspend fun markSent(id: String, sentVia: String) {
        val now = java.time.Instant.now().toString()
        dao.markSent(id, sentVia, now)
    }

    suspend fun recordPreparation(id: String, action: String) {
        require(action == "COPY" || action == "SHARE")
        dao.recordPreparation(id, if (action == "COPY") "COPIED" else "SHARE_OPENED", action, java.time.Instant.now().toString())
    }

    suspend fun cancel(id: String) {
        dao.cancel(id)
    }

    /**
     * v1.1: per-tone template. v1.0 had one template for everything;
     * the user wanted to be able to send a more urgent nudge for a
     * 3-day-old OUTGOING that was stalling. The MCP `draft_nudge`
     * tool already exposed three tones; the local generator now
     * matches.
     *
     * Tone distinctions:
     *  - POLITE: default. "Hi X — following up on Y. Let me know..."
     *  - URGENT: "X — I need Y by end of day. What's blocking?"
     *  - CASUAL: "Hey X — quick nudge on Y. Ping me when you can."
     *
     * The "name" includes the designation when present (e.g. "SHO
     * Ramu") so the recipient recognises the addressee. Indian
     * police officers coordinate by designation more than by
     * given name, so this is the natural form.
     */
    private fun buildTemplate(
        instruction: Instruction,
        person: Person?,
        tone: Tone,
    ): String {
        val name = (person?.let { p ->
            listOfNotNull(p.designation, p.name).joinToString(" ").takeIf { it.isNotBlank() }
        } ?: person?.name?.takeIf { it.isNotBlank() }) ?: "there"
        val title = instruction.title.ifBlank { instruction.rawText.take(60) }
        return when (tone) {
            Tone.POLITE ->
                "Hi $name — following up on \"$title\". " +
                    "Let me know if you need anything from me."
            Tone.URGENT ->
                "$name — I need \"$title\" by end of day. " +
                    "Let me know what's blocking."
            Tone.CASUAL ->
                "Hey $name — quick nudge on \"$title\". " +
                    "Ping me when you can."
        }
    }
}

data class NudgeDraft(
    val id: String,
    val instructionId: String,
    val draftText: String,
    val status: String,
    val sentVia: String?,
    val sentAt: String?,
    val createdAt: String,
)

/**
 * v1.1: the three nudge tones. Matches the cloud `draft_nudge` MCP
 * tool. Stored implicitly via the draft text (the v1.1 `NudgeDraft`
 * doesn't carry the tone — it's a runtime param, not a persisted
 * state). The `tone` of a sent nudge is reconstructable from the
 * audit trail when the LLM refinement is in place.
 */
enum class Tone { POLITE, URGENT, CASUAL }

internal fun NudgeDraftEntity.toDomain(): NudgeDraft = NudgeDraft(
    id = id,
    instructionId = instructionId,
    draftText = draftText,
    status = status,
    sentVia = sentVia,
    sentAt = sentAt,
    createdAt = createdAt,
)
