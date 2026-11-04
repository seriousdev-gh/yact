package com.seriousdev.caltracker.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds a v1-shaped database by hand (exact v1 `products`/`profile` columns + tables),
 * runs [AppDatabase.MIGRATION_1_2] and validates the result against the v2 schema
 * via Room's own opening validation, preserving data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {

    private fun createV1Database(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `products` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `kcal` REAL NOT NULL,
                `protein` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `isCustom` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX `index_products_name` ON `products` (`name`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `meal_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `dayEpochDay` INTEGER NOT NULL,
                `mealType` TEXT NOT NULL,
                `productId` INTEGER NOT NULL,
                `grams` REAL NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                FOREIGN KEY(`productId`) REFERENCES `products`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX `index_meal_entries_productId` ON `meal_entries` (`productId`)")
        db.execSQL("CREATE INDEX `index_meal_entries_dayEpochDay` ON `meal_entries` (`dayEpochDay`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `profile` (
                `id` INTEGER NOT NULL,
                `sex` TEXT NOT NULL,
                `birthYear` INTEGER NOT NULL,
                `heightCm` REAL NOT NULL,
                `weightKg` REAL NOT NULL,
                `activityLevel` TEXT NOT NULL,
                `goal` TEXT NOT NULL,
                `manualKcalTarget` REAL,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `weight_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `dayEpochDay` INTEGER NOT NULL,
                `weightKg` REAL NOT NULL,
                `timestampMs` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX `index_weight_log_dayEpochDay` ON `weight_log` (`dayEpochDay`)")
    }

    @Test
    fun `migration 1 to 2 preserves data and passes schema validation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migration-v1.db"
        context.deleteDatabase(dbName)

        // Create the v1 file directly and seed it.
        val raw = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
        createV1Database(raw)
        raw.execSQL(
            "INSERT INTO products (name, category, kcal, protein, fat, carbs, isCustom) " +
                "VALUES ('Гречка', 'Крупы', 343, 12, 3, 71, 0)",
        )
        raw.execSQL(
            "INSERT INTO profile (id, sex, birthYear, heightCm, weightKg, activityLevel, goal, manualKcalTarget, updatedAtMs) " +
                "VALUES (1, 'MALE', 1995, 180, 80, 'LIGHT', 'MAINTAIN', NULL, 1)",
        )
        val grechkaId = raw.compileStatement(
            "SELECT id FROM products WHERE name = 'Гречка'",
        ).simpleQueryForLong()
        raw.execSQL("INSERT INTO meal_entries (dayEpochDay, mealType, productId, grams, timestampMs) " +
            "VALUES (20000, 'BREAKFAST', $grechkaId, 80, 1)")
        raw.setVersion(1)
        raw.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        db.openHelper.writableDatabase // forces migration + schema validation

        val products = db.productDao()
        val grechka = products.search("Гречка").first().single()
        assertThat(grechka.isFavorite).isFalse()
        assertThat(grechka.kind).isEqualTo("PRODUCT")
        assertThat(grechka.barcode).isNull()

        // v3 barcode column is unique and usable.
        products.findByBarcode("4600000000000") // no crash, column exists

        // New tables usable.
        db.portionDao().insert(
            ProductPortionEntity(productId = grechka.id, label = "пакетик", grams = 80.0),
        )
        assertThat(db.portionDao().getForProduct(grechka.id)).hasSize(1)

        val profile = db.profileDao().observe().first()!!
        assertThat(profile.targetProteinG).isNull()
        assertThat(products.count()).isEqualTo(1)
        assertThat(db.mealEntryDao().observeForDay(20_000L).first()).hasSize(1)

        db.close()
        context.deleteDatabase(dbName)
    }
}
