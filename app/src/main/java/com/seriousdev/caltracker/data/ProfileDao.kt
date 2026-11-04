package com.seriousdev.caltracker.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Single-row user profile (always id = 1). */
@Entity(tableName = "profile")
data class ProfileEntity(
    @androidx.room.PrimaryKey val id: Long = 1,
    /** [com.seriousdev.caltracker.domain.Sex] name. */
    val sex: String,
    val birthYear: Int,
    val heightCm: Double,
    /** Weight as of the last profile edit; the authoritative series is weight_log. */
    val weightKg: Double,
    /** [com.seriousdev.caltracker.domain.ActivityLevel] name. */
    val activityLevel: String,
    /** [com.seriousdev.caltracker.domain.Goal] name. */
    val goal: String,
    val manualKcalTarget: Double?,
    /** Manual macro targets in grams; when all three are set they define the kcal target. */
    val targetProteinG: Double? = null,
    val targetFatG: Double? = null,
    val targetCarbsG: Double? = null,
    val updatedAtMs: Long,
)

/** Daily weight observation, at most one per day. */
@Entity(
    tableName = "weight_log",
    indices = [Index(value = ["dayEpochDay"], unique = true)],
)
data class WeightEntryEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayEpochDay: Long,
    val weightKg: Double,
    val timestampMs: Long,
)

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)
}

@Dao
interface WeightLogDao {

    @Query("SELECT * FROM weight_log ORDER BY dayEpochDay DESC LIMIT 1")
    fun observeLatest(): Flow<WeightEntryEntity?>

    @Query(
        """
        SELECT * FROM weight_log
        WHERE dayEpochDay >= :fromDay AND dayEpochDay <= :toDay
        ORDER BY dayEpochDay
        """,
    )
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<WeightEntryEntity>>

    /** Replaces the existing entry when the day already has one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entry: WeightEntryEntity)
}
