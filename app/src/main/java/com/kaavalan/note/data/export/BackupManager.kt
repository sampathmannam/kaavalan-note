package com.kaavalan.note.data.export

import android.content.Context
import androidx.room.withTransaction
import com.kaavalan.note.data.local.CaptureDao
import com.kaavalan.note.data.local.ImportantDateDao
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.InstructionTagDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.PersonLinkDao
import com.kaavalan.note.data.local.TagDao
import com.kaavalan.note.data.local.entities.CaptureEntity
import com.kaavalan.note.data.local.entities.ImportantDateEntity
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionTagCrossRef
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.PersonLinkEntity
import com.kaavalan.note.data.local.entities.TagEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.8.0 (PROD-READINESS-P0-#1): full backup + restore.
 *
 * The existing [PlainExporter] can write a JSON snapshot of the
 * person / instruction / tag tables but cannot round-trip the
 * other tables (captures, important dates, person links, the
 * instruction-tag join) and cannot restore. This class wraps
 * [PlainExporter] for the export side and implements the full
 * restore path.
 *
 * **Where backups live:** the app's private `filesDir` under a
 * `backups/` subdirectory. Android's app sandbox already
 * protects this from other apps; on a non-rooted device the
 * file is unreachable without `adb backup` or root. v2.x can
 * add user-selectable destinations (Drive, SD card, share
 * intent) and a passphrase-encrypted envelope — the v1.x
 * trade-off is "sandboxed plain JSON, recoverable on the same
 * device after a clear-data".
 *
 * **What is restored:** every table that [PlainExporter.snapshot]
 * can read plus the additional tables this class adds (captures,
 * important dates, person links, instruction-tag join). The
 * restore is idempotent — re-running it on the same backup is a
 * no-op (every insert is `OnConflictStrategy.REPLACE`).
 *
 * **Restore order:** parents before children.
 *   1. persons
 *   2. tags
 *   3. instructions
 *   4. instruction_tags (join)
 *   5. person_links
 *   6. captures
 *   7. important_dates
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plainExporter: PlainExporter,
    private val personDao: PersonDao,
    private val instructionDao: InstructionDao,
    private val tagDao: TagDao,
    private val instructionTagDao: InstructionTagDao,
    private val personLinkDao: PersonLinkDao,
    private val captureDao: CaptureDao,
    private val importantDateDao: ImportantDateDao,
    private val ftsDao: com.kaavalan.note.data.local.InstructionFtsDao,
    private val db: com.kaavalan.note.data.local.AppDatabase,
) {

    /**
     * The directory under `filesDir` where backup files live.
     * Created lazily on the first [backup] or [restore] call.
     */
    private val backupDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    /**
     * Take a snapshot of every table, serialise to JSON, write
     * to a timestamped file in [backupDir]. Returns the file.
     *
     * **Retention:** keeps the [MAX_BACKUPS] most recent files
     * and deletes older ones. This prevents the app's storage
     * from growing without bound.
     */
    suspend fun backup(): File {
        // v2.6.0: one transaction for the whole snapshot. A backup taken while the officer
        // is still working must not capture an instruction whose matter, or a posting whose
        // contact, was written a moment later.
        val bundle = db.withTransaction {
            Bundle(
                snap = plainExporter.snapshot(),
                captures = captureDao.snapshot(),
                importantDates = importantDateDao.snapshot(),
                personLinks = personLinkDao.snapshot(),
                instructionTags = instructionTagDao.snapshotAll(),
            )
        }
        val snap = bundle.snap
        val root = JSONObject().apply {
            // Schema 4 (v2.6.0) adds the `subdivision` object. Restore still reads 3 and
            // earlier: an older backup is missing the key, which reads correctly as "this
            // device had no subdivision record yet".
            put("schema_version", SCHEMA_VERSION)
            put("created_at", System.currentTimeMillis())
            put("people", snap.people.toPersonJsonArray())
            put("instructions", snap.instructions.toInstructionJsonArray())
            put("tags", snap.tags.toTagJsonArray())
            put("captures", bundle.captures.toCaptureJsonArray())
            put("important_dates", bundle.importantDates.toImportantDateJsonArray())
            put("person_links", bundle.personLinks.toPersonLinkJsonArray())
            put("instruction_tags", bundle.instructionTags.toInstructionTagJsonArray())
            put(
                "subdivision",
                JSONObject(
                    com.kaavalan.note.data.subdivision.SubdivisionArchiveCodec.encode(snap.subdivision),
                ),
            )
        }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(backupDir, "kaavalan-note-backup-$ts.json")
        file.writeText(root.toString(2))
        pruneOldBackups()
        return file
    }

    /**
     * Read a backup file and insert every row back into Room.
     * Idempotent: re-running on the same file is a no-op
     * (REPLACE strategy on every DAO).
     *
     * Returns a [RestoreResult] with per-table counts so the
     * UI / log can tell the user what was restored.
     */
    suspend fun restore(file: File): RestoreResult {
        val root = JSONObject(file.readText())
        val version = if (root.has("schema_version")) root.optInt("schema_version", 1) else 1
        require(version <= SCHEMA_VERSION) {
            "This backup was written by a newer version of KaavalanNote (format " + version +
                "; this build reads up to " + SCHEMA_VERSION + "). Update the app before restoring it. " +
                "Nothing has been changed."
        }
        // v2.6.0: parse EVERYTHING first, and refuse the whole file on the first bad row.
        // The pre-2.6.0 parser wrapped each row in a try/catch and returned null on
        // failure, so a truncated or hand-edited backup restored "successfully" with rows
        // missing and no way for the officer to know which. A backup whose failure mode is
        // silent partial loss is not a backup.
        val people = parseAll(root.optJSONArray("people"), "contact") { it.toPersonEntity() }
        val tags = parseAll(root.optJSONArray("tags"), "label") { it.toTagEntity() }
        val instructions = parseAll(root.optJSONArray("instructions"), "instruction") { it.toInstructionEntity() }
        val captures = parseAll(root.optJSONArray("captures"), "capture") { it.toCaptureEntity() }
        val importantDates = parseAll(root.optJSONArray("important_dates"), "important date") {
            it.toImportantDateEntity()
        }
        val personLinks = parseAll(root.optJSONArray("person_links"), "relationship") { it.toPersonLinkEntity() }
        val instructionTags = parseAll(root.optJSONArray("instruction_tags"), "label link") {
            it.toInstructionTagCrossRef()
        }
        val subdivision = com.kaavalan.note.data.subdivision.SubdivisionArchiveCodec
            .decode(root.optJSONObject("subdivision")?.toString())

        return db.withTransaction {
            BackupIntegrity.requireDistinctIds(people.map { it.id }, "contact")
            BackupIntegrity.requireDistinctIds(instructions.map { it.id }, "instruction")
            BackupIntegrity.requireDistinctIds(tags.map { it.id }, "label")
            BackupIntegrity.requireDistinctIds(captures.map { it.id }, "capture")

            val vaultModes = buildMap {
                personDao.snapshot().forEach { put(it.id, it.vaultMode) }
                people.forEach { put(it.id, it.vaultMode) }
            }
            com.kaavalan.note.data.subdivision.SubdivisionArchiveCodec.validate(subdivision, vaultModes)
            val subdivisionDao = db.subdivisionDao()
            val stationIds = (subdivisionDao.stations().map { it.id } + subdivision.stations.map { it.id }).toSet()
            val matterIds = (subdivisionDao.matters().map { it.id } + subdivision.matters.map { it.id }).toSet()
            BackupIntegrity.requireReferences(
                people = people,
                instructions = instructions,
                knownPersonIds = vaultModes.keys,
                knownStationIds = stationIds,
                knownMatterIds = matterIds,
            )
            importantDates.forEach { date ->
                require(date.personId in vaultModes.keys) {
                    "An important date in this backup belongs to a contact that does not exist. " +
                        "Nothing has been changed."
                }
            }
            personLinks.forEach { link ->
                require(link.fromId in vaultModes.keys && link.toId in vaultModes.keys) {
                    "A relationship in this backup refers to a contact that does not exist. " +
                        "Nothing has been changed."
                }
            }
            val instructionIds = (instructionDao.snapshot().map { it.id } + instructions.map { it.id }).toSet()
            val tagIds = (tagDao.snapshot().map { it.id } + tags.map { it.id }).toSet()
            instructionTags.forEach { link ->
                require(link.instructionId in instructionIds && link.tagId in tagIds) {
                    "A label link in this backup refers to a record that does not exist. Nothing has been changed."
                }
            }

            // Order matters: parents before children, stations before anything that points
            // at one. Writes are @Upsert, not INSERT OR REPLACE, so a repeated restore does
            // not delete-and-reinsert rows and cascade their children away.
            if (people.isNotEmpty()) personDao.saveAll(people)
            if (tags.isNotEmpty()) tagDao.saveAll(tags)
            BackupIntegrity.writeSubdivision(db, subdivision)
            if (instructions.isNotEmpty()) {
                instructionDao.saveAll(instructions)
                BackupIntegrity.refreshFts(ftsDao, instructions)
            }
            if (instructionTags.isNotEmpty()) instructionTagDao.attachAll(instructionTags)
            if (personLinks.isNotEmpty()) personLinkDao.upsertAll(personLinks)
            if (captures.isNotEmpty()) captureDao.upsertAll(captures)
            if (importantDates.isNotEmpty()) importantDateDao.upsertAll(importantDates)
            BackupIntegrity.deriveLegacyStations(db, people, instructions)

            RestoreResult(
                people = people.size,
                tags = tags.size,
                instructions = instructions.size,
                instructionTags = instructionTags.size,
                personLinks = personLinks.size,
                captures = captures.size,
                importantDates = importantDates.size,
                subdivisionRecords = subdivision.profiles.size + subdivision.stations.size +
                    subdivision.matters.size + subdivision.postings.size + subdivision.reviews.size,
            )
        }
    }

    private class Bundle(
        val snap: PlainExporter.Snapshot,
        val captures: List<CaptureEntity>,
        val importantDates: List<ImportantDateEntity>,
        val personLinks: List<PersonLinkEntity>,
        val instructionTags: List<InstructionTagCrossRef>,
    )

    /**
     * List the backup files in [backupDir] sorted newest-first.
     */
    fun listBackups(): List<File> =
        backupDir.listFiles { f -> f.isFile && f.name.startsWith("kaavalan-note-backup-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun pruneOldBackups() {
        val files = listBackups()
        if (files.size > MAX_BACKUPS) {
            files.drop(MAX_BACKUPS).forEach { it.delete() }
        }
    }

    data class RestoreResult(
        val people: Int,
        val tags: Int,
        val instructions: Int,
        val instructionTags: Int,
        val personLinks: Int,
        val captures: Int,
        val importantDates: Int,
        val subdivisionRecords: Int = 0,
    ) {
        val total: Int
            get() = people + tags + instructions + instructionTags + personLinks + captures +
                importantDates + subdivisionRecords
    }

    companion object {
        /** v1.8.0 (PROD-READINESS-P0-#1): keep the last 7 daily backups. */
        const val MAX_BACKUPS = 7

        /**
         * v2.6.0: 3 -> 4 for the `subdivision` object. Reading is backward compatible;
         * a newer version than this is refused rather than partially understood.
         */
        const val SCHEMA_VERSION = 4
    }

}

// ---- JSON serialise helpers (write side) ----

private fun List<PersonEntity>.toPersonJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach { p ->
        arr.put(JSONObject().apply {
            put("id", p.id); put("name", p.name)
            put("designation", p.designation); put("station", p.station)
            put("phone", p.phone); put("user_id", p.userId)
            put("is_sensitive", p.isSensitive)
            put("tier", p.tier)
            put("cadence_override_days", p.cadenceOverrideDays)
            put("last_interaction_at", p.lastInteractionAt)
            put("vault_mode", p.vaultMode)
            // v2.6.0: the subdivision columns on a contact.
            put("station_id", p.stationId)
            put("is_staff", p.isStaff)
            put("staff_active", p.staffActive)
            put("responsibilities", p.responsibilities)
            put("created_at", p.createdAt); put("updated_at", p.updatedAt)
        })
    }
}

