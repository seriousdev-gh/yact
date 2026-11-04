package com.seriousdev.caltracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.RoomDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Export/import of the whole Room database as a single .db file via SAF Uris.
 *
 * Import is validated before anything is touched and is atomic: the live file is
 * only renamed away after all checks pass. Callers must rebuild the DI root
 * (`CalTrackerApp.rebuildContainer`) afterwards; Room migrates older-but-known
 * schemas on the new instance automatically.
 */
class BackupManager(
    private val context: Context,
    private val databaseProvider: () -> RoomDatabase,
    private val dbFileName: String = "caltracker.db",
) {

    /** Tables that must exist in any restorable backup (pre-v3 baseline). */
    companion object {
        val REQUIRED_TABLES = setOf(
            "products",
            "meal_entries",
            "profile",
            "weight_log",
            "product_portions",
            "recipe_ingredients",
        )
        const val MIN_SUPPORTED_VERSION = 1
    }

    class ExportResult(val sizeBytes: Long)

    sealed class BackupError(message: String) : IOException(message) {
        class NotReadable : BackupError("Не удалось прочитать выбранный файл")
        class Corrupt : BackupError("Файл повреждён: это не база данных или файл обрезан")
        class MissingTables(val tables: Set<String>) :
            BackupError("В файле нет таблиц: ${tables.joinToString()}")
        class TooNew(val found: Int, val supported: Int) :
            BackupError("Копия из новой версии приложения (схема $found > $supported)")
        class TooOld(val found: Int) :
            BackupError("Слишком старая копия (схема $found)")
    }

    /** Copy the current database (WAL folded in) to [target]. */
    suspend fun export(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        val db = databaseProvider()
        db.openHelper.writableDatabase // ensure open
        // Fold WAL into the main file so the single file is self-contained.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val src = context.getDatabasePath(dbFileName)
        if (!src.exists()) throw IOException("База данных ещё не создана")
        val resolver = context.contentResolver
        resolver.openOutputStream(target, "w").use { out ->
            requireNotNull(out) { "Не удалось открыть выбранный файл для записи" }
            src.inputStream().use { it.copyTo(out) }
        }
        ExportResult(src.length())
    }

    /**
     * Validate [source] and, if OK, replace the live database with it. The old
     * database connection is intentionally left open (see swap comment); the
     * caller must then rebuild the DI root so no new work targets the old file.
     * Any failure leaves the live database untouched.
     */
    suspend fun importAndStage(source: Uri): Int = withContext(Dispatchers.IO) {
        importAndStageBlocking(source)
    }

    private fun importAndStageBlocking(source: Uri): Int {
        val tmp = File.createTempFile("import-", ".db", context.cacheDir)
        try {
            try {
                context.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "Файл не читается" }
                    tmp.outputStream().use { input.copyTo(it) }
                }
            } catch (e: IOException) {
                throw BackupError.NotReadable()
            }

            val version = validate(tmp)

            val db = databaseProvider()
            val live = context.getDatabasePath(dbFileName)
            val stamp = System.currentTimeMillis()
            val staged = File(live.parentFile, "$dbFileName.staging-$stamp")

            tmp.copyTo(staged, overwrite = true)

            // Swap by rename while the old connection stays OPEN: live queries on
            // the old RoomDatabase must never observe a closed database (they run
            // until MainActivity recreates and the new container takes over). The
            // old fds keep serving reads from the renamed inode until process
            // exit; the new container opens the freshly installed file. Suffix on
            // the stale name also dodges case-insensitive rename refusal.
            var renameError: IOException? = null
            var swapped = false
            for (attempt in 1..5) {
                val stale = File(live.parentFile, "$dbFileName.old-$stamp")
                try {
                    if (live.renameTo(stale) || (!live.exists() && stale.exists())) {
                        swapped = true
                        break
                    }
                } catch (e: IOException) {
                    renameError = e
                }
                Thread.sleep(50L * attempt)
                // Android filesystems rename open files happily, so the loop
                // above succeeds with the connection open. Only filesystems that
                // lock open files (desktop JVM tests) force this fallback.
                if (attempt >= 2 && db.isOpen) db.close()
            }
            if (!swapped) {
                staged.delete()
                throw renameError ?: IOException("Не удалось подготовить файл базы к замене")
            }
            if (!staged.renameTo(live)) {
                File(live.parentFile, "$dbFileName.old-$stamp").renameTo(live) // roll back
                throw IOException("Не удалось установить копию на место")
            }
            // Clean WAL/SHM of the replaced database.
            File(live.path + "-wal").delete()
            File(live.path + "-shm").delete()
            return version
        } finally {
            tmp.delete()
        }
    }

    /** Runs all integrity checks on [file]; returns its schema version. */
    private fun validate(file: File): Int {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") throw BackupError.Corrupt()
            }
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null,
            ).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            val missing = REQUIRED_TABLES - tables
            if (missing.isNotEmpty()) throw BackupError.MissingTables(missing)
            val version = db.rawQuery("PRAGMA user_version", null).use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            if (version > AppDatabase.CURRENT_VERSION) {
                throw BackupError.TooNew(version, AppDatabase.CURRENT_VERSION)
            }
            if (version < MIN_SUPPORTED_VERSION) throw BackupError.TooOld(version)
            return version
        } catch (e: android.database.SQLException) {
            throw BackupError.Corrupt()
        } finally {
            db?.close()
        }
    }
}
