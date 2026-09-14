package com.kaavalan.note.data.captures

import androidx.room.withTransaction
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.CaptureDao
import com.kaavalan.note.data.local.SyncQueueDao
import com.kaavalan.note.data.local.entities.CaptureEntity
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-first capture storage.
 *
 * A capture row and its empty, forward-compatible outbox marker commit in one Room
 * transaction. The outbox deliberately contains no note text; retention removes markers
 * after their source capture expires. Cloud sync is disabled in the current product, so
 * no network operation is part of the save path.
 */
@Singleton
class RoomCaptureRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: CaptureDao,
    private val syncQueueDao: SyncQueueDao,
) : CaptureRepository {

    private val now: () -> Long = { System.currentTimeMillis() }

    override suspend fun create(rawText: String, mode: CaptureMode): Capture {
        val nowIso = nowIso()
        val id = UUID.randomUUID().toString()
        val local = CaptureEntity(
            id = id,
            mode = mode.toDbValue(),
            rawText = rawText,
            audioUri = null,
            imageUri = null,
            processed = false,
            createdAt = nowIso,
            // Kept for forward compatibility with an optional future sync implementation.
            // In the current local-only product this is simply persisted metadata.
            syncStatus = SyncStatus.PENDING_INSERT,
        )
        db.withTransaction {
            dao.upsert(local)
            syncQueueDao.enqueue(
                SyncQueueEntity(
                    table = "captures",
                    rowId = id,
                    op = SyncQueueEntity.OP_INSERT,
                    payloadJson = "{}",
                    createdAt = now(),
                ),
            )
        }
        return local.toDomain()
    }

    override suspend fun markProcessed(id: String) {
        db.withTransaction {
            // Guard before the update so a stale caller cannot enqueue an outbox row for
            // a capture that no longer exists.
            dao.getById(id) ?: return@withTransaction
            dao.setProcessed(id, true, SyncStatus.PENDING_UPDATE)
            syncQueueDao.enqueue(
                SyncQueueEntity(
                    table = "captures",
                    rowId = id,
                    op = SyncQueueEntity.OP_UPDATE,
                    payloadJson = "{}",
                    createdAt = now(),
                ),
            )
        }
    }

    private fun nowIso(): String = java.time.Instant.now().toString()

    private fun CaptureEntity.toDomain(): Capture = Capture(
        id = id,
        mode = CaptureMode.fromDbValue(mode),
        rawText = rawText,
        audioUri = audioUri,
        imageUri = imageUri,
        processed = processed,
        createdAt = createdAt,
    )
}
