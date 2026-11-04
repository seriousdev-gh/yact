package com.seriousdev.caltracker.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of `assets/seed_products.json`. */
@Serializable
data class SeedProduct(
    val name: String,
    val category: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
)

object SeedProducts {

    const val ASSET_NAME = "seed_products.json"

    private val json = Json { ignoreUnknownKeys = false }

    /** Parses the seed asset; throws [kotlinx.serialization.SerializationException] on malformed input. */
    fun parse(text: String): List<SeedProduct> = json.decodeFromString<List<SeedProduct>>(text)

    fun readAsset(context: Context): List<SeedProduct> =
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }

    fun toEntity(seed: SeedProduct): ProductEntity = ProductEntity(
        name = seed.name,
        category = seed.category,
        kcal = seed.kcal,
        protein = seed.protein,
        fat = seed.fat,
        carbs = seed.carbs,
        isCustom = false,
    )
}

/**
 * Bulk-inserts the built-in catalog into [ProductDao] at most once per database.
 * Names are unique, so re-running on an already seeded database is a no-op
 * ([Insert IGNORE] skips existing names).
 */
class SeedImporter(
    private val context: Context,
    private val productDao: ProductDao,
    private val settingsStore: SettingsStore,
) {
    private val mutex = Mutex()

    /** @return number of products actually inserted (0 when already imported). */
    suspend fun importIfNeeded(): Int = mutex.withLock {
        if (settingsStore.isSeedImported()) return 0
        val rows = SeedProducts.readAsset(context).map { SeedProducts.toEntity(it) }
        val inserted = productDao.insertAll(rows).count { it >= 0 }
        settingsStore.setSeedImported(true)
        inserted
    }
}
