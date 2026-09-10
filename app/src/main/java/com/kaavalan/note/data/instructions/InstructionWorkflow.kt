package com.kaavalan.note.data.instructions

import androidx.room.withTransaction
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionFtsEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.reminder.ReminderManager
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CompletionUndo(val id: String, val previousStatus: String, val completedAt: String)

/** Write boundary for the officer workspace. Re-reads and checks vault ownership on every action. */
@Singleton
class InstructionWorkflow @Inject constructor(
    private val db: AppDatabase,
    private val reminders: ReminderManager,
    private val vault: VaultModeHolder,
) {
    private fun InstructionEntity.reminder(): Long? = dueAtMs ?: dueAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    private suspend fun scoped(id: String): InstructionEntity {
        val row = requireNotNull(db.instructionDao().getById(id))
        require(!row.isSensitive)
        val audienceId = row.audienceTarget.takeIf { row.audienceKind == "PERSON" }
        val ids = listOfNotNull(row.personId, audienceId)
        require(ids.isNotEmpty() || vault.mode.value == VaultMode.Visible)
        ids.forEach { require(db.personDao().getById(it)?.vaultMode == vault.mode.value.storageKey) }
        return row
    }

    private suspend fun persist(row: InstructionEntity) {
        db.instructionDao().updateExisting(row.copy(syncStatus = SyncStatus.PENDING_UPDATE))
        val rowid = requireNotNull(db.instructionFtsDao().rowidForInstruction(row.id))
        db.instructionFtsDao().upsert(InstructionFtsEntity(rowid, row.title, row.rawText, row.personId, row.capturedAt))
    }

    suspend fun edit(id: String, text: String, direction: Direction, personId: String?, deadlineAtMs: Long?) = db.withTransaction {
        require(text.isNotBlank())
        val row = scoped(id)
        if (personId != null) require(db.personDao().getById(personId)?.vaultMode == vault.mode.value.storageKey)
        else require(vault.mode.value == VaultMode.Visible)
        val now = Instant.now().toString()
        val relinked = row.personId != personId
        val person = personId?.let { db.personDao().getById(it) }
        val entry = InstructionUpdate(UUID.randomUUID().toString(), now, "Instruction details edited", row.status,
            row.reminder(), row.rawText)
        persist(row.copy(rawText = text.trim(), title = text.trim().take(80), direction = direction.name,
            personId = personId, deadlineAtMs = deadlineAtMs, updatedAt = now,
            audienceKind = if (relinked) person?.let { "PERSON" } else row.audienceKind,
            audienceTarget = if (relinked) personId else row.audienceTarget,
            audienceLabel = if (relinked) person?.name else row.audienceLabel,
            audienceIsBroadcast = if (relinked) false else row.audienceIsBroadcast,
            updatesJson = InstructionJournal.encode(InstructionJournal.decode(row.updatesJson) + entry)))
    }

    suspend fun addUpdate(id: String, text: String, status: Status, followUp: Long?) {
        require(text.isNotBlank())
        require(status in progressStates)
        require(followUp == null || followUp > System.currentTimeMillis())
        db.withTransaction {
            val row = scoped(id)
            require(row.status != Status.DONE.name && row.status != Status.DROPPED.name)
            val now = Instant.now().toString()
            val entry = InstructionUpdate(UUID.randomUUID().toString(), now, text.trim(), status.name, followUp)
            persist(row.copy(status = status.name, updatedAt = now, dueAtMs = followUp,
                dueAt = followUp?.let { Instant.ofEpochMilli(it).toString() },
                updatesJson = InstructionJournal.encode(InstructionJournal.decode(row.updatesJson) + entry)))
        }
        reminders.scheduleSaved(id, followUp)
    }

    suspend fun complete(id: String): CompletionUndo {
        val undo = db.withTransaction {
            val row = scoped(id)
            require(row.status != Status.DONE.name && row.status != Status.DROPPED.name)
            val now = Instant.now().toString()
            persist(row.copy(status = Status.DONE.name, completedAt = now, droppedReason = null, updatedAt = now))
            CompletionUndo(id, row.status, now)
        }
        reminders.cancelDelivery(id)
        return undo
    }

    suspend fun undoCompletion(undo: CompletionUndo) {
        val reminder = db.withTransaction {
            val row = scoped(undo.id)
            // A newer lifecycle action must never be overwritten by a stale snackbar.
            require(row.status == Status.DONE.name && row.completedAt == undo.completedAt)
            persist(row.copy(status = undo.previousStatus, completedAt = null, updatedAt = Instant.now().toString()))
            row.reminder()
        }
        reminders.scheduleSaved(undo.id, reminder)
    }

    suspend fun editContact(id: String, name: String, rank: String, station: String, phone: String) = db.withTransaction {
        require(name.isNotBlank())
        val person = requireNotNull(db.personDao().getById(id))
        require(person.vaultMode == vault.mode.value.storageKey)
        db.personDao().updateLocal(id, name.trim(), rank.trim().ifBlank { null }, station.trim().ifBlank { null },
            phone.trim().ifBlank { null }, Instant.now().toString(), SyncStatus.PENDING_UPDATE)
    }

    suspend fun changeReminder(id: String, at: Long?) {
        require(at == null || at > System.currentTimeMillis())
        db.withTransaction {
            val row = scoped(id)
            require(row.status != "DONE" && row.status != "DROPPED")
            persist(row.copy(dueAtMs = at, dueAt = at?.let { Instant.ofEpochMilli(it).toString() }, updatedAt = Instant.now().toString()))
        }
        reminders.scheduleSaved(id, at)
    }

    suspend fun close(id: String) {
        db.withTransaction {
            val row = scoped(id)
            persist(row.copy(status = "DROPPED", completedAt = null, droppedReason = null, updatedAt = Instant.now().toString()))
        }
        reminders.cancelDelivery(id)
    }

    suspend fun reopen(id: String) {
        val at = db.withTransaction {
            val row = scoped(id)
            require(row.status == "DONE" || row.status == "DROPPED")
            val now = Instant.now().toString()
            val entry = InstructionUpdate(UUID.randomUUID().toString(), now, "Instruction reopened", "OPEN", row.reminder())
            persist(row.copy(status = "OPEN", completedAt = null, droppedReason = null, updatedAt = now,
                updatesJson = InstructionJournal.encode(InstructionJournal.decode(row.updatesJson) + entry)))
            row.reminder()
        }
        reminders.scheduleSaved(id, at)
    }

    companion object {
        val progressStates = listOf(Status.OPEN, Status.ACK_PENDING, Status.IN_PROGRESS, Status.WAITING_ON_OTHER, Status.REPORTED_DONE)
    }
}
