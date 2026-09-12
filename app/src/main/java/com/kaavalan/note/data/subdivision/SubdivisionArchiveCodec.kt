package com.kaavalan.note.data.subdivision

import kotlinx.serialization.json.Json

/**
 * The one place the subdivision archive is turned into text and back.
 *
 * Shared by the plain JSON export, the CSV export's `subdivision_json` block and the
 * manual backup's `subdivision` object, so the three formats cannot drift apart and a fix
 * to one is a fix to all three.
 *
 * Every failure here is a refusal, never a partial read. New metadata is only worth
 * writing if recovery restores it faithfully, and a half-restored subdivision (stations
 * without their matters, postings pointing at nobody) is worse than a clear error the
 * officer can act on.
 */
object SubdivisionArchiveCodec {

    private val json = Json {
        // Tolerate fields a future version adds, so this build can still read the parts of
        // a compatible file it does understand. The explicit version check below is what
        // refuses an actually incompatible file.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(archive: SubdivisionArchive): String =
        json.encodeToString(SubdivisionArchive.serializer(), archive)

    /**
     * Decode an archive, refusing anything this build cannot honour.
     *
     * A blank value means "this file carries no subdivision data", which is the correct
     * reading of a pre-2.6.0 export and is not an error.
     */
    fun decode(text: String?): SubdivisionArchive {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return SubdivisionArchive()
        val archive = runCatching { json.decodeFromString(SubdivisionArchive.serializer(), clean) }
            .getOrElse {
                throw IllegalArgumentException(
                    "The subdivision section of this file could not be read. Nothing has been changed.",
                )
            }
        require(archive.version <= SubdivisionArchive.ARCHIVE_VERSION) {
            "This file was written by a newer version of KaavalanNote (subdivision format " +
                archive.version + "; this build reads up to " + SubdivisionArchive.ARCHIVE_VERSION +
                "). Update the app before restoring it."
        }
        return archive
    }

    /**
     * Structural checks that must all pass BEFORE a single row is written.
     *
     * [personVaultModes] is the id-to-vaultMode map of every person that will exist after
     * the restore: the people in the file plus the people already on the device. Using
     * only one of those would reject a legitimate incremental import.
     */
    fun validate(archive: SubdivisionArchive, personVaultModes: Map<String, String>) {
        requireDistinct(archive.profiles.map { it.vaultMode }, "subdivision profile")
        requireDistinct(archive.stations.map { it.id }, "station")
        requireDistinct(archive.matters.map { it.id }, "matter")
        requireDistinct(archive.postings.map { it.id }, "staff posting")
        requireDistinct(archive.reviews.map { it.id }, "review")
        requireDistinct(archive.stations.map { it.vaultMode + " " + it.nameKey }, "station name")

        archive.profiles.forEach { profile ->
            require(profile.name.isNotBlank()) {
                "The subdivision in this file has no name. Nothing has been changed."
            }
        }
        val stationsById = archive.stations.associateBy { it.id }
        archive.stations.forEach { station ->
            require(station.name.isNotBlank()) {
                "A station in this file has no name. Nothing has been changed."
            }
            // The name key is what uniqueness is enforced on. A file whose key disagrees
            // with its own name would restore a station that can never be found again.
            require(station.nameKey == SubdivisionRepository.stationKey(station.name)) {
                "The station " + quote(station.name) + " has an inconsistent name key. Nothing has been changed."
            }
        }
        archive.matters.forEach { matter ->
            require(matter.title.isNotBlank()) {
                "A matter in this file has no title. Nothing has been changed."
            }
            val stationId = matter.stationId
            if (stationId != null) {
                val station = stationsById[stationId]
                require(station != null) {
                    "The matter " + quote(matter.title) + " points at a station that is not in this file. " +
                        "Nothing has been changed."
                }
                require(station.vaultMode == matter.vaultMode) {
                    "The matter " + quote(matter.title) + " points at a station in a different workspace. " +
                        "Nothing has been changed."
                }
            }
        }
        archive.postings.forEach { posting ->
            val mode = personVaultModes[posting.personId]
            require(mode != null) {
                "A staff posting in this file refers to a contact that does not exist. Nothing has been changed."
            }
            require(mode == posting.vaultMode) {
                "A staff posting in this file refers to a contact in a different workspace. " +
                    "Nothing has been changed."
            }
        }
        archive.reviews.forEach { review ->
            require(SubdivisionProjections.isKnownScope(review.scopeKey)) {
                "A review in this file has an unrecognised scope. Nothing has been changed."
            }
            require(review.openCount >= 0 && review.readyCount >= 0) {
                "A review in this file has an impossible count. Nothing has been changed."
            }
            SubdivisionProjections.stationIdOf(review.scopeKey)?.let { id ->
                require(stationsById.containsKey(id)) {
                    "A review in this file refers to a station that is not in this file. Nothing has been changed."
                }
            }
            SubdivisionProjections.personIdOf(review.scopeKey)?.let { id ->
                require(personVaultModes.containsKey(id)) {
                    "A review in this file refers to a contact that does not exist. Nothing has been changed."
                }
            }
        }
    }

    private fun quote(value: String) = "\"" + value + "\""

    private fun requireDistinct(values: List<String>, what: String) {
        val duplicate = values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) {
            "This file contains the same " + what + " twice. Nothing has been changed."
        }
    }
}
