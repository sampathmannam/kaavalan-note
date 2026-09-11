package com.kaavalan.note.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaavalan.note.data.local.entities.TagEntity
import kotlinx.coroutines.flow.Flow

/**
 * M3-T7: DAO for the local tags mirror. The cloud source of truth is
 * the `tags` table on Supabase; this DAO is the read path for the
 * tag picker and the tag management screen.
 */
@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY usageCount DESC, name ASC LIMIT :limit")
    fun observeTop(limit: Int): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY usageCount DESC, name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE kind = :kind ORDER BY usageCount DESC, name ASC")
    fun observeByKind(kind: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name AND kind = :kind LIMIT 1")
    suspend fun findByNameAndKind(name: String, kind: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tags: List<TagEntity>)

    /**
     * v18: restore / import writes. INSERT-then-UPDATE rather than INSERT OR REPLACE, so
     * restoring a tag does not delete the row and cascade its `instruction_tags` links.
     */
    @androidx.room.Upsert
    suspend fun save(tag: TagEntity)

    @androidx.room.Upsert
    suspend fun saveAll(tags: List<TagEntity>)

    @Query("SELECT * FROM tags")
    suspend fun snapshot(): List<TagEntity>


    @Query("UPDATE tags SET syncStatus = :status WHERE id = :id")
    suspend fun setSyncStatus(id: String, status: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    // v1.6.2: bulk delete for the developer fixture loader.
    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}
