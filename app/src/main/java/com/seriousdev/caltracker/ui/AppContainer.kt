package com.seriousdev.caltracker.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.seriousdev.caltracker.BuildConfig
import com.seriousdev.caltracker.data.AppDatabase
import com.seriousdev.caltracker.data.BackupManager
import com.seriousdev.caltracker.data.DiaryRepository
import com.seriousdev.caltracker.data.LabelExtractor
import com.seriousdev.caltracker.data.LlmSettingsStore
import com.seriousdev.caltracker.data.OpenFoodFactsClient
import com.seriousdev.caltracker.data.ProductRepository
import com.seriousdev.caltracker.data.ProfileRepository
import com.seriousdev.caltracker.data.RecipeRepository
import com.seriousdev.caltracker.data.SeedImporter
import com.seriousdev.caltracker.data.SettingsStore
import com.seriousdev.caltracker.data.WeightRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.seriousdev.caltracker.ui.catalog.PrefilledProduct
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivers scan/recognition results to a specific product form instance.
 * Values are parked under the form's nav id until collected, so a fill that
 * lands while the form is not composing is applied when it returns.
 */
class FormFillBus {
    private val pending = ConcurrentHashMap<Long, PrefilledProduct>()
    private val signal = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    fun put(formNavId: Long, fill: PrefilledProduct) {
        pending[formNavId] = fill
        signal.tryEmit(formNavId)
    }

    /** Emits form nav ids that have a parked fill waiting. */
    fun signals(): Flow<Long> = signal

    /** Removes and returns the fill parked for [formNavId], if any. */
    fun take(formNavId: Long): PrefilledProduct? = pending.remove(formNavId)
}

/** A (product, grams) pair picked through the ingredient add flow. */
data class IngredientPickResult(val productId: Long, val grams: Double)

/**
 * Delivers product picks from the ingredient add flow (search/scan started
 * in a recipe form) to that form, parked under the form's nav id until the
 * form recomposes and collects them.
 */
class IngredientPickBus {
    private val pending = ConcurrentHashMap<Long, IngredientPickResult>()
    private val signal = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    fun put(formNavId: Long, result: IngredientPickResult) {
        pending[formNavId] = result
        signal.tryEmit(formNavId)
    }

    /** Emits form nav ids that have a parked pick waiting. */
    fun signals(): Flow<Long> = signal

    /** Removes and returns the pick parked for [formNavId], if any. */
    fun take(formNavId: Long): IngredientPickResult? = pending.remove(formNavId)
}

/**
 * Hand-rolled DI root: lazily-wired repositories over Room and DataStore.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Application context for SAF/file work (backup). */
    val app: Context get() = appContext

    companion object {
        /**
         * DataStore rejects a second instance over the same file, and containers
         * are rebuilt after a backup import - so the store is process-wide.
         */
        @Volatile
        private var sharedDataStore: DataStore<Preferences>? = null

        private fun dataStoreFor(appContext: Context): DataStore<Preferences> =
            sharedDataStore ?: synchronized(this) {
                sharedDataStore ?: PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ) { File(appContext.filesDir, "datastore/caltracker.preferences_pb") }
                    .also { sharedDataStore = it }
            }
    }

    private val dataStore: DataStore<Preferences> = dataStoreFor(appContext)

    val settingsStore = SettingsStore(dataStore)

    val onboardingDoneFlow: Flow<Boolean> =
        dataStore.data.map { it[SettingsStore.KEY_ONBOARDING_DONE] ?: false }

    val database: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "caltracker.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()

    val products = ProductRepository(database.productDao(), database.portionDao(), database.recipeDao())
    val recipes = RecipeRepository(
        database,
        database.productDao(),
        database.recipeDao(),
        database.portionDao(),
    )
    val diary = DiaryRepository(database.mealEntryDao())
    val profile = ProfileRepository(database.profileDao())
    val weights = WeightRepository(database.weightLogDao())

    val backupManager = BackupManager(appContext, { database })

    val openFoodFacts = OpenFoodFactsClient.forBuild(BuildConfig.DEBUG)

    val llmSettings = LlmSettingsStore(dataStore)
    val labelExtractor = LabelExtractor({ llmSettings.settings.first() })

    /**
     * Barcode/label scans started from a product form deliver their result to
     * the originating form instance (matched by its nav id). Fills are parked
     * by id until the form consumes them, so a scan that completes while the
     * form is off-screen (or between recompositions) is never dropped.
     */
    val formFills = FormFillBus()

    /** Recipe-ingredient picks (search/scan) routed back to their recipe form. */
    val ingredientPicks = IngredientPickBus()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        appScope.launch {
            SeedImporter(appContext, database.productDao(), settingsStore).importIfNeeded()
        }
    }
}
