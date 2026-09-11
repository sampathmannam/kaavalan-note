package com.kaavalan.note.data.export

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.kaavalan.note.data.instructions.InstructionJournal
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.InstructionFtsDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.TagDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionFtsEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.local.entities.TagEntity
import com.kaavalan.note.data.subdivision.SubdivisionArchive
import com.kaavalan.note.data.subdivision.SubdivisionArchiveCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The inverse of [PlainExporter]: read a CSV or JSON snapshot from a SAF-chosen URI and
 * write the rows into the local Room DB.
 *
 * **v2.6.0 changed the failure model.** The previous importer parsed row by row and
 * skipped anything it could not read, so a truncated or hand-edited file imported
 * "successfully" while dropping records - and the officer had no way to tell which.
 * Now the whole file is parsed and validated first, and only then applied, in a single
 * Room transaction. Either the import lands completely or the database is untouched.
 *
 * **Idempotent.** Writes go through `@Upsert` (INSERT-then-UPDATE), not INSERT OR
 * REPLACE: replacing a person row would cascade their important dates and relationships
 * away, and replacing an instruction would cascade its labels away. Re-importing the same
 * file is genuinely a no-op.
 *
 * **Search stays correct.** Every imported instruction's FTS row is rewritten inside the
 * same transaction, so restored notes are findable immediately rather than after the next
 * reseed.
 *
 * Still text-only: capture photos, audit-chain rows (append-only by design) and
 * sync_queue rows are not imported.
 */
