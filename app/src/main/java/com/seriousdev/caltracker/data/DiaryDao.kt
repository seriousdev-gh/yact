package com.seriousdev.caltracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One eaten product within a meal slot on a given day.
 * [dayEpochDay] is `java.time.LocalDate.toEpochDay()`.
 */
@Entity(
    tableName = "meal_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("productId"), Index("dayEpochDay")],
)
data class MealEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayEpochDay: Long,
    val mealType: String,
    val productId: Long,
    val grams: Double,
    val timestampMs: Long,
)

/** A [MealEntryEntity] together with its product, for list rendering. */
data class MealEntryWithProduct(
    @Embedded val entry: MealEntryEntity,
    @Relation(parentColumn = "productId", entityColumn = "id")
    val product: ProductEntity,
)

/** Aggregated kcal for one day, produced by the stats queries. */
data class DayKcal(
    val dayEpochDay: Long,
    val kcal: Double,
)

@Dao
interface MealEntryDao {

    @Query("SELECT * FROM meal_entries WHERE dayEpochDay = :day ORDER BY timestampMs")
    fun observeForDay(day: Long): Flow<List<MealEntryEntity>>

    @Transaction
    @Query("SELECT * FROM meal_entries WHERE dayEpochDay = :day")
    fun observeWithProductsForDay(day: Long): Flow<List<MealEntryWithProduct>>

    @Query("SELECT * FROM meal_entries WHERE id = :id")
    suspend fun entryById(id: Long): MealEntryEntity?

    @Insert
    suspend fun insert(entry: MealEntryEntity): Long

    @Update
    suspend fun update(entry: MealEntryEntity)

    @Delete
    suspend fun delete(entry: MealEntryEntity)

    @Query(
        """
        SELECT meal_entries.dayEpochDay AS dayEpochDay,
               SUM(products.kcal * meal_entries.grams / 100.0) AS kcal
        FROM meal_entries
        INNER JOIN products ON products.id = meal_entries.productId
        WHERE meal_entries.dayEpochDay >= :fromDay AND meal_entries.dayEpochDay <= :toDay
        GROUP BY meal_entries.dayEpochDay
        ORDER BY meal_entries.dayEpochDay
        """,
    )
    fun observeDayKcalRange(fromDay: Long, toDay: Long): Flow<List<DayKcal>>
}
