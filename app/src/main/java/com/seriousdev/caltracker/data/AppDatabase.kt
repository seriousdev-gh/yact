package com.seriousdev.caltracker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProductEntity::class,
        MealEntryEntity::class,
        ProfileEntity::class,
        WeightEntryEntity::class,
        ProductPortionEntity::class,
        RecipeIngredientEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun mealEntryDao(): MealEntryDao
    abstract fun profileDao(): ProfileDao
    abstract fun weightLogDao(): WeightLogDao
    abstract fun portionDao(): ProductPortionDao
    abstract fun recipeDao(): RecipeIngredientDao

    companion object {

        /** Keep in sync with @Database(version = 3); bump with every migration. */
        const val CURRENT_VERSION = 3

        /** v1 -> v2: portions, recipe ingredients, product favorite/kind, profile macro targets. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'PRODUCT'",
                )
                db.execSQL(
                    "ALTER TABLE `profile` ADD COLUMN `targetProteinG` REAL",
                )
                db.execSQL(
                    "ALTER TABLE `profile` ADD COLUMN `targetFatG` REAL",
                )
                db.execSQL(
                    "ALTER TABLE `profile` ADD COLUMN `targetCarbsG` REAL",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `product_portions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productId` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `grams` REAL NOT NULL,
                        FOREIGN KEY(`productId`) REFERENCES `products`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_product_portions_productId` " +
                        "ON `product_portions` (`productId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recipe_ingredients` (
                        `recipeId` INTEGER NOT NULL,
                        `ingredientProductId` INTEGER NOT NULL,
                        `grams` REAL NOT NULL,
                        PRIMARY KEY(`recipeId`, `ingredientProductId`),
                        FOREIGN KEY(`recipeId`) REFERENCES `products`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`ingredientProductId`) REFERENCES `products`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recipe_ingredients_ingredientProductId` " +
                        "ON `recipe_ingredients` (`ingredientProductId`)",
                )
            }
        }

        /** v2 -> v3: product barcode (unique, nullable) for Open Food Facts imports. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `barcode` TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_products_barcode` " +
                        "ON `products` (`barcode`)",
                )
            }
        }
    }
}