@Singleton
class PlainImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val personDao: PersonDao,
    private val instructionDao: InstructionDao,
    private val tagDao: TagDao,
    private val ftsDao: InstructionFtsDao,
    private val db: AppDatabase,
) {

    /**
     * Read the file at [uri] and apply it atomically. Returns a [Result] with an
     * [ImportReport] on success, or the underlying [Throwable] on failure - in which case
     * nothing was written.
     */
    suspend fun importFromUri(uri: Uri): Result<ImportReport> = runCatching {
        val text = readText(uri)
        val parsed = if (text.trimStart().removePrefix(CsvCodec.BOM).trimStart().startsWith("{")) {
            parseJson(text)
        } else {
            parseCsv(text)
        }
        apply(parsed)
    }

    private fun readText(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not open input URI: $uri")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Everything the file describes, fully parsed, before anything is written. */
    private data class Parsed(
        val people: List<PersonEntity>,
        val instructions: List<InstructionEntity>,
        val tags: List<TagEntity>,
        val subdivision: SubdivisionArchive,
    )

    // ---- CSV ----

    private fun parseCsv(text: String): Parsed {
        val blocks = CsvCodec.blocks(text)
        var people = emptyList<PersonEntity>()
        var instructions = emptyList<InstructionEntity>()
        var tags = emptyList<TagEntity>()
        var subdivision = SubdivisionArchive()
        blocks.forEach { block ->
            val header = block.headerLine
            when {
                header.startsWith("id,name,designation,station") ->
                    people = block.rows.map { csvToPerson(it) }
                header.startsWith("id,person_id,direction,status") ->
                    instructions = block.rows.map { csvToInstruction(it) }
                header.startsWith("id,name,kind,color") ->
                    tags = block.rows.map { csvToTag(it) }
                header.trim() == PlainExporter.SUBDIVISION_HEADER ->
                    // One quoted record holding the whole archive. Parsed as a unit: a
                    // subdivision that only half-restored would be worse than none.
                    subdivision = SubdivisionArchiveCodec.decode(block.rows.firstOrNull()?.firstOrNull())
            }
        }
        return Parsed(people, instructions, tags, subdivision)
    }

    private fun csvToPerson(cols: List<String>): PersonEntity {
        require(cols.size >= 8) {
            "A contact row in this file is incomplete. Nothing has been changed."
        }
        require(cols[0].isNotBlank() && cols[1].isNotBlank()) {
            "A contact row in this file has no id or name. Nothing has been changed."
        }
        return PersonEntity(
            id = cols[0],
            name = cols[1],
            designation = cols[2].ifEmpty { null },
            station = cols[3].ifEmpty { null },
            phone = cols[4].ifEmpty { null },
            userId = "",
            createdAt = cols[6],
            updatedAt = cols[7],
            isSensitive = cols[5].toBooleanStrictOrNull() ?: false,
            syncStatus = SyncStatus.SYNCED,
            // Appended in v2.6.0. A pre-2.6.0 file simply has no columns here and gets the
            // entity defaults, which is exactly "an ordinary contact, not staff".
            vaultMode = cols.getOrNull(8)?.ifEmpty { null } ?: "visible",
            stationId = cols.getOrNull(9)?.ifEmpty { null },
            isStaff = cols.getOrNull(10)?.toBooleanStrictOrNull() ?: false,
            staffActive = cols.getOrNull(11)?.toBooleanStrictOrNull() ?: true,
            responsibilities = cols.getOrNull(12).orEmpty(),
            tier = cols.getOrNull(13)?.ifEmpty { null } ?: "Active",
            cadenceOverrideDays = cols.getOrNull(14)?.toIntOrNull(),
            lastInteractionAt = cols.getOrNull(15)?.toLongOrNull(),
        )
    }

    private fun csvToInstruction(cols: List<String>): InstructionEntity {
        require(cols.size >= 13) {
            "An instruction row in this file is incomplete. Nothing has been changed."
        }
        require(cols[0].isNotBlank()) {
            "An instruction row in this file has no id. Nothing has been changed."
        }
        val updatesJson = cols.getOrNull(14)?.ifEmpty { null } ?: "[]"
        return InstructionEntity(
            id = cols[0],
            personId = cols[1].ifEmpty { null },
            direction = cols[2],
            status = cols[3],
            source = cols[4],
            priority = cols[5],
            title = cols[6],
            rawText = cols[7],
            dueAt = cols[8].ifEmpty { null },
            capturedAt = cols[9],
            createdAt = cols[10],
            updatedAt = cols[11],
            nextActionAt = cols[12].toLongOrNull(),
            deadlineAtMs = cols.getOrNull(13)?.toLongOrNull(),
            updatesJson = requireJournal(updatesJson),
            dueAtMs = cols.getOrNull(15)?.toLongOrNull(),
            // Appended in v2.6.0. The lifecycle fields below were being silently dropped
            // by the pre-2.6.0 CSV round trip; a completed instruction came back as open.
            stationId = cols.getOrNull(16)?.ifEmpty { null },
            matterId = cols.getOrNull(17)?.ifEmpty { null },
            isSensitive = cols.getOrNull(18)?.toBooleanStrictOrNull() ?: false,
            completedAt = cols.getOrNull(19)?.ifEmpty { null },
            droppedReason = cols.getOrNull(20)?.ifEmpty { null },
            audienceKind = cols.getOrNull(21)?.ifEmpty { null },
            audienceTarget = cols.getOrNull(22)?.ifEmpty { null },
            audienceLabel = cols.getOrNull(23)?.ifEmpty { null },
            audienceIsBroadcast = cols.getOrNull(24)?.toBooleanStrictOrNull() ?: false,
            caseType = cols.getOrNull(25)?.ifEmpty { null },
            urgency = cols.getOrNull(26)?.ifEmpty { null } ?: "normal",
            reviewAtEpochDay = cols.getOrNull(27)?.toLongOrNull(),
            channel = cols.getOrNull(28)?.ifEmpty { null },
            syncStatus = SyncStatus.SYNCED,
        )
    }

    private fun csvToTag(cols: List<String>): TagEntity {
        require(cols.size >= 7) { "A label row in this file is incomplete. Nothing has been changed." }
        return TagEntity(
            id = cols[0],
            name = cols[1],
            kind = cols[2],
            color = cols[3].ifEmpty { null },
            userId = "",
            usageCount = cols[4].toIntOrNull() ?: 0,
            // The exporter does not write lastUsedAt (a derived stat); a re-imported label
            // picks up a new value the next time it is attached.
            lastUsedAt = null,
            createdAt = cols[5],
            updatedAt = cols[6],
            syncStatus = SyncStatus.SYNCED,
        )
    }

    // ---- JSON ----

    private fun parseJson(text: String): Parsed {
        val root = JSONObject(text.removePrefix(CsvCodec.BOM))
        val people = mutableListOf<PersonEntity>()
        root.optJSONArray("people")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                people += PersonEntity(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    designation = o.optStringOrNull("designation"),
                    station = o.optStringOrNull("station"),
                    phone = o.optStringOrNull("phone"),
                    userId = "",
                    createdAt = o.getString("created_at"),
                    updatedAt = o.getString("updated_at"),
                    isSensitive = o.optBoolean("is_sensitive", false),
                    syncStatus = SyncStatus.SYNCED,
                    vaultMode = o.optStringOrNull("vault_mode") ?: "visible",
                    stationId = o.optStringOrNull("station_id"),
                    isStaff = o.optBoolean("is_staff", false),
                    staffActive = o.optBoolean("staff_active", true),
                    responsibilities = o.optStringOrNull("responsibilities").orEmpty(),
                    tier = o.optStringOrNull("tier") ?: "Active",
                    cadenceOverrideDays = o.optIntOrNull("cadence_override_days"),
                    lastInteractionAt = o.optLongOrNull("last_interaction_at"),
                )
            }
        }
        val instructions = mutableListOf<InstructionEntity>()
        root.optJSONArray("instructions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                instructions += InstructionEntity(
                    id = o.getString("id"),
                    personId = o.optStringOrNull("person_id"),
                    direction = o.getString("direction"),
                    status = o.getString("status"),
                    source = o.getString("source"),
                    priority = o.getString("priority"),
                    title = o.getString("title"),
                    rawText = o.getString("raw_text"),
                    dueAt = o.optStringOrNull("due_at"),
                    capturedAt = o.getString("captured_at"),
                    createdAt = o.getString("created_at"),
                    updatedAt = o.getString("updated_at"),
                    nextActionAt = o.optLongOrNull("next_action_at"),
                    completedAt = o.optStringOrNull("completed_at"),
                    droppedReason = o.optStringOrNull("dropped_reason"),
                    deadlineAtMs = o.optLongOrNull("deadline_at_ms"),
                    updatesJson = requireJournal(o.optString("updates_json", "[]")),
                    dueAtMs = o.optLongOrNull("due_at_ms"),
                    channel = o.optStringOrNull("channel"),
                    audienceKind = o.optStringOrNull("audience_kind"),
                    audienceTarget = o.optStringOrNull("audience_target"),
                    audienceLabel = o.optStringOrNull("audience_label"),
                    audienceIsBroadcast = o.optBoolean("audience_is_broadcast", false),
                    isSensitive = o.optBoolean("is_sensitive", false),
                    stationId = o.optStringOrNull("station_id"),
                    matterId = o.optStringOrNull("matter_id"),
                    caseType = o.optStringOrNull("case_type"),
                    urgency = o.optStringOrNull("urgency") ?: "normal",
                    reviewAtEpochDay = o.optLongOrNull("review_at_epoch_day"),
                    syncStatus = SyncStatus.SYNCED,
                )
            }
        }
        val tags = mutableListOf<TagEntity>()
        root.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tags += TagEntity(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    kind = o.getString("kind"),
                    color = o.optStringOrNull("color"),
                    userId = "",
                    usageCount = o.optInt("usage_count", 0),
                    lastUsedAt = null,
                    createdAt = o.getString("created_at"),
                    updatedAt = o.getString("updated_at"),
                    syncStatus = SyncStatus.SYNCED,
                )
            }
        }
        val subdivision = root.optJSONObject("subdivision")
            ?.let { SubdivisionArchiveCodec.decode(it.toString()) }
            ?: SubdivisionArchive()
        return Parsed(people, instructions, tags, subdivision)
    }

    // ---- validate, then apply ----

    private suspend fun apply(parsed: Parsed): ImportReport = db.withTransaction {
        BackupIntegrity.requireDistinctIds(parsed.people.map { it.id }, "contact")
        BackupIntegrity.requireDistinctIds(parsed.instructions.map { it.id }, "instruction")
        BackupIntegrity.requireDistinctIds(parsed.tags.map { it.id }, "label")

        val existingPeople = personDao.snapshot()
        // Read the "already here" ids BEFORE anything is written, so the inserted /
        // updated split in the report describes what actually happened rather than the
        // state left behind by this same import.
        val existingPersonIds = existingPeople.mapTo(mutableSetOf()) { it.id }
        val existingInstructionIds = instructionDao.snapshot().mapTo(mutableSetOf()) { it.id }
        val existingTagIds = tagDao.snapshot().mapTo(mutableSetOf()) { it.id }
        val vaultModes = buildMap {
            existingPeople.forEach { put(it.id, it.vaultMode) }
            // A person in the file wins: the file is what the officer is restoring.
            parsed.people.forEach { put(it.id, it.vaultMode) }
        }
        SubdivisionArchiveCodec.validate(parsed.subdivision, vaultModes)

        val subdivisionDao = db.subdivisionDao()
        val stationIds = (subdivisionDao.stations().map { it.id } + parsed.subdivision.stations.map { it.id })
            .toSet()
        val matterIds = (subdivisionDao.matters().map { it.id } + parsed.subdivision.matters.map { it.id })
            .toSet()
        BackupIntegrity.requireReferences(
            people = parsed.people,
            instructions = parsed.instructions,
            knownPersonIds = vaultModes.keys,
            knownStationIds = stationIds,
            knownMatterIds = matterIds,
        )

        // Parents before children, and every station before any matter or person that
        // points at one.
        if (parsed.people.isNotEmpty()) personDao.saveAll(parsed.people)
        if (parsed.tags.isNotEmpty()) tagDao.saveAll(parsed.tags)
        BackupIntegrity.writeSubdivision(db, parsed.subdivision)
        if (parsed.instructions.isNotEmpty()) {
            instructionDao.saveAll(parsed.instructions)
            BackupIntegrity.refreshFts(ftsDao, parsed.instructions)
        }
        // A pre-2.6.0 file has no station ids at all. Fold its free-text station names
        // into real stations so a recovered contact is not stranded outside the
        // subdivision record. Only nulls are filled, so this is a no-op for a v2.6.0 file.
        BackupIntegrity.deriveLegacyStations(db, parsed.people, parsed.instructions)


        ImportReport(
            peopleInserted = parsed.people.count { it.id !in existingPersonIds },
            peopleUpdated = parsed.people.count { it.id in existingPersonIds },
            instructionsInserted = parsed.instructions.count { it.id !in existingInstructionIds },
            instructionsUpdated = parsed.instructions.count { it.id in existingInstructionIds },
            tagsInserted = parsed.tags.count { it.id !in existingTagIds },
            tagsUpdated = parsed.tags.count { it.id in existingTagIds },

            subdivisionRecords = parsed.subdivision.let {
                it.profiles.size + it.stations.size + it.matters.size + it.postings.size + it.reviews.size
            },
        )
    }

    private fun requireJournal(value: String): String {
        val clean = value.ifBlank { "[]" }
        runCatching { InstructionJournal.decode(clean) }.getOrElse {
            throw IllegalArgumentException(
                "An instruction's update history in this file could not be read. Nothing has been changed.",
            )
        }
        return clean
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    data class ImportReport(
        var peopleInserted: Int = 0,
        var peopleUpdated: Int = 0,
        var instructionsInserted: Int = 0,
        var instructionsUpdated: Int = 0,
        var tagsInserted: Int = 0,
        var tagsUpdated: Int = 0,
        var subdivisionRecords: Int = 0,
    ) {
        val total: Int get() = peopleInserted + peopleUpdated +
            instructionsInserted + instructionsUpdated +
            tagsInserted + tagsUpdated + subdivisionRecords
    }
}