private fun List<InstructionEntity>.toInstructionJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach { i ->
        arr.put(JSONObject().apply {
            put("id", i.id); put("person_id", i.personId)
            put("direction", i.direction); put("status", i.status)
            put("source", i.source); put("priority", i.priority)
            put("title", i.title); put("raw_text", i.rawText)
            put("due_at", i.dueAt); put("captured_at", i.capturedAt)
            put("created_at", i.createdAt); put("updated_at", i.updatedAt)
            put("is_sensitive", i.isSensitive)
            put("completed_at", i.completedAt)
            put("dropped_reason", i.droppedReason)
            put("next_action_at", i.nextActionAt)
            put("case_type", i.caseType)
            put("urgency", i.urgency)
            put("review_at_epoch_day", i.reviewAtEpochDay)
            put("audience_kind", i.audienceKind); put("audience_target", i.audienceTarget)
            put("audience_label", i.audienceLabel); put("audience_is_broadcast", i.audienceIsBroadcast)
            put("due_at_ms", i.dueAtMs); put("channel", i.channel)
            put("deadline_at_ms", i.deadlineAtMs); put("updates_json", i.updatesJson)
            // v2.6.0: the recorded work context.
            put("station_id", i.stationId)
            put("matter_id", i.matterId)
        })
    }
}

