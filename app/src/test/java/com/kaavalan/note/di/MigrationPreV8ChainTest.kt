package com.kaavalan.note.di

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pre-v8 upgrade path, end to end.
 *
 * **What this covers that nothing else does.** v2.1.0 replaced
 * `fallbackToDestructiveMigrationFrom(2, 3, 4, 5, 6, 7)` with six
 * explicit migrations, on the reasoning that a v2.0 app with no
 * Supabase to re-fill from cannot afford to wipe a pre-v8
 * database. That made the pre-v8 path a path real user data now
 * travels down. [Migration7To8Test] covers the first step of it,
 * but stops at v8 — and it asserts against SQL mirrored into the
 * test rather than against the production [AppDatabase] migration
 * objects, so it cannot see how the step composes with the ones
 * that follow.
 *
 * The composition is where the defect lives. `MIGRATION_2_3`
 * through `MIGRATION_7_8` all delegate to the same
 * `runPreV8Migration` helper, which adds `vaultMode` to `persons`
 * and `instructions`; `MIGRATION_12_13` adds the same two columns
 * again, unguarded. Each is correct read on its own. Run in
 * sequence — which is exactly what Room does for a database that
 * starts below v8 — the second `ALTER TABLE ... ADD COLUMN
 * vaultMode` hits SQLite's `duplicate column name` error.
 *
 * Room runs migrations inside a transaction, so the throw rolls
 * the whole upgrade back and leaves the database where it
 * started. Every launch retries and fails the same way. This is
 * the same shape as the v14 -> v15 brick that
 * [Migration14To16Test] was written for: invisible to fresh
 * installs, permanent for upgrading ones.
 *
 * These tests drive the real [AppDatabase] migration objects
 * rather than copies of their SQL, so they track the production
 * code rather than a snapshot of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationPreV8ChainTest {

    private val testDbName = "test-migration-pre-v8-${System.nanoTime()}.db"
    private lateinit var dbPath: String

    @Before
    fun setUp() {
        dbPath = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getDatabasePath(testDbName).absolutePath
    }

    @After
    fun tearDown() {
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { java.io.File(it).delete() }
    }

    /**
     * A pre-v8 database: the M3-era `persons` + `instructions`
     * tables, with no `vaultMode` column. Matches the fixture in
     * [Migration7To8Test], which is the project's existing
     * statement of what a v7 database looks like.
     *
     * `version` is a parameter because the same schema stands in
     * for every version in the v2..v7 range — the six migrations
     * that cover that range are the same helper, so the starting
     * `user_version` is the only thing that differs.
     */
    private fun buildPreV8Fixture(version: Int) {
        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE persons (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    designation TEXT,
                    station TEXT,
                    phone TEXT,
                    userId TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    isSensitive INTEGER NOT NULL DEFAULT 0,
                    syncStatus TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE instructions (
                    id TEXT NOT NULL PRIMARY KEY,
                    personId TEXT,
                    direction TEXT NOT NULL,
                    status TEXT NOT NULL,
                    source TEXT NOT NULL,
                    priority TEXT NOT NULL,
                    title TEXT NOT NULL,
                    rawText TEXT NOT NULL,
                    dueAt TEXT,
                    capturedAt TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    isSensitive INTEGER NOT NULL DEFAULT 0,
                    syncStatus TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """INSERT INTO persons
                   (id, name, designation, station, userId,
                    createdAt, updatedAt, syncStatus)
                   VALUES
                   ('p1', 'Ramu', 'SHO', 'Bandipora', '',
                    '2026-08-12T00:00:00Z', '2026-08-12T00:00:00Z', 'SYNCED')""",
            )
            db.execSQL(
                """INSERT INTO instructions
                   (id, personId, direction, status, source, priority,
                    title, rawText, capturedAt, createdAt, updatedAt, syncStatus)
                   VALUES
                   ('i1', 'p1', 'INCOMING', 'OPEN', 'TEXT', 'NORMAL',
                    'Temple land', 'follow up by Friday',
                    '2026-08-12T00:00:00Z', '2026-08-12T00:00:00Z',
                    '2026-08-12T00:00:00Z', 'SYNCED')""",
            )
            db.version = version
        }
    }

    /**
     * A [SupportSQLiteDatabase] over the fixture file, so the
     * production [androidx.room.migration.Migration] objects can
     * be invoked directly. Room is deliberately not involved: it
     * would also run its own schema validation against the v16
     * entities, and this test is about whether the migrations can
     * run at all, not about what they add up to.
     */
    private fun openFixture(version: Int): SupportSQLiteOpenHelper {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbPath)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    private fun columnCount(db: SupportSQLiteDatabase, table: String, column: String): Int =
        db.query("SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = '$column'")
            .use { c ->
                c.moveToFirst()
                c.getInt(0)
            }

    /**
     * The defect, at its smallest. `MIGRATION_7_8` adds
     * `vaultMode`; `MIGRATION_12_13` adds it again. A v7 database
     * runs both.
     */
    @Test
    fun `MIGRATION_12_13 survives a database that already went through MIGRATION_7_8`() {
        buildPreV8Fixture(version = 7)

        openFixture(7).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_7_8.migrate(db)

            // Fails with SQLiteException: duplicate column name:
            // vaultMode until MIGRATION_12_13 tolerates a column
            // an earlier migration in the same chain already added.
            AppDatabase.MIGRATION_12_13.migrate(db)

            assertEquals(
                "persons.vaultMode must exist exactly once after both migrations",
                1,
                columnCount(db, "persons", "vaultMode"),
            )
            assertEquals(
                "instructions.vaultMode must exist exactly once after both migrations",
                1,
                columnCount(db, "instructions", "vaultMode"),
            )
        }
    }

    /**
     * The whole pre-v8 range walks the same helper, so the
     * collision is not specific to v7. A v2 database runs
     * `runPreV8Migration` six times before it ever reaches
     * `MIGRATION_12_13`.
     */
    @Test
    fun `every pre-v8 start version reaches v13 without a duplicate column`() {
        val preV8Migrations = listOf(
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
        )

        for (startVersion in 2..7) {
            tearDown()
            buildPreV8Fixture(version = startVersion)

            openFixture(startVersion).use { helper ->
                val db = helper.writableDatabase
                // Room applies every migration from the start
                // version upward, so a v2 database runs all six.
                preV8Migrations.drop(startVersion - 2).forEach { it.migrate(db) }
                AppDatabase.MIGRATION_12_13.migrate(db)

                assertEquals(
                    "persons.vaultMode must exist exactly once for a v$startVersion start",
                    1,
                    columnCount(db, "persons", "vaultMode"),
                )
            }
        }
    }

    /**
     * The point of keeping the pre-v8 path at all is the data on
     * it. A migration that runs but drops rows would satisfy the
     * tests above and still be the failure v2.1.0 set out to
     * prevent.
     */
    @Test
    fun `pre-v8 rows survive the chain and pick up the vaultMode default`() {
        buildPreV8Fixture(version = 7)

        openFixture(7).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_7_8.migrate(db)
            AppDatabase.MIGRATION_12_13.migrate(db)

            db.query("SELECT name, vaultMode FROM persons WHERE id = 'p1'").use { c ->
                assertTrue("the pre-v8 person row must survive the chain", c.moveToFirst())
                assertEquals("Ramu", c.getString(0))
                assertEquals(
                    "pre-existing rows must default to 'visible', not to hidden",
                    "visible",
                    c.getString(1),
                )
            }
            db.query("SELECT title, vaultMode FROM instructions WHERE id = 'i1'").use { c ->
                assertTrue("the pre-v8 instruction row must survive the chain", c.moveToFirst())
                assertEquals("Temple land", c.getString(0))
                assertEquals("visible", c.getString(1))
            }
        }
    }
}
