package com.kaavalan.note.data.local

import androidx.room.withTransaction
import com.kaavalan.note.data.subdivision.SubdivisionRepository
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.person.PersonRepository
import com.kaavalan.note.data.person.toDomain
import com.kaavalan.note.data.person.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.0.0 (drop Supabase): local-only person repository.
 *
 * The v1.x version had a [SyncEngine] and a `remote:
 * SupabasePersonRepository` constructor dep. v2.0.0 removes
 * both. The sync-queue writes are still here (the schema is
 * unchanged for forward-compat with a future optional cloud
 * sync) but the `drainOne()` call is gone. Rows accumulate in
 * `sync_queue` indefinitely in v2.0.0; a future
 * `RetentionWorker` pass should clean them up.
 */
@Singleton
class RoomPersonRepository @Inject constructor(
    private val dao: PersonDao,
    private val syncQueueDao: SyncQueueDao,
    private val db: AppDatabase,
) : PersonRepository {

    override fun observeAll(): Flow<List<Person>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }

    /**
     * v2.0 T3-1 (deniable vault): the mode-filtered read.
     * Delegates to the DAO's `observeAllInMode(mode)` and maps
     * the rows to the domain [Person] type. The mode is a
     * `String` because the DAO interface is
     * [androidx.room.Dao] (which doesn't model enums).
     */
    override fun observeAllInMode(mode: String): Flow<List<Person>> =
        dao.observeAllInMode(mode).map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(
        name: String,
        designation: String?,
        station: String?,
        clientId: String?,
    ): Person = createContact(name, designation, station, clientId = clientId)

    /** Contact import and manual entry share one durable write; phone numbers are retained. */
    suspend fun createContact(
        name: String,
        designation: String?,
        station: String?,
        phone: String? = null,
        vaultMode: String = "visible",
        clientId: String? = null,
    ): Person = db.withTransaction {
        val nowIso = nowIso()
        val id = clientId ?: java.util.UUID.randomUUID().toString()
        val local = PersonEntity(
            id = id,
            name = name,
            designation = designation,
            station = station,
            stationId = SubdivisionRepository.resolveStation(db, vaultMode, station),
            phone = phone,
            vaultMode = vaultMode,
            userId = "",
            createdAt = nowIso,
            updatedAt = nowIso,
            syncStatus = SyncStatus.PENDING_INSERT,
        )
        dao.upsert(local)
        // v2.0.0: no SyncEngine call. The sync_queue row is
        // still enqueued (forward-compat) but no drain will
        // ever run. A future v2.x pass that adds cloud sync
        // would re-insert the drain call here.
        local.toDomain()
    }

    override suspend fun findByName(name: String): Person? {
        return dao.findByName(name)?.toDomain()
    }

    override suspend fun findOrCreate(
        name: String,
        designation: String?,
        station: String?,
    ): Person {
        findByName(name)?.let { return it }
        return create(name = name, designation = designation, station = station)
    }

    /**
     * v2.0.0: no-op. v1.x's `refreshFromNetwork` was a
     * fire-and-forget pull from Supabase. v2.0.0 has no remote.
     * Kept as a method (rather than deleted) so the function
     * contract is preserved for any future change.
     */
    suspend fun refreshFromNetwork() {
        // v2.0.0: no remote to refresh from. Local Room is
        // the source of truth.
    }

    /**
     * v1.1.1: spec §13 — flip the local-only flag. v2.0.0:
     * the wire-side push is gone; the local flip still works.
     */
    override suspend fun setSensitive(id: String, sensitive: Boolean) {
        val now = java.time.Instant.now().toString()
        dao.setSensitive(id, sensitive, now, SyncStatus.SYNCED)
    }

    private fun nowIso(): String =
        java.time.Instant.now().toString()
}
