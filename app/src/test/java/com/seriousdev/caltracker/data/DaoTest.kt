package com.seriousdev.caltracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaoTest {

    private lateinit var db: AppDatabase

    private val day = 20_000L

    private fun product(
        name: String,
        kcal: Double = 100.0,
        protein: Double = 5.0,
        fat: Double = 2.0,
        carbs: Double = 15.0,
        isCustom: Boolean = false,
        category: String = "Прочее",
    ) = ProductEntity(name = name, category = category, kcal = kcal, protein = protein, fat = fat, carbs = carbs, isCustom = isCustom)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `search matches prefix case-insensitively`() = runTest {
        val dao = db.productDao()
        dao.insertAll(listOf(product("Куриная грудка"), product("Курица целиком"), product("Утка")))

        val found = dao.search("куриц").first()
        assertThat(found.map { it.name }).containsExactly("Курица целиком")
    }

    @Test
    fun `search treats escaped wildcards as literals`() = runTest {
        val dao = db.productDao()
        dao.insertAll(listOf(product("Халва"), product("Сахар")))
        // The DAO layer declares ESCAPE '\'; callers escape wildcards (see ProductRepository.search).
        assertThat(dao.search("\\%").first()).isEmpty()
    }

    @Test
    fun `categories are deduplicated and sorted`() = runTest {
        val dao = db.productDao()
        dao.insertAll(listOf(product("A", category = "Яйца"), product("B", category = "Овощи"), product("C", category = "Яйца")))
        assertThat(dao.observeCategories().first()).containsExactly("Овощи", "Яйца").inOrder()
    }

    @Test
    fun `day kcal aggregation scales by grams`() = runTest {
        val productId = db.productDao().insert(product("Рис", kcal = 200.0))
        val entries = db.mealEntryDao()
        entries.insert(MealEntryEntity(dayEpochDay = day, mealType = "LUNCH", productId = productId, grams = 150.0, timestampMs = 1))
        entries.insert(MealEntryEntity(dayEpochDay = day, mealType = "DINNER", productId = productId, grams = 50.0, timestampMs = 2))
        entries.insert(MealEntryEntity(dayEpochDay = day + 1, mealType = "LUNCH", productId = productId, grams = 100.0, timestampMs = 3))

        val rows = entries.observeDayKcalRange(day, day + 1).first()
        assertThat(rows.map { it.dayEpochDay to it.kcal })
            .containsExactly(day to 400.0, day + 1 to 200.0)
            .inOrder()
    }

    @Test
    fun `entries load with relations per day`() = runTest {
        val productId = db.productDao().insert(product("Гречка"))
        val entries = db.mealEntryDao()
        entries.insert(MealEntryEntity(dayEpochDay = day, mealType = "BREAKFAST", productId = productId, grams = 80.0, timestampMs = 1))

        val rows = entries.observeWithProductsForDay(day).first()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().product.name).isEqualTo("Гречка")
        assertThat(rows.single().entry.grams).isEqualTo(80.0)
    }

    @Test
    fun `updating entry grams persists`() = runTest {
        val productId = db.productDao().insert(product("Хлеб"))
        val entries = db.mealEntryDao()
        val id = entries.insert(MealEntryEntity(dayEpochDay = day, mealType = "SNACK", productId = productId, grams = 30.0, timestampMs = 1))
        val loaded = entries.entryById(id)!!
        entries.update(loaded.copy(grams = 60.0, mealType = "DINNER"))

        val updated = entries.entryById(id)!!
        assertThat(updated.grams).isEqualTo(60.0)
        assertThat(updated.mealType).isEqualTo("DINNER")
    }

    @Test
    fun `deleting a custom product cascades to its entries`() = runTest {
        val dao = db.productDao()
        val productId = dao.insert(product("Свой смузи", isCustom = true))
        val entries = db.mealEntryDao()
        entries.insert(MealEntryEntity(dayEpochDay = day, mealType = "SNACK", productId = productId, grams = 250.0, timestampMs = 1))
        assertThat(entries.observeForDay(day).first()).hasSize(1)

        dao.delete(dao.getById(productId)!!)
        assertThat(entries.observeForDay(day).first()).isEmpty()
    }

    @Test
    fun `weight log keeps one row per day replacing old value`() = runTest {
        val dao = db.weightLogDao()
        dao.record(WeightEntryEntity(dayEpochDay = day, weightKg = 80.0, timestampMs = 1))
        dao.record(WeightEntryEntity(dayEpochDay = day, weightKg = 79.5, timestampMs = 2))

        val rows = dao.observeRange(day, day).first()
        assertThat(rows.map { it.weightKg }).containsExactly(79.5)
        assertThat(dao.observeLatest().first()?.weightKg).isEqualTo(79.5)
    }

    @Test
    fun `profile row is replaced on upsert`() = runTest {
        val dao = db.profileDao()
        assertThat(dao.observe().first()).isNull()

        dao.upsert(ProfileEntity(sex = "MALE", birthYear = 1990, heightCm = 180.0, weightKg = 80.0, activityLevel = "LIGHT", goal = "LOSE", manualKcalTarget = null, updatedAtMs = 1))
        dao.upsert(ProfileEntity(sex = "MALE", birthYear = 1990, heightCm = 180.0, weightKg = 78.0, activityLevel = "LIGHT", goal = "LOSE", manualKcalTarget = 2100.0, updatedAtMs = 2))

        val profile = dao.observe().first()!!
        assertThat(profile.weightKg).isEqualTo(78.0)
        assertThat(profile.manualKcalTarget).isEqualTo(2100.0)
    }
}
