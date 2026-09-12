package com.kaavalan.note.data.export

import androidx.room.withTransaction
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.InstructionTagDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.TagDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.TagEntity
import com.kaavalan.note.data.subdivision.SubdivisionArchive
import com.kaavalan.note.data.subdivision.SubdivisionArchiveCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tier 1.7 (v2.0): plain (non-encrypted) export to CSV or JSON. The user can hand the
 * file to anyone - there is no passphrase, no key, no protected envelope.
 *
 * **CSV** carries a UTF-8 BOM so Excel opens Tamil and other Indian-language names
 * without the mojibake round-trip. Fields are quoted only when they need it, which keeps
 * the file byte-identical to the pre-2.6.0 export for rows that gained no new data.
 *
 * **v2.6.0 (subdivision CRM).** Both formats now carry the officer's own structure -
 * subdivision profile, stations, matters, staff postings and reviews - plus the new
 * person and instruction columns. An export that claimed a complete round trip while
 * silently dropping all of that would be worse than no export: the officer would find out
 * only when they tried to recover.
 *
 * New columns are **appended**, and the subdivision archive rides in its own clearly
 * labelled `subdivision_json` block, so a spreadsheet or script written against the older
 * layout still reads the columns it knows.
 *
 * The whole snapshot is read inside one Room transaction, so an export taken while the
 * officer is still using the app cannot contain an instruction whose matter is missing.
 */
