package com.kaavalan.note.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionFtsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Tier 1.3 (v2.0): full-text search DAO. The query is a
 * pre-built [com.kaavalan.note.data.search.SearchQuery] string
 * (whitespace tokens, each suffixed with `*`, FTS4-safe).
 *
 * The DAO writes to the FTS table directly (we use a "free"
 * FTS4 entity — no `contentEntity` link — so Room does not
 * auto-generate the sync triggers). The repository keeps
 * the FTS rows in lock-step with the `instructions` rows
 * in a single `runInTransaction` block on every write.
 */
@Dao
interface InstructionFtsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: InstructionFtsEntity)

    @Query("DELETE FROM instructions_fts WHERE rowid = :rowid")
    suspend fun deleteByRowId(rowid: Long)

    @Query("DELETE FROM instructions_fts")
    suspend fun deleteAll()

    @Query("SELECT MAX(rowid) FROM instructions")
    suspend fun maxInstructionRowid(): Long?

    @Query("SELECT rowid FROM instructions WHERE id = :id")
    suspend fun rowidForInstruction(id: String): Long?

    /**
     * Returns up to 200 matching instructions, ordered by
     * `capturedAt` DESC (most recent first). The query
     * parameter is a pre-built FTS4 MATCH expression; the
     * caller must sanitise it via
     * [com.kaavalan.note.data.search.SearchQuery.build].
     */
    @Query(
        """
        SELECT i.* FROM instructions i
        JOIN instructions_fts f ON i.rowid = f.rowid
        WHERE instructions_fts MATCH :matchQuery
        ORDER BY i.capturedAt DESC
        LIMIT 200
        """,
    )
    fun search(matchQuery: String): Flow<List<InstructionEntity>>

    /**
     * One-shot, suspend version. Used by the unit test that
     * asserts the right rows come back in the right order.
     */
    @Query(
        """
        SELECT i.* FROM instructions i
        JOIN instructions_fts f ON i.rowid = f.rowid
        WHERE instructions_fts MATCH :matchQuery
        ORDER BY i.capturedAt DESC
        LIMIT 200
        """,
    )
    suspend fun searchOnce(matchQuery: String): List<InstructionEntity>
}