private fun List<TagEntity>.toTagJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach { t ->
        arr.put(JSONObject().apply {
            put("id", t.id); put("name", t.name); put("kind", t.kind)
            put("color", t.color); put("usage_count", t.usageCount)
            put("last_used_at", t.lastUsedAt)
            put("user_id", t.userId)
            put("created_at", t.createdAt); put("updated_at", t.updatedAt)
        })
    }
}

private fun List<CaptureEntity>.toCaptureJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach { c ->
        arr.put(JSONObject().apply {
            put("id", c.id); put("mode", c.mode); put("raw_text", c.rawText)
            put("audio_uri", c.audioUri); put("image_uri", c.imageUri)
            put("processed", c.processed); put("created_at", c.createdAt)
            put("ocr_text", c.ocrText); put("calendar_event_id", c.calendarEventId)
            put("urgency", c.urgency); put("review_at_epoch_day", c.reviewAtEpochDay)
        })
    }
}

private fun List<ImportantDateEntity>.toImportantDateJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach { d ->
        arr.put(JSONObject().apply {
            put("id", d.id); put("person_id", d.personId); put("label", d.label)
            put("date_epoch_day", d.dateEpochDay); put("recurring", d.recurring)
            put("created_at", d.createdAt); put("updated_at", d.updatedAt)
        })
    }
}

