package com.seriousdev.caltracker.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One ingredient line of a recipe. The recipe itself is a row in `products`
 * with `kind = 'RECIPE'`; its per-100 g values are recomputed from ingredients on save.
 */
@Entity(
    tableName = "recipe_ingredients",
    primaryKeys = ["recipeId", "ingredientProductId"],
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredientProductId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ingredientProductId")],
)
data class RecipeIngredientEntity(
    val recipeId: Long,
    val ingredientProductId: Long,
    val grams: Double,
)

/** An ingredient joined with its product's nutrition, for recipe recomputation. */
data class IngredientWithProduct(
    @Embedded val ingredient: RecipeIngredientEntity,
    @Relation(
        parentColumn = "ingredientProductId",
        entityColumn = "id",
    )
    val product: ProductEntity,
)

@Dao
interface RecipeIngredientDao {

    @Transaction
    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY rowid")
    suspend fun getWithProducts(recipeId: Long): List<IngredientWithProduct>

    @Transaction
    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY rowid")
    fun observeWithProducts(recipeId: Long): Flow<List<IngredientWithProduct>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ingredients: List<RecipeIngredientEntity>)

    @Query("DELETE FROM recipe_ingredients WHERE recipeId = :recipeId")
    suspend fun clear(recipeId: Long)

    /** Ids of recipes that use [productId] as an ingredient. */
    @Query("SELECT DISTINCT recipeId FROM recipe_ingredients WHERE ingredientProductId = :productId")
    suspend fun recipesUsing(productId: Long): List<Long>

    @Query("DELETE FROM recipe_ingredients WHERE ingredientProductId = :productId")
    suspend fun removeIngredientFromAll(productId: Long)
}
