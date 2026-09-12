package com.kaavalan.note.data.person

import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncStatus

/**
 * Convert between the [Person] domain model and the [PersonEntity] Room
 * row. The wire format (`PersonInsert`) lives in
 * `SupabasePersonRepository` and is `internal` so the SyncEngine can
 * share it.
 *
 * M2-T8: [toEntity] now carries [Person.updatedAt] through. When the
 * caller has no `updatedAt` (e.g. an in-flight local write before
 * `RoomPersonRepository.create` stamps it), we fall back to "now"
 * in ISO-8601 form so the column is never blank.
 *
 * v2.0 Tier 2: round-trip the [Person.tier], [Person.cadenceOverrideDays],
 * and [Person.lastInteractionAt] fields. Default values on the
 * PersonEntity side keep legacy callers safe.
 */
fun PersonEntity.toDomain(): Person = Person(
    id = id,
    name = name,
    designation = designation,
    station = station,
    phone = phone,
    updatedAt = updatedAt,
    isSensitive = isSensitive,
    tier = tier,
    cadenceOverrideDays = cadenceOverrideDays,
    lastInteractionAt = lastInteractionAt,
    stationId = stationId, isStaff = isStaff, staffActive = staffActive, responsibilities = responsibilities,
)

fun Person.toEntity(syncStatus: String = SyncStatus.SYNCED): PersonEntity = PersonEntity(
    id = id,
    name = name,
    designation = designation,
    station = station,
    phone = phone,
    userId = "",  // Filled by the server on insert; the local row
    // doesn't need it (RLS is enforced at the wire).
    createdAt = updatedAt ?: java.time.Instant.now().toString(),
    updatedAt = updatedAt ?: java.time.Instant.now().toString(),
    isSensitive = isSensitive,
    syncStatus = syncStatus,
    tier = tier,
    cadenceOverrideDays = cadenceOverrideDays,
    lastInteractionAt = lastInteractionAt,
    stationId = stationId, isStaff = isStaff, staffActive = staffActive, responsibilities = responsibilities,
)
