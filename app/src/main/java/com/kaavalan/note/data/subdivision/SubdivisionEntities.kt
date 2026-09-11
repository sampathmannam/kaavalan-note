package com.kaavalan.note.data.subdivision

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Each installation owns its own records. Vault scopes are never shared through the UI. */
@Serializable
@Entity(tableName = "subdivision_profile")
data class SubdivisionProfile(
    @PrimaryKey val vaultMode: String,
    val name: String,
    val district: String = "",
    val officerName: String = "",
    val updatedAt: String,
)

@Serializable
@Entity(tableName = "stations", indices = [Index(value = ["vaultMode", "nameKey"], unique = true)])
data class Station(
    @PrimaryKey val id: String,
    val vaultMode: String,
    val name: String,
    val nameKey: String,
    val kind: String = "Station",
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
@Entity(tableName = "matters", indices = [Index("vaultMode"), Index("stationId")])
data class Matter(
    @PrimaryKey val id: String,
    val vaultMode: String,
    val title: String,
    val stationId: String? = null,
    val reference: String = "",
    val description: String = "",
    val archived: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
@Entity(tableName = "staff_postings", indices = [Index("vaultMode"), Index("personId")])
data class StaffPosting(
    @PrimaryKey val id: String,
    val vaultMode: String,
    val personId: String,
    val fromStation: String,
    val toStation: String,
    val note: String,
    val recordedAt: String,
)

@Serializable
@Entity(tableName = "subdivision_reviews", indices = [Index(value = ["vaultMode", "scopeKey"])])
data class SubdivisionReview(
    @PrimaryKey val id: String,
    val vaultMode: String,
    val scopeKey: String,
    val scopeTitle: String,
    val notes: String,
    val recordedAt: String,
    val openCount: Int,
    val readyCount: Int,
)

/**
 * The portable shape of the subdivision tables. Written into plain JSON / CSV exports and
 * into the manual backup under a `subdivision` key, so recovery restores the officer's
 * own structure rather than only their notes.
 *
 * [version] is the archive's own contract version, independent of the manual-backup
 * `schema_version`. A reader that does not recognise a newer archive version must refuse
 * the file rather than silently drop the fields it cannot parse.
 */
@Serializable
data class SubdivisionArchive(
    val version: Int = ARCHIVE_VERSION,
    val profiles: List<SubdivisionProfile> = emptyList(),
    val stations: List<Station> = emptyList(),
    val matters: List<Matter> = emptyList(),
    val postings: List<StaffPosting> = emptyList(),
    val reviews: List<SubdivisionReview> = emptyList(),
) {
    val isEmpty: Boolean
        get() = profiles.isEmpty() && stations.isEmpty() && matters.isEmpty() &&
            postings.isEmpty() && reviews.isEmpty()

    companion object {
        /** Bump only for a shape change that an older reader cannot handle. */
        const val ARCHIVE_VERSION = 1
    }
}
