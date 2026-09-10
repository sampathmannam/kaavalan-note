package com.kaavalan.note.data.export

import android.content.Context
import android.net.Uri
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.TagDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.local.entities.TagEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.0.1 (PM rating): the inverse of [PlainExporter]. Reads
 * a CSV or JSON snapshot from a SAF-chosen URI and upserts
 * the rows into the local Room DB.
 *
 * **Why a separate class.** The exporter is pure (no
 * Android dependencies, no DAOs). The importer is the one
 * that touches the DB, so the split keeps each class
 * single-purpose and the unit tests for the exporter stay
 * JVM-only.
 *
 * **What "upsert" means here.** Every entity has a
 * client-generated UUID (or an autoinc Long for some).
 * The importer calls the DAO's `upsert` method (a Room
 * `@Upsert` that REPLACE-on-conflict). Re-importing the
 * same file is idempotent — the row counts in the result
 * are `inserted + updated` but the user-visible "rows
 * now in DB" stays stable.
 *
 * **Format detection.** CSV starts with a BOM (`\uFEFF`)
 * and the first non-empty line is the `id,name,...`
 * header. JSON starts with `{` and has a top-level
 * `people` array. We branch on the first non-whitespace
 * character.
 *
 * **What this is NOT.** v2.0.1 does NOT import:
 *  - capture photos (the JPEGs in `filesDir/captures/`)
 *  - audit chain rows (the chain is append-only; re-importing
 *    audit rows would corrupt the hash chain)
 *  - sync_queue rows (the table is dormant in v2.0)
 * The importer is text-only. A v2.x that re-introduces
 * photo import would need to zip the JPEGs into the export
 * and unzip them on import.
 */
