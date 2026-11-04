package com.seriousdev.caltracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A food product, either seeded from the built-in catalog or created by the user.
 * Nutrition values are per 100 g.
 */
@Entity(
    tableName = "products",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["barcode"], unique = true),
    ],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    /** false for catalog (seeded) products, true for user-created ones. */
    val isCustom: Boolean = false,
    /** Pinned by the user; surfaced first in add-food search. */
    val isFavorite: Boolean = false,
    /** "PRODUCT" or "RECIPE" ([com.seriousdev.caltracker.domain.ProductKind]). */
    val kind: String = "PRODUCT",
    /** EAN/UPC from barcode import; null for manual and seeded products. */
    val barcode: String? = null,
)

@Dao
interface ProductDao {

    /** Products whose name starts with [pattern], case-insensitive; `%`/`_` in pattern are literal. */
    @Query("""SELECT * FROM products WHERE name LIKE :pattern || '%' ESCAPE '\' ORDER BY name COLLATE NOCASE""")
    fun search(pattern: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY category, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE category = :category ORDER BY name COLLATE NOCASE")
    fun observeByCategory(category: String): Flow<List<ProductEntity>>

    @Query("SELECT DISTINCT category FROM products ORDER BY category COLLATE NOCASE")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT * FROM products WHERE isCustom = 1 ORDER BY name COLLATE NOCASE")
    fun observeCustom(): Flow<List<ProductEntity>>

    /** Custom products and recipes: everything the user created. */
    @Query("SELECT * FROM products WHERE isCustom = 1 OR kind = 'RECIPE' ORDER BY name COLLATE NOCASE")
    fun observeUserContent(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE isFavorite = 1 ORDER BY name COLLATE NOCASE")
    fun observeFavorites(): Flow<List<ProductEntity>>

    /** Products eaten since [sinceDay], most recently used first; recipes included. */
    @Query(
        """
        SELECT p.* FROM products p
        INNER JOIN meal_entries m ON m.productId = p.id
        WHERE m.dayEpochDay >= :sinceDay
        GROUP BY p.id
        ORDER BY MAX(m.timestampMs) DESC
        LIMIT :max
        """,
    )
    fun observeRecentlyUsed(sinceDay: Long, max: Int): Flow<List<ProductEntity>>

    @Query("UPDATE products SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): ProductEntity?

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(products: List<ProductEntity>): List<Long>

    @Insert
    suspend fun insert(product: ProductEntity): Long

    @Update
    suspend fun update(product: ProductEntity)

    @Delete
    suspend fun delete(product: ProductEntity)
}
