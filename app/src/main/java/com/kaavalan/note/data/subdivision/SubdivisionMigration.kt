package com.kaavalan.note.data.subdivision

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v18 (2.6.0, subdivision CRM): add the five subdivision tables and the station / matter
 * columns on `persons` and `instructions`.
 *
 * Non-destructive by construction — every statement is a `CREATE TABLE`, a
 * `CREATE INDEX`, an `ALTER TABLE ADD COLUMN` or an `UPDATE`. No table is rebuilt, so
 * every existing contact, note, journal entry, date, tag, link and hidden row survives
 * untouched.
 *
 * **Station backfill.** Contacts already carry free-text station names. Those names are
 * folded into stable station rows, one per distinct name **per vault**, so a hidden
 * contact's station never merges with a visible one. Each existing contact and each
 * existing instruction is then stamped with the resulting station ID; from here on the
 * instruction keeps that ID even if the officer is later transferred.
 *
 * **What is deliberately NOT seeded.** No subdivision profile, no staff classification,
 * no matter, no review. `isStaff` defaults to 0 for every existing contact: marking
 * someone as staff is an explicit act by the officer, and inventing it would put words
 * in their mouth about real people.
 *
 * `lower()` in SQLite folds ASCII only. [SubdivisionRepository.stationKey] folds exactly
 * the same range on purpose, so a name typed in Tamil or with a non-ASCII capital keeps
 * its own identity in both the migration and the app.
 */
val SUBDIVISION_MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS subdivision_profile (vaultMode TEXT NOT NULL PRIMARY KEY, " +
                "name TEXT NOT NULL, district TEXT NOT NULL, officerName TEXT NOT NULL, updatedAt TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS stations (id TEXT NOT NULL PRIMARY KEY, vaultMode TEXT NOT NULL, " +
                "name TEXT NOT NULL, nameKey TEXT NOT NULL, kind TEXT NOT NULL, notes TEXT NOT NULL, " +
                "archived INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_stations_vaultMode_nameKey ON stations(vaultMode, nameKey)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS matters (id TEXT NOT NULL PRIMARY KEY, vaultMode TEXT NOT NULL, " +
                "title TEXT NOT NULL, stationId TEXT, reference TEXT NOT NULL, description TEXT NOT NULL, " +
                "archived INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_matters_vaultMode ON matters(vaultMode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_matters_stationId ON matters(stationId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS staff_postings (id TEXT NOT NULL PRIMARY KEY, vaultMode TEXT NOT NULL, " +
                "personId TEXT NOT NULL, fromStation TEXT NOT NULL, toStation TEXT NOT NULL, note TEXT NOT NULL, " +
                "recordedAt TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_staff_postings_vaultMode ON staff_postings(vaultMode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_staff_postings_personId ON staff_postings(personId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS subdivision_reviews (id TEXT NOT NULL PRIMARY KEY, vaultMode TEXT NOT NULL, " +
                "scopeKey TEXT NOT NULL, scopeTitle TEXT NOT NULL, notes TEXT NOT NULL, recordedAt TEXT NOT NULL, " +
                "openCount INTEGER NOT NULL, readyCount INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_subdivision_reviews_vaultMode_scopeKey " +
                "ON subdivision_reviews(vaultMode, scopeKey)",
        )

        db.execSQL("ALTER TABLE persons ADD COLUMN stationId TEXT")
        db.execSQL("ALTER TABLE persons ADD COLUMN isStaff INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE persons ADD COLUMN staffActive INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE persons ADD COLUMN responsibilities TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_persons_stationId ON persons(stationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_persons_isStaff ON persons(isStaff)")

        db.execSQL("ALTER TABLE instructions ADD COLUMN stationId TEXT")
        db.execSQL("ALTER TABLE instructions ADD COLUMN matterId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_instructions_stationId ON instructions(stationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_instructions_matterId ON instructions(matterId)")

        // One station row per distinct trimmed, ASCII-folded name per vault.
        db.execSQL(
            "INSERT INTO stations (id, vaultMode, name, nameKey, kind, notes, archived, createdAt, updatedAt) " +
                "SELECT 'station-' || lower(hex(randomblob(16))), vaultMode, min(trim(station)), " +
                "lower(trim(station)), 'Station', '', 0, min(createdAt), max(updatedAt) " +
                "FROM persons WHERE station IS NOT NULL AND trim(station) != '' " +
                "GROUP BY vaultMode, lower(trim(station))",
        )
        db.execSQL(
            "UPDATE persons SET stationId = (SELECT s.id FROM stations s " +
                "WHERE s.vaultMode = persons.vaultMode AND s.nameKey = lower(trim(persons.station))) " +
                "WHERE station IS NOT NULL AND trim(station) != ''",
        )
        // Snapshot the responsible contact's station onto existing work, so the record keeps
        // where the work actually sat even after a later transfer.
        db.execSQL(
            "UPDATE instructions SET stationId = (SELECT p.stationId FROM persons p " +
                "WHERE p.id = coalesce(instructions.personId, " +
                "CASE WHEN instructions.audienceKind = 'PERSON' THEN instructions.audienceTarget END))",
        )
    }
}
