package com.kaavalan.note.di

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaavalan.note.data.local.AppDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays the v10 -> v11 migration through SQLCipher's native Android
 * implementation and checks Room's strict FTS schema contract plus seeded
 * search data. This is intentionally an instrumentation test because the
 * SQLCipher dependency ships Android ABI libraries, not a host-JVM library.
 */
@RunWith(AndroidJUnit4::class)
class Migration10To11SqlCipherDeviceTest {

    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper
    private val dbName = "migration-10-11-device-${System.nanoTime()}.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        helper = SupportOpenHelperFactory(TEST_PASSPHRASE.toByteArray()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
    }

    @After
    fun tearDown() {
        if (::helper.isInitialized) helper.close()
        if (::context.isInitialized) context.deleteDatabase(dbName)
    }

    @Test
    fun migrationProducesRoomCompatibleSearchableFtsTable() {
        val db = helper.writableDatabase
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
            """INSERT INTO instructions
               (id, personId, direction, status, source, priority,
                title, rawText, capturedAt, createdAt, updatedAt, syncStatus)
               VALUES
               ('i-temple', 'p-bhanu', 'OUTGOING', 'OPEN', 'TEXT', 'NORMAL',
                'Temple land inquiry', 'follow up on temple land allocation by Friday',
                '2026-08-12T00:00:00+00:00', '2026-08-12T00:00:00+00:00',
                '2026-08-12T00:00:00+00:00', 'SYNCED')""",
        )

        AppDatabase.MIGRATION_10_11.migrate(db)

        val columns = db.query(
            "SELECT name FROM pragma_table_info('instructions_fts') ORDER BY cid",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(
            listOf("capturedAt", "personId", "rawText", "title"),
            columns,
        )

        val schemaSql = db.query(
            "SELECT sql FROM sqlite_master WHERE name = ?",
            arrayOf<Any?>("instructions_fts"),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        assertNotNull(schemaSql)
        assertTrue(schemaSql!!.contains("tokenize=porter"))
        assertTrue(!schemaSql.contains("tokenize=`"))

        val matches = db.query(
            "SELECT rowid FROM instructions_fts WHERE instructions_fts MATCH ?",
            arrayOf<Any?>("temple"),
        ).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) count++
            count
        }
        assertTrue(matches >= 1)
    }

    private companion object {
        const val TEST_PASSPHRASE = "test-migration-passphrase-do-not-reuse"
    }
}
