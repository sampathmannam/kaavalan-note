package com.kaavalan.note.ui.workspace

import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.person.Person
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Screen-independent projections. No writes, Android dependencies, or hidden age cut-offs. */
enum class WorkFilter { ALL, OPEN, FOR_ME, ASSIGNED, RECEIVED, CLOSED }
enum class WorkspaceTab { TODAY, INSTRUCTIONS, CONTACTS }

val Instruction.isClosed: Boolean get() = status == Status.DONE || status == Status.DROPPED
val Instruction.reminderMillis: Long? get() = dueAtMs
    ?: dueAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

fun Direction.officerLabel(): String = when (this) {
    Direction.SELF -> "For me"
    Direction.OUTGOING -> "Assigned by me"
    Direction.INCOMING -> "Received"
}

fun Status.officerLabel(): String = when (this) {
    Status.OPEN -> "Open"
    Status.ACK_PENDING -> "Awaiting acknowledgement"
    Status.IN_PROGRESS -> "In progress"
    Status.WAITING_ON_OTHER -> "Waiting for an update"
    Status.REPORTED_DONE -> "Ready to verify"
    Status.DONE -> "Done"
    Status.CARRIED_OVER -> "Carried over"
    Status.DROPPED -> "Closed without action"
}

data class TodayWork(
    val attention: List<Instruction>,
    val followUps: List<Instruction>,
    val upcoming: List<Instruction>,
    val completed: List<Instruction>,
)

fun todayWork(
    instructions: List<Instruction>,
    date: LocalDate,
    zone: ZoneId,
): TodayWork {
    val tomorrow = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val open = instructions.filterNot { it.isClosed }.sortedWith(workOrder)
    val relevant = open.filter { (it.reminderMillis ?: Long.MIN_VALUE) < tomorrow ||
        (it.deadlineAtMs ?: Long.MAX_VALUE) < tomorrow || it.status == Status.REPORTED_DONE }
    return TodayWork(
        attention = relevant.filter { it.direction != Direction.OUTGOING && it.status !in setOf(Status.WAITING_ON_OTHER, Status.REPORTED_DONE) },
        followUps = relevant.filter { it.direction == Direction.OUTGOING || it.status in setOf(Status.WAITING_ON_OTHER, Status.REPORTED_DONE) },
        upcoming = open.filterNot { it in relevant },
        completed = instructions.filter { item ->
            item.status == Status.DONE && item.completedAt?.let {
                runCatching { Instant.parse(it).atZone(zone).toLocalDate() == date }.getOrDefault(false)
            } == true
        },
    )
}

private val workOrder = compareBy<Instruction> { minOf(it.reminderMillis ?: Long.MAX_VALUE, it.deadlineAtMs ?: Long.MAX_VALUE) }
    .thenByDescending { when (it.priority) { Priority.URGENT -> 3; Priority.HIGH -> 2; Priority.NORMAL -> 1; Priority.LOW -> 0 } }
    .thenByDescending { it.capturedAt }

fun filterWork(items: List<Instruction>, people: List<Person>, filter: WorkFilter, query: String): List<Instruction> {
    val names = people.associate { it.id to listOfNotNull(it.name, it.designation, it.station).joinToString(" ") }
    val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return items.filter { item ->
        val category = when (filter) {
            WorkFilter.ALL -> true
            WorkFilter.OPEN -> !item.isClosed
            WorkFilter.FOR_ME -> !item.isClosed && item.direction == Direction.SELF
            WorkFilter.ASSIGNED -> !item.isClosed && item.direction == Direction.OUTGOING
            WorkFilter.RECEIVED -> !item.isClosed && item.direction == Direction.INCOMING
            WorkFilter.CLOSED -> item.isClosed
        }
        val searchable = "${item.title} ${item.rawText} ${names[item.personId].orEmpty()} ${item.audience?.label.orEmpty()} ${item.updates.joinToString(" ") { it.text }}"
        category && words.all { searchable.contains(it, ignoreCase = true) }
    }.sortedWith(if (filter == WorkFilter.CLOSED) compareByDescending { it.updatedAt } else workOrder)
}

/** Fail closed for linked contacts outside the active vault, including deleted/orphaned links. */
fun visibleWork(items: List<Instruction>, people: List<Person>, includeUnlinked: Boolean): List<Instruction> {
    val ids = people.mapTo(mutableSetOf()) { it.id }
    return items.filter {
        val audienceId = (it.audience as? com.kaavalan.note.data.instructions.AudienceRef.ByPerson)?.personId
        !it.isSensitive && (audienceId == null || audienceId in ids) &&
            if (it.personId == null) (audienceId != null || includeUnlinked) else it.personId in ids
    }
}
