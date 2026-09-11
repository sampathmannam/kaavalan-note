package com.kaavalan.note.data.export

import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.InstructionFtsDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionFtsEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.subdivision.SubdivisionArchive
import com.kaavalan.note.data.subdivision.SubdivisionRepository

/**
 * The checks and the write order shared by the plain importer and the manual backup
 * restore, so the two paths cannot disagree about what a valid file is.
 *
 * Every `require` here runs before any row is written, inside the caller's transaction.
 * The messages are written for the officer and all end the same way - nothing has been
 * changed - because that is the guarantee an atomic restore is worth having.
 */
internal object BackupIntegrity {

    fun requireDistinctIds(ids: List<String>, what: String) {
        val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) {
            "This file lists the same " + what + " twice (" + duplicate?.key + "). Nothing has been changed."
        }
    }

    /**
     * Reject dangling and mismatched references.
     *
     * [knownPersonIds], [knownStationIds] and [knownMatterIds] are the union of what the
     * file carries and what the device already holds, so restoring one half of a pair of
     * files is still accepted while a genuinely broken reference is refused.
     */
    fun requireReferences(
        people: List<PersonEntity>,
        instructions: List<InstructionEntity>,
        knownPersonIds: Set<String>,
        knownStationIds: Set<String>,
        knownMatterIds: Set<String>,
    ) {
        people.forEach { person ->
            person.stationId?.let { stationId ->
                require(stationId in knownStationIds) {
                    "The contact " + quote(person.name) + " is posted to a station that is not in this file. " +
                        "Nothing has been changed."
                }
            }
        }
        instructions.forEach { item ->
            item.personId?.let { personId ->
                require(personId in knownPersonIds) {
                    "An instruction in this file is linked to a contact that does not exist. " +
                        "Nothing has been changed."
                }
            }
            if (item.audienceKind == "PERSON") {
                item.audienceTarget?.let { target ->
                    require(target in knownPersonIds) {
                        "An instruction in this file is addressed to a contact that does not exist. " +
                            "Nothing has been changed."
                    }
                }
            }
            item.stationId?.let { stationId ->
                require(stationId in knownStationIds) {
                    "An instruction in this file is recorded at a station that is not in this file. " +
                        "Nothing has been changed."
                }
            }
            item.matterId?.let { matterId ->
                require(matterId in knownMatterIds) {
                    "An instruction in this file is linked to a matter that is not in this file. " +
                        "Nothing has been changed."
                }
            }
        }
    }

    /** Stations first: matters and postings both point at them. The caller owns the transaction. */
    suspend fun writeSubdivision(db: AppDatabase, archive: SubdivisionArchive) {
        if (archive.isEmpty) return
        val dao = db.subdivisionDao()
        if (archive.stations.isNotEmpty()) dao.saveStations(archive.stations)
        if (archive.profiles.isNotEmpty()) dao.saveProfiles(archive.profiles)
        if (archive.matters.isNotEmpty()) dao.saveMatters(archive.matters)
        if (archive.postings.isNotEmpty()) dao.savePostings(archive.postings)
        if (archive.reviews.isNotEmpty()) dao.saveReviews(archive.reviews)
    }

    /**
     * Rewrite the search index for the instructions just written.
     *
     * The FTS4 table is a free entity with no content link, so Room generates no sync
     * triggers: without this, a restored note exists but cannot be found by searching for
     * a word inside it.
     */
    suspend fun refreshFts(ftsDao: InstructionFtsDao, instructions: List<InstructionEntity>) {
        instructions.forEach { item ->
            val rowid = ftsDao.rowidForInstruction(item.id) ?: return@forEach
            ftsDao.upsert(
                InstructionFtsEntity(
                    rowid = rowid,
                    title = item.title,
                    rawText = item.rawText,
                    personId = item.personId,
                    capturedAt = item.capturedAt,
                ),
            )
        }
    }

    /**
     * Fill in station ids that an older backup could not carry.
     *
     * Contacts restored from a v3-or-earlier file have free-text station names and no
     * station id. Those names are folded into real station rows exactly as the 17-to-18
     * migration does, per vault, so a recovered contact belongs to the subdivision record
     * rather than sitting outside it.
     *
     * Deliberately conservative: only null ids are filled, no staff classification is
     * invented, and no subdivision profile is created. A restored backup must not put a
     * subdivision name or a staffing decision in the officer's mouth.
     *
     * The caller owns the transaction.
     */
    suspend fun deriveLegacyStations(
        db: AppDatabase,
        people: List<PersonEntity>,
        instructions: List<InstructionEntity>,
    ) {
        val resolved = mutableMapOf<String, String>()
        people.forEach { person ->
            if (person.stationId != null) return@forEach
            val name = person.station?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val current = db.personDao().getById(person.id) ?: return@forEach
            if (current.stationId != null) return@forEach
            val stationId = SubdivisionRepository.resolveStation(db, current.vaultMode, name) ?: return@forEach
            resolved[person.id] = stationId
            db.personDao().updateExisting(current.copy(stationId = stationId))
        }
        if (resolved.isEmpty()) return
        instructions.forEach { item ->
            if (item.stationId != null) return@forEach
            val responsible = item.personId
                ?: item.audienceTarget?.takeIf { item.audienceKind == "PERSON" }
                ?: return@forEach
            val stationId = resolved[responsible] ?: return@forEach
            val current = db.instructionDao().getById(item.id) ?: return@forEach
            if (current.stationId != null) return@forEach
            db.instructionDao().updateExisting(current.copy(stationId = stationId))
        }
    }

    private fun quote(value: String) = "\"" + value + "\""
}