private fun List<PersonLinkEntity>.toPersonLinkJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach { l ->
        arr.put(JSONObject().apply {
            put("from_id", l.fromId); put("to_id", l.toId)
            put("relation", l.relation); put("created_at", l.createdAt)
        })
    }
}

private fun List<InstructionTagCrossRef>.toInstructionTagJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach { r ->
        arr.put(JSONObject().apply {
            put("instruction_id", r.instructionId); put("tag_id", r.tagId)
        })
    }
}

// ---- JSON parse helpers (restore side) ----

/**
 * v2.6.0: parse every element or refuse the file.
 *
 * The v1.8.0 version of this helper returned `null` for any element that threw and then
 * dropped it, which turned a corrupt backup into a silently incomplete restore. Recovery
 * is the one path where partial success is the worst outcome: the officer believes their
 * record is back and only discovers otherwise months later.
 */
private fun <T> parseAll(arr: JSONArray?, what: String, transform: (JSONObject) -> T): List<T> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { i ->
        val obj = arr.optJSONObject(i)
            ?: throw IllegalArgumentException(
                "This backup has a malformed " + what + " entry. Nothing has been changed.",
            )
        try {
            transform(obj)
        } catch (failure: Throwable) {
            throw IllegalArgumentException(
                "This backup has a " + what + " entry that could not be read. Nothing has been changed.",
                failure,
            )
        }
    }
}

