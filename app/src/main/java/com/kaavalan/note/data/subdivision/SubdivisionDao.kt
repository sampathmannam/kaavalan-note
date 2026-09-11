package com.kaavalan.note.data.subdivision

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Reads are mode-scoped so a normal-workspace screen can never render a hidden record.
 * The unscoped `snapshot`-style reads exist only for export / backup, which write the
 * whole encrypted database back to the same device.
 *
 * Every write is an [Upsert] (INSERT, then UPDATE on conflict) rather than
 * `INSERT OR REPLACE`: a REPLACE deletes the existing row first, which would fire
 * `ON DELETE CASCADE` on anything referencing it.
 */
@Dao
interface SubdivisionDao {
    @Query("SELECT * FROM subdivision_profile WHERE vaultMode = :mode")
    fun observeProfile(mode: String): Flow<SubdivisionProfile?>

    @Query("SELECT * FROM stations WHERE vaultMode = :mode ORDER BY name COLLATE NOCASE")
    fun observeStations(mode: String): Flow<List<Station>>

    @Query("SELECT * FROM matters WHERE vaultMode = :mode ORDER BY updatedAt DESC")
    fun observeMatters(mode: String): Flow<List<Matter>>

    @Query("SELECT * FROM staff_postings WHERE vaultMode = :mode ORDER BY recordedAt DESC")
    fun observePostings(mode: String): Flow<List<StaffPosting>>

    @Query("SELECT * FROM subdivision_reviews WHERE vaultMode = :mode ORDER BY recordedAt DESC")
    fun observeReviews(mode: String): Flow<List<SubdivisionReview>>

    @Query("SELECT * FROM subdivision_profile WHERE vaultMode = :mode")
    suspend fun profile(mode: String): SubdivisionProfile?

    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun station(id: String): Station?

    @Query("SELECT * FROM matters WHERE id = :id")
    suspend fun matter(id: String): Matter?

    @Query("SELECT * FROM stations WHERE vaultMode = :mode AND nameKey = :key")
    suspend fun stationByName(mode: String, key: String): Station?

    @Query("SELECT * FROM stations WHERE vaultMode = :mode ORDER BY name COLLATE NOCASE")
    suspend fun stationsInMode(mode: String): List<Station>

    @Query("SELECT * FROM matters WHERE vaultMode = :mode ORDER BY updatedAt DESC")
    suspend fun mattersInMode(mode: String): List<Matter>

    @Query("SELECT * FROM staff_postings WHERE vaultMode = :mode AND personId = :personId ORDER BY recordedAt DESC")
    suspend fun postingsFor(mode: String, personId: String): List<StaffPosting>

    @Query("SELECT * FROM subdivision_reviews WHERE vaultMode = :mode AND scopeKey = :scopeKey ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latestReview(mode: String, scopeKey: String): SubdivisionReview?

    // ---- whole-table reads, export / backup only ----

    @Query("SELECT * FROM subdivision_profile")
    suspend fun profiles(): List<SubdivisionProfile>

    @Query("SELECT * FROM stations")
    suspend fun stations(): List<Station>

    @Query("SELECT * FROM matters")
    suspend fun matters(): List<Matter>

    @Query("SELECT * FROM staff_postings")
    suspend fun postings(): List<StaffPosting>

    @Query("SELECT * FROM subdivision_reviews")
    suspend fun reviews(): List<SubdivisionReview>

    // ---- writes ----

    @Upsert suspend fun saveProfile(row: SubdivisionProfile)
    @Upsert suspend fun saveStation(row: Station)
    @Upsert suspend fun saveMatter(row: Matter)
    @Upsert suspend fun savePosting(row: StaffPosting)
    @Upsert suspend fun saveReview(row: SubdivisionReview)

    @Upsert suspend fun saveProfiles(rows: List<SubdivisionProfile>)
    @Upsert suspend fun saveStations(rows: List<Station>)
    @Upsert suspend fun saveMatters(rows: List<Matter>)
    @Upsert suspend fun savePostings(rows: List<StaffPosting>)
    @Upsert suspend fun saveReviews(rows: List<SubdivisionReview>)
}