@Singleton
class PlainExporter @Inject constructor(
    private val personDao: PersonDao,
    private val instructionDao: InstructionDao,
    private val tagDao: TagDao,
    private val xrefDao: InstructionTagDao,
    private val db: AppDatabase,
) {

    suspend fun snapshot(): Snapshot = db.withTransaction {
        val subdivisionDao = db.subdivisionDao()
        Snapshot(
            people = personDao.snapshot(),
            instructions = instructionDao.snapshot(),
            tags = tagDao.observeAll().first(),
            subdivision = SubdivisionArchive(
                profiles = subdivisionDao.profiles(),
                stations = subdivisionDao.stations(),
                matters = subdivisionDao.matters(),
                postings = subdivisionDao.postings(),
                reviews = subdivisionDao.reviews(),
            ),
        )
    }

    suspend fun toCsv(snap: Snapshot): String {
        val people = StringBuilder()
        people.append(CsvCodec.row(PEOPLE_HEADER)).append("\n")
        snap.people.forEach { p ->
            people.append(
                CsvCodec.row(
                    listOf(
                        p.id, p.name, p.designation.orEmpty(), p.station.orEmpty(),
                        p.phone.orEmpty(), p.isSensitive.toString(), p.createdAt, p.updatedAt,
                        // v2.6.0 appended columns.
                        p.vaultMode, p.stationId.orEmpty(), p.isStaff.toString(),
                        p.staffActive.toString(), p.responsibilities, p.tier,
                        p.cadenceOverrideDays?.toString().orEmpty(),
                        p.lastInteractionAt?.toString().orEmpty(),
                    ),
                ),
            ).append("\n")
        }
        val instructions = StringBuilder()
        instructions.append(CsvCodec.row(INSTRUCTION_HEADER)).append("\n")
        snap.instructions.forEach { i ->
            instructions.append(
                CsvCodec.row(
                    listOf(
                        i.id, i.personId.orEmpty(), i.direction, i.status, i.source, i.priority,
                        i.title, i.rawText, i.dueAt.orEmpty(), i.capturedAt, i.createdAt,
                        i.updatedAt, i.nextActionAt?.toString().orEmpty(),
                        i.deadlineAtMs?.toString().orEmpty(), i.updatesJson, i.dueAtMs?.toString().orEmpty(),
                        // v2.6.0 appended columns.
                        i.stationId.orEmpty(), i.matterId.orEmpty(), i.isSensitive.toString(),
                        i.completedAt.orEmpty(), i.droppedReason.orEmpty(),
                        i.audienceKind.orEmpty(), i.audienceTarget.orEmpty(), i.audienceLabel.orEmpty(),
                        i.audienceIsBroadcast.toString(), i.caseType.orEmpty(), i.urgency,
                        i.reviewAtEpochDay?.toString().orEmpty(), i.channel.orEmpty(),
                    ),
                ),
            ).append("\n")
        }
        val tags = StringBuilder()
        tags.append(CsvCodec.row(TAG_HEADER)).append("\n")
        snap.tags.forEach { t ->
            tags.append(
                CsvCodec.row(
                    listOf(
                        t.id, t.name, t.kind, t.color.orEmpty(), t.usageCount.toString(),
                        t.createdAt, t.updatedAt,
                    ),
                ),
            ).append("\n")
        }
        // The subdivision archive is one clearly labelled block holding one quoted record.
        // A reader that does not know the block skips it; a reader that does gets the whole
        // structure or a refusal, never a half-parsed subdivision.
        val subdivision = StringBuilder()
        subdivision.append(SUBDIVISION_HEADER).append("\n")
        subdivision.append(CsvCodec.field(SubdivisionArchiveCodec.encode(snap.subdivision))).append("\n")
        return CsvCodec.BOM + people + "\n" + instructions + "\n" + tags + "\n" + subdivision
    }

    suspend fun toJson(snap: Snapshot): String {
        val peopleArr = JSONArray()
        snap.people.forEach { p ->
            peopleArr.put(
                JSONObject().apply {
                    put("id", p.id); put("name", p.name)
                    put("designation", p.designation); put("station", p.station)
                    put("phone", p.phone); put("is_sensitive", p.isSensitive)
                    put("created_at", p.createdAt); put("updated_at", p.updatedAt)
                    // v2.6.0: the vault flag and the subdivision columns.
                    put("vault_mode", p.vaultMode)
                    put("station_id", p.stationId)
                    put("is_staff", p.isStaff)
                    put("staff_active", p.staffActive)
                    put("responsibilities", p.responsibilities)
                    put("tier", p.tier)
                    put("cadence_override_days", p.cadenceOverrideDays)
                    put("last_interaction_at", p.lastInteractionAt)
                },
            )
        }
        val instrArr = JSONArray()
        snap.instructions.forEach { i ->
            instrArr.put(
                JSONObject().apply {
                    put("id", i.id); put("person_id", i.personId)
                    put("direction", i.direction); put("status", i.status)
                    put("source", i.source); put("priority", i.priority)
                    put("title", i.title); put("raw_text", i.rawText)
                    put("due_at", i.dueAt); put("captured_at", i.capturedAt)
                    put("created_at", i.createdAt); put("updated_at", i.updatedAt)
                    put("next_action_at", i.nextActionAt)
                    put("completed_at", i.completedAt); put("dropped_reason", i.droppedReason)
                    put("deadline_at_ms", i.deadlineAtMs); put("updates_json", i.updatesJson)
                    put("due_at_ms", i.dueAtMs); put("channel", i.channel)
                    put("audience_kind", i.audienceKind); put("audience_target", i.audienceTarget)
                    put("audience_label", i.audienceLabel); put("audience_is_broadcast", i.audienceIsBroadcast)
                    put("is_sensitive", i.isSensitive)
                    // v2.6.0: the recorded work context, plus the lifecycle fields the
                    // pre-2.6.0 JSON export was already dropping.
                    put("station_id", i.stationId)
                    put("matter_id", i.matterId)
                    put("case_type", i.caseType)
                    put("urgency", i.urgency)
                    put("review_at_epoch_day", i.reviewAtEpochDay)
                },
            )
        }
        val tagArr = JSONArray()
        snap.tags.forEach { t ->
            tagArr.put(
                JSONObject().apply {
                    put("id", t.id); put("name", t.name); put("kind", t.kind)
                    put("color", t.color); put("usage_count", t.usageCount)
                    put("created_at", t.createdAt); put("updated_at", t.updatedAt)
                },
            )
        }
        val root = JSONObject()
        root.put("people", peopleArr)
        root.put("instructions", instrArr)
        root.put("tags", tagArr)
        root.put("subdivision", JSONObject(SubdivisionArchiveCodec.encode(snap.subdivision)))
        return root.toString(2)
    }

    /**
     * [subdivision] defaults to empty so a caller that only cares about people, notes and
     * tags - including the pre-2.6.0 tests - still constructs a valid snapshot.
     */
    data class Snapshot(
        val people: List<PersonEntity>,
        val instructions: List<InstructionEntity>,
        val tags: List<TagEntity>,
        val subdivision: SubdivisionArchive = SubdivisionArchive(),
    )

    companion object {
        /** The historic columns come first, in their original order; v2.6.0 appends. */
        val PEOPLE_HEADER = listOf(
            "id", "name", "designation", "station", "phone", "is_sensitive", "created_at", "updated_at",
            "vault_mode", "station_id", "is_staff", "staff_active", "responsibilities", "tier",
            "cadence_override_days", "last_interaction_at",
        )
        val INSTRUCTION_HEADER = listOf(
            "id", "person_id", "direction", "status", "source", "priority", "title", "raw_text",
            "due_at", "captured_at", "created_at", "updated_at", "next_action_at", "deadline_at_ms",
            "updates_json", "due_at_ms",
            "station_id", "matter_id", "is_sensitive", "completed_at", "dropped_reason",
            "audience_kind", "audience_target", "audience_label", "audience_is_broadcast",
            "case_type", "urgency", "review_at_epoch_day", "channel",
        )
        val TAG_HEADER = listOf("id", "name", "kind", "color", "usage_count", "created_at", "updated_at")

        /** The label the importer looks for. One header record, then one quoted archive record. */
        const val SUBDIVISION_HEADER = "subdivision_json"
    }
}