private fun JSONObject.toPersonEntity(): PersonEntity = PersonEntity(
    id = getString("id"),
    name = getString("name"),
    designation = optStringOrNull("designation"),
    station = optStringOrNull("station"),
    phone = optStringOrNull("phone"),
    userId = optString("user_id", "restored"),
    isSensitive = optBoolean("is_sensitive", false),
    tier = optString("tier", "Active"),
    cadenceOverrideDays = optIntOrNull("cadence_override_days"),
    lastInteractionAt = optLongOrNull("last_interaction_at"),
    vaultMode = optString("vault_mode", "visible"),
    // v2.6.0. A schema-3 backup has none of these keys, and the defaults are exactly
    // right for it: an ordinary contact with no station and no staff classification.
    // Restoring must never invent a posting for a real person.
    stationId = optStringOrNull("station_id"),
    isStaff = optBoolean("is_staff", false),
    staffActive = optBoolean("staff_active", true),
    responsibilities = optString("responsibilities", ""),
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
)

private fun JSONObject.toInstructionEntity(): InstructionEntity = InstructionEntity(
    id = getString("id"),
    personId = optStringOrNull("person_id"),
    direction = getString("direction"),
    status = getString("status"),
    source = getString("source"),
    priority = getString("priority"),
    title = getString("title"),
    rawText = getString("raw_text"),
    dueAt = optStringOrNull("due_at"),
    capturedAt = getString("captured_at"),
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
    isSensitive = optBoolean("is_sensitive", false),
    completedAt = optStringOrNull("completed_at"),
    droppedReason = optStringOrNull("dropped_reason"),
    nextActionAt = optLongOrNull("next_action_at"),
    caseType = optStringOrNull("case_type"),
    urgency = optString("urgency", "normal"),
    reviewAtEpochDay = optLongOrNull("review_at_epoch_day"),
    audienceKind = optStringOrNull("audience_kind"),
    audienceTarget = optStringOrNull("audience_target"),
    audienceLabel = optStringOrNull("audience_label"),
    audienceIsBroadcast = optBoolean("audience_is_broadcast", false),
    dueAtMs = optLongOrNull("due_at_ms"),
    channel = optStringOrNull("channel"),
    deadlineAtMs = optLongOrNull("deadline_at_ms"),
    updatesJson = optString("updates_json", "[]")
        .ifBlank { "[]" }
        .also { com.kaavalan.note.data.instructions.InstructionJournal.decode(it) },
    // v2.6.0. Absent in a schema-3 backup; [BackupIntegrity.deriveLegacyStations] fills
    // the station in afterwards from the contact's own station text where it can.
    stationId = optStringOrNull("station_id"),
    matterId = optStringOrNull("matter_id"),
)


private fun JSONObject.toTagEntity(): TagEntity = TagEntity(
    id = getString("id"),
    name = getString("name"),
    kind = getString("kind"),
    color = optStringOrNull("color"),
    usageCount = optInt("usage_count", 0),
    lastUsedAt = optStringOrNull("last_used_at"),
    userId = optString("user_id", "restored"),
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
)

private fun JSONObject.toCaptureEntity(): CaptureEntity = CaptureEntity(
    id = getString("id"),
    mode = getString("mode"),
    rawText = optStringOrNull("raw_text"),
    audioUri = optStringOrNull("audio_uri"),
    imageUri = optStringOrNull("image_uri"),
    processed = optBoolean("processed", false),
    createdAt = getString("created_at"),
    ocrText = optStringOrNull("ocr_text"),
    calendarEventId = optStringOrNull("calendar_event_id"),
    urgency = optString("urgency", "normal"),
    reviewAtEpochDay = optLongOrNull("review_at_epoch_day"),
)

private fun JSONObject.toImportantDateEntity(): ImportantDateEntity = ImportantDateEntity(
    id = getString("id"),
    personId = optStringOrNull("person_id") ?: "__orphan__",
    label = getString("label"),
    dateEpochDay = getLong("date_epoch_day"),
    recurring = optBoolean("recurring", false),
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
)

private fun JSONObject.toPersonLinkEntity(): PersonLinkEntity = PersonLinkEntity(
    fromId = getString("from_id"),
    toId = getString("to_id"),
    relation = getString("relation"),
    createdAt = optString("created_at", "2026-01-01T00:00:00Z"),
)

private fun JSONObject.toInstructionTagCrossRef(): InstructionTagCrossRef = InstructionTagCrossRef(
    instructionId = getString("instruction_id"),
    tagId = getString("tag_id"),
)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)
