package com.kaavalan.note.data.export

import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.InstructionTagDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.TagDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.TagEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tier 1.7 (v2.0): plain (non-encrypted) export to CSV or
 * JSON. The user can hand the file to anyone — there's no
 * passphrase, no key, no protected envelope. The export is
 * a flat snapshot of `persons` + `instructions` + `tags` +
 * the `instruction_tags` join rows. No photos / voice
 * (the existing camera + voice code only stores to
 * `cacheDir`; it's lost on process death anyway).
 *
 * **CSV** includes a UTF-8 BOM (`\uFEFF`) so Excel opens
 * Indian / Tamil names without the mojibake round-trip.
 * **JSON** is the `org.json` flat serialiser; no extra
 * dep. Both share the same `Snapshot` model.
 */
@Singleton
class PlainExporter @Inject constructor(
    private val personDao: PersonDao,
    private val instructionDao: InstructionDao,
    private val tagDao: TagDao,
    private val xrefDao: InstructionTagDao,
) {

    suspend fun snapshot(): Snapshot = Snapshot(
        people = personDao.snapshot(),
        instructions = instructionDao.snapshot(),
        tags = tagDao.observeAll().first(),
    )

    suspend fun toCsv(snap: Snapshot): String {
        val bom = "\uFEFF"
        val people = StringBuilder()
        people.append("id,name,designation,station,phone,is_sensitive,created_at,updated_at\n")
        snap.people.forEach { p ->
            people.append(joinCsv(listOf(
                p.id, p.name, p.designation.orEmpty(), p.station.orEmpty(),
                p.phone.orEmpty(), p.isSensitive.toString(), p.createdAt, p.updatedAt,
            ))).append("\n")
        }
        val instructions = StringBuilder()
        instructions.append("id,person_id,direction,status,source,priority,title,raw_text,due_at,captured_at,created_at,updated_at,next_action_at,deadline_at_ms,updates_json,due_at_ms\n")
        snap.instructions.forEach { i ->
            instructions.append(joinCsv(listOf(
                i.id, i.personId.orEmpty(), i.direction, i.status, i.source, i.priority,
                i.title, i.rawText, i.dueAt.orEmpty(), i.capturedAt, i.createdAt,
                i.updatedAt, i.nextActionAt?.toString().orEmpty(),
                i.deadlineAtMs?.toString().orEmpty(), i.updatesJson, i.dueAtMs?.toString().orEmpty(),
            ))).append("\n")
        }
        val tags = StringBuilder()
        tags.append("id,name,kind,color,usage_count,created_at,updated_at\n")
        snap.tags.forEach { t ->
            tags.append(joinCsv(listOf(
                t.id, t.name, t.kind, t.color.orEmpty(), t.usageCount.toString(),
                t.createdAt, t.updatedAt,
            ))).append("\n")
        }
        return bom + people.toString() + "\n" + instructions.toString() + "\n" + tags.toString()
    }

    suspend fun toJson(snap: Snapshot): String {
        val peopleArr = JSONArray()
        snap.people.forEach { p ->
            peopleArr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name)
                put("designation", p.designation); put("station", p.station)
                put("phone", p.phone); put("is_sensitive", p.isSensitive)
                put("created_at", p.createdAt); put("updated_at", p.updatedAt)
            })
        }
        val instrArr = JSONArray()
        snap.instructions.forEach { i ->
            instrArr.put(JSONObject().apply {
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
            })
        }
        val tagArr = JSONArray()
        snap.tags.forEach { t ->
            tagArr.put(JSONObject().apply {
                put("id", t.id); put("name", t.name); put("kind", t.kind)
                put("color", t.color); put("usage_count", t.usageCount)
                put("created_at", t.createdAt); put("updated_at", t.updatedAt)
            })
        }
        val root = JSONObject()
        root.put("people", peopleArr)
        root.put("instructions", instrArr)
        root.put("tags", tagArr)
        return root.toString(2)
    }

    private fun joinCsv(values: List<String>): String =
        values.joinToString(",") { v ->
            if (v.contains(',') || v.contains('"') || v.contains('\n')) {
                "\"" + v.replace("\"", "\"\"") + "\""
            } else v
        }

    data class Snapshot(
        val people: List<PersonEntity>,
        val instructions: List<InstructionEntity>,
        val tags: List<TagEntity>,
    )
}
