package com.seriousdev.caltracker.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Plain Application: CalTrackerApp would build AppContainer, seed and hold the same db file open.
@Config(sdk = [35], application = Application::class)
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric shares the sandbox filesystem across tests; start from a clean db.
        context.getDatabasePath("caltracker.db").delete()
        File(context.getDatabasePath("caltracker.db").parentFile, ".")
            .listFiles()?.filter { it.name.startsWith("caltracker.db") }?.forEach { it.delete() }
        db = Room.databaseBuilder(context, AppDatabase::class.java, "caltracker.db").build()
        backup = BackupManager(context, { db })
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    private suspend fun seed() {
        db.productDao().insert(
            ProductEntity(
                name = "Гречка", category = "Крупы", kcal = 110.0,
                protein = 4.0, fat = 1.0, carbs = 21.0,
            ),
        )
    }

    private fun fileUri(file: File): Uri = Uri.fromFile(file)

    @Test
    fun `export writes valid sqlite copy with same content`() = runTest {
        seed()
        val target = File.createTempFile("export", ".db", context.cacheDir)
        val result = backup.export(fileUri(target))
        assertThat(result.sizeBytes).isGreaterThan(0L)

        // The exported file must be a restorable sqlite db with our row.
        val check = android.database.sqlite.SQLiteDatabase.openDatabase(
            target.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        check.rawQuery("select name from products", null).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Гречка")
        }
        check.close()
        target.delete()
    }

    @Test
    fun `import replaces live database and keeps old as backup file`() = runTest {
        seed()
        val snapshot = File.createTempFile("snap", ".db", context.cacheDir)
        backup.export(fileUri(snapshot))

        // Change the live db after export.
        db.productDao().insert(
            ProductEntity(
                name = "Удалится при импорте", category = "Крупы", kcal = 1.0,
                protein = 0.0, fat = 0.0, carbs = 0.0,
            ),
        )

        val version = backup.importAndStage(fileUri(snapshot))
        assertThat(version).isEqualTo(AppDatabase.CURRENT_VERSION)

        val live = context.getDatabasePath("caltracker.db")
        val restored = android.database.sqlite.SQLiteDatabase.openDatabase(
            live.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        restored.rawQuery("select count(*) from products", null).use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        restored.close()

        // The previous live db is preserved next to it.
        assertThat(
            live.parentFile!!.list { _, n -> n.startsWith("caltracker.db.old-") }!!,
        ).isNotEmpty()
        snapshot.delete()
    }

    @Test
    fun `import of garbage file is rejected and leaves live db untouched`() = runTest {
        seed()
        val garbage = File.createTempFile("junk", ".db", context.cacheDir)
        garbage.writeBytes(ByteArray(4096) { (it % 251).toByte() })

        val error = runCatching { backup.importAndStage(fileUri(garbage)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupManager.BackupError.Corrupt::class.java)

        val live = context.getDatabasePath("caltracker.db")
        val reopened = android.database.sqlite.SQLiteDatabase.openDatabase(
            live.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        reopened.rawQuery("select count(*) from products", null).use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        reopened.close()
        garbage.delete()
    }

    @Test
    fun `sqlite file without required tables is rejected`() = runTest {
        val foreign = File.createTempFile("foreign", ".db", context.cacheDir)
        foreign.delete()
        val foreignDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(foreign, null)
        foreignDb.execSQL("create table something_else (id integer primary key)")
        foreignDb.close()

        val error = runCatching { backup.importAndStage(fileUri(foreign)) }.exceptionOrNull()
        error as BackupManager.BackupError.MissingTables
        assertThat(error.tables).contains("products")
        foreign.delete()
    }

    @Test
    fun `backup from newer schema is rejected`() = runTest {
        seed()
        val snapshot = File.createTempFile("future", ".db", context.cacheDir)
        backup.export(fileUri(snapshot))
        val bump = android.database.sqlite.SQLiteDatabase.openDatabase(
            snapshot.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        bump.execSQL("PRAGMA user_version=${AppDatabase.CURRENT_VERSION + 1}")
        bump.close()

        val error = runCatching { backup.importAndStage(fileUri(snapshot)) }.exceptionOrNull()
        error as BackupManager.BackupError.TooNew
        assertThat(error.found).isEqualTo(AppDatabase.CURRENT_VERSION + 1)
        snapshot.delete()
    }
}
