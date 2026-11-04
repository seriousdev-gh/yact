package com.seriousdev.caltracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A named serving of a product, e.g. "ломтик" = 15 g of bread.
 * Products may carry 0..N portions; diary entries still store plain grams.
 */
@Entity(
    tableName = "product_portions",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("productId")],
)
data class ProductPortionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val label: String,
    val grams: Double,
)

@Dao
interface ProductPortionDao {

    @Query("SELECT * FROM product_portions WHERE productId = :productId ORDER BY id")
    fun observeForProduct(productId: Long): Flow<List<ProductPortionEntity>>

    @Query("SELECT * FROM product_portions WHERE productId = :productId ORDER BY id")
    suspend fun getForProduct(productId: Long): List<ProductPortionEntity>

    /** Replaces the full portion list of a product in one transaction. */
    @Transaction
    suspend fun replaceAll(productId: Long, portions: List<Pair<String, Double>>) {
        clear(productId)
        portions.forEach { (label, grams) ->
            insert(ProductPortionEntity(productId = productId, label = label, grams = grams))
        }
    }

    @Insert
    suspend fun insert(portion: ProductPortionEntity)

    @Query("DELETE FROM product_portions WHERE productId = :productId")
    suspend fun clear(productId: Long)

    @Delete
    suspend fun delete(portion: ProductPortionEntity)
}
