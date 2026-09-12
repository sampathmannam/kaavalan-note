package com.kaavalan.note.data.subdivision

import androidx.room.withTransaction
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.InstructionJournal
import com.kaavalan.note.data.instructions.InstructionUpdate
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single write boundary for the officer's own subdivision record.
 *
 * Three rules hold for every method here:
 *
 *  1. **Re-read, never trust the caller's object.** A picker row the officer tapped ten
 *     seconds ago may since have been archived, moved to the other vault, or deleted.
 *     Ownership and sensitivity are rechecked against the row on disk at save time.
 *  2. **One transaction per action.** Every guard runs inside the same
 *     [androidx.room.withTransaction] block as the writes it protects, so a rejected
 *     guard rolls the whole action back instead of leaving half a change behind.
 *  3. **Archive, never destroy.** Nothing here deletes a station, matter, posting or
 *     review. Archiving is reversible and keeps the officer's history intact.
 *
 * Failure messages are written for the officer, not for a log: each one says what to do
 * next. They surface verbatim in the UI.
 */
@Singleton
class SubdivisionRepository @Inject constructor(
    private val db: AppDatabase,
    private val vault: VaultModeHolder,
) {
    private val dao get() = db.subdivisionDao()

    /**
     * The CRM is a normal-workspace feature in this version. Refusing here — rather than
     * only hiding the entry points — is what makes a vault switch during an open form
     * fail closed instead of writing a normal record from a private session.
     */
    private fun requireNormalWorkspace() = require(vault.mode.value == VaultMode.Visible) {
        "Return to the normal workspace to manage your subdivision."
    }

    // ---- profile ----

    suspend fun saveProfile(name: String, district: String, officer: String) = db.withTransaction {
        requireNormalWorkspace()
        require(name.isNotBlank()) { "Enter your subdivision name." }
        require(name.length <= 120 && district.length <= 120 && officer.length <= 120) {
            "Keep the subdivision, district and officer names within 120 characters."
        }
        dao.saveProfile(SubdivisionProfile(VISIBLE, name.trim(), district.trim(), officer.trim(), now()))
    }

    // ---- stations / units ----

    /**
     * Create or rename a station. Uniqueness covers archived rows too, so a name cannot
     * be quietly duplicated by archiving the original first.
     *
     * A rename updates the compatibility display text on the station's contacts, but
     * never rewrites a posting-history snapshot: "moved from Subedari" stays true even
     * after Subedari is renamed.
     */
    suspend fun saveStation(id: String?, name: String, kind: String, notes: String): String = db.withTransaction {
        requireNormalWorkspace()
        require(name.isNotBlank() && name.trim().length <= 120) {
            "Enter a station or unit name of up to 120 characters."
        }
        require(kind.isNotBlank() && kind.trim().length <= 60) { "Enter a unit type, for example Station." }
        require(notes.length <= NOTE_LIMIT) { "Keep the notes within $NOTE_LIMIT characters." }
        val existing = id?.let { requireStation(it) }
        val clash = dao.stationByName(VISIBLE, stationKey(name))
        require(clash == null || clash.id == existing?.id) {
            if (clash?.archived == true) {
                "\"${clash.name}\" already exists but is archived. Reopen it instead of adding it again."
            } else {
                "\"${clash?.name}\" already exists in your subdivision."
            }
        }
        val row = Station(
            id = existing?.id ?: UUID.randomUUID().toString(),
            vaultMode = VISIBLE,
            name = name.trim(),
            nameKey = stationKey(name),
            kind = kind.trim(),
            notes = notes.trim(),
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: now(),
            updatedAt = now(),
        )
        dao.saveStation(row)
        if (existing != null && existing.name != row.name) {
            db.personDao().snapshot()
                .filter { it.vaultMode == VISIBLE && it.stationId == row.id }
                .forEach { db.personDao().updateExisting(it.copy(station = row.name, updatedAt = now())) }
        }
        row.id
    }

    /**
     * Archiving is blocked while the station still holds work or people, and each block
     * says which one it is so the officer knows the next step. Reopening is unconditional.
     */
    suspend fun archiveStation(id: String, archived: Boolean) = db.withTransaction {
        requireNormalWorkspace()
        val row = requireStation(id)
        if (archived) {
            require(db.personDao().snapshot().none { it.stationId == id && it.isStaff && it.staffActive }) {
                "This station still has active staff. Move them to another station or mark their posting inactive first."
            }
            require(visibleWork().none { it.stationId == id && it.isOpen }) {
                "This station still has open instructions. Complete them, or move them to another station first."
            }
            require(dao.mattersInMode(VISIBLE).none { it.stationId == id && !it.archived }) {
                "This station still has active matters. Archive those matters first."
            }
        }
        dao.saveStation(row.copy(archived = archived, updatedAt = now()))
    }

    // ---- staff ----

    /**
     * Classify an existing contact as staff, set their current posting and responsibilities.
     *
     * Their existing instructions are deliberately left where they are: work belongs to the
     * station it was recorded at, and a transfer is not a reason to rewrite history. Every
     * change appends a dated posting entry.
     */
    suspend fun saveStaff(
        personId: String,
        stationId: String?,
        responsibilities: String,
        isStaff: Boolean,
        active: Boolean,
    ) = db.withTransaction {
        requireNormalWorkspace()
        require(responsibilities.length <= NOTE_LIMIT) { "Keep the responsibilities within $NOTE_LIMIT characters." }
        val person = requireNotNull(db.personDao().getById(personId)) {
            "This contact is no longer available."
        }
        require(person.vaultMode == VISIBLE && !person.isSensitive) {
            "This contact is not available in the normal subdivision workspace."
        }
        val target = stationId?.let { requireStation(it) }
        require(target == null || !target.archived || target.id == person.stationId) {
            "\"${target?.name}\" is archived. Reopen it before posting staff there."
        }
        val cleanResponsibilities = responsibilities.trim()
        val stationChanged = person.stationId != stationId
        val changed = stationChanged || person.isStaff != isStaff ||
            person.staffActive != active || person.responsibilities != cleanResponsibilities
        if (changed) {
            dao.savePosting(
                StaffPosting(
                    id = UUID.randomUUID().toString(),
                    vaultMode = VISIBLE,
                    personId = person.id,
                    fromStation = person.station.orEmpty(),
                    toStation = target?.name.orEmpty(),
                    note = postingNote(isStaff, active, stationChanged, cleanResponsibilities),
                    recordedAt = now(),
                ),
            )
        }
        db.personDao().updateExisting(
            person.copy(
                stationId = stationId,
                station = target?.name,
                isStaff = isStaff,
                staffActive = active,
                responsibilities = cleanResponsibilities,
                updatedAt = now(),
                syncStatus = SyncStatus.PENDING_UPDATE,
            ),
        )
    }

    private fun postingNote(
        isStaff: Boolean,
        active: Boolean,
        stationChanged: Boolean,
        responsibilities: String,
    ): String {
        val head = when {
            !isStaff -> "Removed from the staff list"
            !active -> "Posting marked inactive"
            stationChanged -> "Posting changed"
            else -> "Responsibilities updated"
        }
        return if (responsibilities.isEmpty()) head else "$head. Responsibilities: $responsibilities"
    }

    // ---- matters ----

    suspend fun saveMatter(
        id: String?,
        title: String,
        stationId: String?,
        reference: String,
        description: String,
    ): String = db.withTransaction {
        requireNormalWorkspace()
        require(title.isNotBlank() && title.trim().length <= 180) {
            "Enter a matter title of up to 180 characters."
        }
        require(reference.trim().length <= 120) { "Keep the reference within 120 characters." }
        require(description.length <= NOTE_LIMIT) { "Keep the context within $NOTE_LIMIT characters." }
        val existing = id?.let { requireMatter(it) }
        val unit = stationId?.let { requireStation(it) }
        require(unit == null || !unit.archived || existing?.stationId == stationId) {
            "\"${unit?.name}\" is archived. Reopen it before adding matters there."
        }
        if (existing != null && existing.stationId != stationId) {
            require(db.instructionDao().snapshot().none { it.matterId == existing.id }) {
                "This matter already has instructions recorded against its station. " +
                    "Keep its station and move individual instructions explicitly instead."
            }
        }
        val row = Matter(
            id = existing?.id ?: UUID.randomUUID().toString(),
            vaultMode = VISIBLE,
            title = title.trim(),
            stationId = stationId,
            reference = reference.trim(),
            description = description.trim(),
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: now(),
            updatedAt = now(),
        )
        dao.saveMatter(row)
        row.id
    }

    suspend fun archiveMatter(id: String, archived: Boolean) = db.withTransaction {
        requireNormalWorkspace()
        val row = requireMatter(id)
        if (archived) {
            require(visibleWork().none { it.matterId == id && it.isOpen }) {
                "This matter still has open instructions. Complete them, or unlink them first."
            }
        } else {
            // Reopening a matter reopens the station it belongs to, so the officer is not
            // left with an active matter parked under an archived unit.
            row.stationId?.let { dao.station(it) }
                ?.takeIf { it.vaultMode == VISIBLE && it.archived }
                ?.let { dao.saveStation(it.copy(archived = false, updatedAt = now())) }
        }
        dao.saveMatter(row.copy(archived = archived, updatedAt = now()))
    }

    // ---- work context ----

    /** Change one instruction's recorded station / matter. Responsibility and dates are untouched. */
    suspend fun linkInstruction(instructionId: String, stationId: String?, matterId: String?) = db.withTransaction {
        requireNormalWorkspace()
        relink(db, instructionId, stationId, matterId)
    }

    // ---- reviews ----

    /**
     * Record a dated review note. The open / ready counts are computed and stored inside
     * this transaction, so the number saved is the number that was true at save time. This
     * writes a note; it does not complete work and it sends nothing to anyone.
     */
    suspend fun saveReview(scopeKey: String, notes: String): String = db.withTransaction {
        requireNormalWorkspace()
        require(notes.isNotBlank()) { "Write a short note for this review." }
        require(notes.length <= REVIEW_LIMIT) { "Keep the review note within $REVIEW_LIMIT characters." }
        require(SubdivisionProjections.isKnownScope(scopeKey)) { "Choose a valid review scope." }
        val title = scopeTitle(scopeKey)
        val counts = SubdivisionProjections.counts(SubdivisionProjections.inScope(visibleWork(), scopeKey))
        val row = SubdivisionReview(
            id = UUID.randomUUID().toString(),
            vaultMode = VISIBLE,
            scopeKey = scopeKey,
            scopeTitle = title,
            notes = notes.trim(),
            recordedAt = now(),
            openCount = counts.open,
            readyCount = counts.readyToVerify,
        )
        dao.saveReview(row)
        row.id
    }

    private suspend fun scopeTitle(scopeKey: String): String {
        SubdivisionProjections.stationIdOf(scopeKey)?.let { return requireStation(it).name }
        SubdivisionProjections.personIdOf(scopeKey)?.let { id ->
            val person = requireNotNull(db.personDao().getById(id)) { "This officer is no longer available." }
            require(person.vaultMode == VISIBLE && !person.isSensitive) {
                "This officer is not available in the normal subdivision workspace."
            }
            return person.name
        }
        return "Whole subdivision"
    }

    // ---- shared helpers ----

    private suspend fun requireStation(id: String): Station =
        requireNotNull(dao.station(id)?.takeIf { it.vaultMode == VISIBLE }) {
            "That station or unit is not available in your subdivision."
        }

    private suspend fun requireMatter(id: String): Matter =
        requireNotNull(dao.matter(id)?.takeIf { it.vaultMode == VISIBLE }) {
            "That matter is not available in your subdivision."
        }

    /** Every normal-workspace instruction, with hidden and sensitive records already excluded. */
    private suspend fun visibleWork(): List<Instruction> {
        val ids = db.personDao().snapshot()
            .filter { it.vaultMode == VISIBLE && !it.isSensitive }
            .mapTo(mutableSetOf()) { it.id }
        return SubdivisionProjections.normalWorkspaceWork(
            db.instructionDao().snapshot().map { it.toDomain() },
            ids,
        )
    }

    private val Instruction.isOpen: Boolean
        get() = with(SubdivisionProjections) { isOpenWork }

    /** The resolved work context for a new instruction. */
    data class CreationContext(val stationId: String?, val matterId: String?)

    companion object {
        const val VISIBLE = "visible"
        private const val NOTE_LIMIT = 10_000
        private const val REVIEW_LIMIT = 20_000

        private fun now() = Instant.now().toString()

        /**
         * Fold a station name to its uniqueness key.
         *
         * ASCII case only, matching SQLite's `lower()` — which is what the 17→18 migration
         * used to deduplicate existing contact station text. Kotlin's `lowercase()` would
         * also fold Turkish, Greek and other scripts, and the two would then disagree about
         * whether a migrated row and a newly typed name are the same station. A Tamil or
         * Devanagari name keeps every character exactly as the officer typed it.
         */
        fun stationKey(name: String): String =
            name.trim().map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")

        /**
         * Find the stable station ID for a free-text station name, creating the station on
         * first use. Called from ordinary contact entry and phone-contact import, so a
         * contact saved before the officer ever opens the CRM still gets a real station.
         *
         * An archived station with the same name is reused and reopened rather than
         * duplicated — the officer is actively putting somebody there.
         *
         * The caller owns the transaction.
         */
        suspend fun resolveStation(db: AppDatabase, mode: String, name: String?): String? {
            val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val dao = db.subdivisionDao()
            val key = stationKey(clean)
            dao.stationByName(mode, key)?.let { existing ->
                if (existing.archived) dao.saveStation(existing.copy(archived = false, updatedAt = now()))
                return existing.id
            }
            val row = Station(
                id = UUID.randomUUID().toString(),
                vaultMode = mode,
                name = clean,
                nameKey = key,
                createdAt = now(),
                updatedAt = now(),
            )
            dao.saveStation(row)
            return row.id
        }

        /**
         * Resolve the work context to stamp on a brand-new instruction.
         *
         * With no explicit context, the instruction snapshots the responsible contact's
         * current station. With one, the station and matter are validated here — inside the
         * caller's insert transaction — so an unavailable context aborts the creation
         * instead of producing a saved instruction that silently lost its link.
         */
        suspend fun resolveCreationContext(
            db: AppDatabase,
            personId: String?,
            stationId: String?,
            matterId: String?,
        ): CreationContext {
            val person = personId?.let { db.personDao().getById(it) }
            if (stationId == null && matterId == null) {
                val resolved = person?.let { it.stationId ?: resolveStation(db, it.vaultMode, it.station) }
                return CreationContext(resolved, null)
            }
            require(person == null || (person.vaultMode == VISIBLE && !person.isSensitive)) {
                "This contact is not available in the normal subdivision workspace."
            }
            val dao = db.subdivisionDao()
            val matter = matterId?.let {
                requireNotNull(dao.matter(it)?.takeIf { row -> row.vaultMode == VISIBLE }) {
                    "That matter is not available in your subdivision."
                }
            }
            require(matter?.archived != true) { "\"${matter?.title}\" is archived. Reopen it before adding work." }
            val station = stationId?.let {
                requireNotNull(dao.station(it)?.takeIf { row -> row.vaultMode == VISIBLE }) {
                    "That station or unit is not available in your subdivision."
                }
            }
            require(station?.archived != true) { "\"${station?.name}\" is archived. Reopen it before adding work." }
            require(matter?.stationId == null || matter.stationId == stationId) {
                "\"${matter?.title}\" belongs to a different station. Use that station for this instruction."
            }
            return CreationContext(stationId, matterId)
        }

        /**
         * Move one existing instruction to a station / matter, appending a journal entry
         * that names both the old and the new context. Never changes who is responsible and
         * never touches a deadline or reminder.
         *
         * Fails closed on a sensitive record, on a link that reaches a contact in another
         * vault (including through the audience pointer), and on an archived or missing
         * station or matter.
         *
         * The caller owns the transaction.
         */
        suspend fun relink(db: AppDatabase, instructionId: String, stationId: String?, matterId: String?) {
            val row = requireNotNull(db.instructionDao().getById(instructionId)) {
                "This instruction is no longer available."
            }
            require(!row.isSensitive) { "Private instructions are not part of the subdivision workspace." }
            listOfNotNull(row.personId, row.audienceTarget.takeIf { row.audienceKind == "PERSON" }).forEach { id ->
                val person = db.personDao().getById(id)
                require(person != null && person.vaultMode == VISIBLE && !person.isSensitive) {
                    "This instruction belongs to another workspace."
                }
            }
            val dao = db.subdivisionDao()
            val matter = matterId?.let {
                requireNotNull(dao.matter(it)?.takeIf { m -> m.vaultMode == VISIBLE }) {
                    "That matter is not available in your subdivision."
                }
            }
            require(matter?.archived != true || row.matterId == matterId) {
                "\"${matter?.title}\" is archived. Reopen it before linking instructions."
            }
            require(matter?.stationId == null || matter.stationId == stationId) {
                "\"${matter?.title}\" belongs to a different station. Use that station for this instruction."
            }
            val station = stationId?.let {
                requireNotNull(dao.station(it)?.takeIf { s -> s.vaultMode == VISIBLE }) {
                    "That station or unit is not available in your subdivision."
                }
            }
            require(station?.archived != true || row.stationId == stationId) {
                "\"${station?.name}\" is archived. Reopen it before linking instructions."
            }
            if (row.stationId == stationId && row.matterId == matterId) return
            val beforeStation = row.stationId?.let { dao.station(it)?.name } ?: NO_STATION
            val beforeMatter = row.matterId?.let { dao.matter(it)?.title } ?: NO_MATTER
            val at = now()
            val entry = InstructionUpdate(
                id = UUID.randomUUID().toString(),
                at = at,
                text = "Work context changed: $beforeStation / $beforeMatter → " +
                    "${station?.name ?: NO_STATION} / ${matter?.title ?: NO_MATTER}",
                status = row.status,
                nextFollowUpAtMs = row.dueAtMs,
            )
            db.instructionDao().updateExisting(
                row.copy(
                    stationId = stationId,
                    matterId = matterId,
                    updatedAt = at,
                    syncStatus = SyncStatus.PENDING_UPDATE,
                    updatesJson = InstructionJournal.encode(InstructionJournal.decode(row.updatesJson) + entry),
                ),
            )
        }

        /**
         * Reopening work reopens the context it sits in, so an instruction can never be
         * open under an archived station or matter. Shared by reopen and by undoing a
         * completion — the officer may have archived the station in between.
         *
         * The caller owns the transaction.
         */
        suspend fun reactivateContext(db: AppDatabase, stationId: String?, matterId: String?) {
            val dao = db.subdivisionDao()
            stationId?.let { dao.station(it) }?.takeIf { it.archived }
                ?.let { dao.saveStation(it.copy(archived = false, updatedAt = now())) }
            matterId?.let { dao.matter(it) }?.takeIf { it.archived }
                ?.let { dao.saveMatter(it.copy(archived = false, updatedAt = now())) }
        }

        const val NO_STATION = "No station"
        const val NO_MATTER = "No matter"
    }
}