@Singleton
class PlainImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val personDao: PersonDao,
    private val instructionDao: InstructionDao,
    private val tagDao: TagDao,
) {

    /**
     * Read the file at [uri] and upsert every row.
     * Returns a [Result] with an [ImportReport] on
     * success or the underlying [Throwable] on failure.
     */
    suspend fun importFromUri(uri: Uri): Result<ImportReport> = runCatching {
        val text = readText(uri)
        val report = if (text.trimStart().startsWith("{")) {
            importJson(text)
        } else {
            importCsv(text)
        }
        report
    }

    private fun readText(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not open input URI: $uri")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // ---- CSV ----

    private suspend fun importCsv(text: String): ImportReport {
        // Strip the BOM if present, then split on
        // newlines. CSV uses \n between sections but the
        // exporter joins them with "\n\n" (one blank line
        // between blocks) so we treat any blank line as a
        // section break.
        val cleaned = text.removePrefix("\uFEFF")
        val blocks = cleaned.split(Regex("\\n\\s*\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val report = ImportReport()
        for (block in blocks) {
            val lines = block.split("\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val header = lines.first()
            val rows = lines.drop(1).map { parseCsvLine(it) }
            when {
                header.startsWith("id,name,designation,station") -> {
                    rows.forEach { cols ->
                        val entity = csvToPerson(cols) ?: return@forEach
                        val before = personDao.snapshot().any { it.id == entity.id }
                        personDao.upsert(entity)
                        if (before) report.peopleUpdated++ else report.peopleInserted++
                    }
                }
                header.startsWith("id,person_id,direction,status") -> {
                    rows.forEach { cols ->
                        val entity = csvToInstruction(cols) ?: return@forEach
                        val before = instructionDao.snapshot().any { it.id == entity.id }
                        instructionDao.upsert(entity)
                        if (before) report.instructionsUpdated++ else report.instructionsInserted++
                    }
                }
                header.startsWith("id,name,kind,color") -> {
                    rows.forEach { cols ->
                        val entity = csvToTag(cols) ?: return@forEach
                        val before = tagDao.observeAll().let { flow ->
                            // observeAll() returns Flow, not a
                            // suspend snapshot. Use a quick
                            // getByName + getById pair as a
                            // proxy for "exists".
                            // (A v2.x can add a snapshot() to
                            // TagDao for cleaner duplicate
                            // detection; for v2.0.1 this
                            // covers the common case.)
                            runCatching { tagDao.findByNameAndKind(entity.name, entity.kind) }.getOrNull() != null
                        }
                        tagDao.upsert(entity)
                        if (before) report.tagsUpdated++ else report.tagsInserted++
                    }
                }
            }
        }
        return report
    }

    /**
     * Minimal CSV line parser. The exporter uses the
     * standard double-quote escape (`""` inside a quoted
     * field) and only quotes fields that contain `,`, `"`,
     * or `\n`. The parser mirrors that exactly.
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"' && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i += 2
                        continue
                    } else if (c == '"') {
                        inQuotes = false
                    } else {
                        current.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun csvToPerson(cols: List<String>): PersonEntity? {
        if (cols.size < 8) return null
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
        )
    }

    private fun csvToInstruction(cols: List<String>): InstructionEntity? {
        if (cols.size < 13) return null
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
            updatesJson = cols.getOrNull(14) ?: "[]",
            dueAtMs = cols.getOrNull(15)?.toLongOrNull(),
            completedAt = null,
            droppedReason = null,
            syncStatus = SyncStatus.SYNCED,
        )
    }

    private fun csvToTag(cols: List<String>): TagEntity? {
        if (cols.size < 7) return null
        return TagEntity(
            id = cols[0],
            name = cols[1],
            kind = cols[2],
            color = cols[3].ifEmpty { null },
            userId = "",
            usageCount = cols[4].toIntOrNull() ?: 0,
            // v2.0.1: the exporter does not write lastUsedAt
            // (it changes frequently and is a derived
            // stat). Re-imported tags start with lastUsedAt
            // = null; the tag will pick up a new value the
            // next time it's attached to an instruction.
            lastUsedAt = null,
            createdAt = cols[5],
            updatedAt = cols[6],
            syncStatus = SyncStatus.SYNCED,
        )
    }

    // ---- JSON ----

    private suspend fun importJson(text: String): ImportReport {
        val root = JSONObject(text)
        val report = ImportReport()
        // People
        root.optJSONArray("people")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entity = PersonEntity(
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
                )
                val before = personDao.snapshot().any { it.id == entity.id }
                personDao.upsert(entity)
                if (before) report.peopleUpdated++ else report.peopleInserted++
            }
        }
        // Instructions
        root.optJSONArray("instructions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entity = InstructionEntity(
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
                    updatesJson = o.optString("updates_json", "[]").also { com.kaavalan.note.data.instructions.InstructionJournal.decode(it) },
                    dueAtMs = o.optLongOrNull("due_at_ms"),
                    channel = o.optStringOrNull("channel"),
                    audienceKind = o.optStringOrNull("audience_kind"),
                    audienceTarget = o.optStringOrNull("audience_target"),
                    audienceLabel = o.optStringOrNull("audience_label"),
                    audienceIsBroadcast = o.optBoolean("audience_is_broadcast", false),
                    isSensitive = o.optBoolean("is_sensitive", false),
                    syncStatus = SyncStatus.SYNCED,
                )
                val before = instructionDao.snapshot().any { it.id == entity.id }
                instructionDao.upsert(entity)
                if (before) report.instructionsUpdated++ else report.instructionsInserted++
            }
        }
        // Tags
        root.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entity = TagEntity(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    kind = o.getString("kind"),
                    color = o.optStringOrNull("color"),
                    userId = "",
                    usageCount = o.optInt("usage_count", 0),
                    // v2.0.1: the exporter does not write
                    // lastUsedAt (derived stat); re-imported
                    // tags start with null.
                    lastUsedAt = null,
                    createdAt = o.getString("created_at"),
                    updatedAt = o.getString("updated_at"),
                    syncStatus = SyncStatus.SYNCED,
                )
                val before = runCatching { tagDao.findByNameAndKind(entity.name, entity.kind) }.getOrNull() != null
                tagDao.upsert(entity)
                if (before) report.tagsUpdated++ else report.tagsInserted++
            }
        }
        return report
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    data class ImportReport(
        var peopleInserted: Int = 0,
        var peopleUpdated: Int = 0,
        var instructionsInserted: Int = 0,
        var instructionsUpdated: Int = 0,
        var tagsInserted: Int = 0,
        var tagsUpdated: Int = 0,
    ) {
        val total: Int get() = peopleInserted + peopleUpdated +
            instructionsInserted + instructionsUpdated +
            tagsInserted + tagsUpdated
    }
}
