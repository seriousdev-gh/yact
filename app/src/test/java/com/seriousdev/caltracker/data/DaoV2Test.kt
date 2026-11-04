package com.seriousdev.caltracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.seriousdev.caltracker.domain.Macros
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
class DaoV2Test {

    private lateinit var db: AppDatabase

    private val day = 20_000L

    private fun product(
        name: String,
        kcal: Double = 100.0,
        protein: Double = 5.0,
        fat: Double = 2.0,
        carbs: Double = 15.0,
        isCustom: Boolean = false,
        kind: String = "PRODUCT",
    ) = ProductEntity(
        name = name, category = "Прочее", kcal = kcal, protein = protein, fat = fat,
        carbs = carbs, isCustom = isCustom, kind = kind,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    // --- portions ---

    @Test
    fun `portions replace in order and cascade with product`() = runTest {
        val breadId = db.productDao().insert(product("Хлеб"))
        val repo = ProductRepository(db.productDao(), db.portionDao(), db.recipeDao())
        repo.replacePortions(breadId, listOf("ломтик" to 15.0, "батон" to 300.0))
        assertThat(repo.getPortions(breadId).map { it.label to it.grams })
            .containsExactly("ломтик" to 15.0, "батон" to 300.0).inOrder()

        repo.replacePortions(breadId, listOf("ломтик" to 20.0))
        assertThat(repo.getPortions(breadId).single().grams).isEqualTo(20.0)

        val product = repo.getById(breadId)!!
        repo.deleteCustom(product.copy(isCustom = true))
        assertThat(db.portionDao().getForProduct(breadId)).isEmpty()
    }

    // --- favorites and recents ---

    @Test
    fun `favorite toggle persists and favorites query returns only favorites`() = runTest {
        val dao = db.productDao()
        val a = dao.insert(product("Кефир"))
        dao.insert(product("Творог"))
        dao.setFavorite(a, true)

        assertThat(dao.observeFavorites().first().map { it.id }).containsExactly(a)
    }

    @Test
    fun `recently used orders by last entry and respects day window`() = runTest {
        val products = db.productDao()
        val rice = products.insert(product("Рис"))
        val fish = products.insert(product("Рыба"))
        val entries = db.mealEntryDao()
        entries.insert(MealEntryEntity(dayEpochDay = day - 40, mealType = "LUNCH", productId = rice, grams = 100.0, timestampMs = 1))
        entries.insert(MealEntryEntity(dayEpochDay = day, mealType = "DINNER", productId = fish, grams = 100.0, timestampMs = 2))
        entries.insert(MealEntryEntity(dayEpochDay = day, mealType = "DINNER", productId = rice, grams = 100.0, timestampMs = 3))

        val recent = db.productDao().observeRecentlyUsed(sinceDay = day - 30, max = 8).first()
        assertThat(recent.map { it.id }).containsExactly(rice, fish).inOrder()
    }

    // --- profile macro targets ---

    @Test
    fun `profile macro targets round-trip and macros clear manual kcal`() = runTest {
        val repo = ProfileRepository(db.profileDao())
        val profile = com.seriousdev.caltracker.domain.Profile(
            sex = com.seriousdev.caltracker.domain.Sex.MALE,
            ageYears = 30,
            heightCm = 180.0,
            weightKg = 80.0,
            activityLevel = com.seriousdev.caltracker.domain.ActivityLevel.LIGHT,
            goal = com.seriousdev.caltracker.domain.Goal.MAINTAIN,
            manualKcalTarget = 2500.0,
            macroTargets = com.seriousdev.caltracker.domain.MacroTargets(120.0, 70.0, 250.0),
        )
        repo.save(profile)

        val loaded = db.profileDao().observe().first()!!
        assertThat(loaded.targetProteinG).isEqualTo(120.0)
        assertThat(loaded.targetFatG).isEqualTo(70.0)
        assertThat(loaded.targetCarbsG).isEqualTo(250.0)
        assertThat(loaded.manualKcalTarget).isNull()
    }

    // --- recipes ---

    @Test
    fun `recipe nutrition is computed per 100 g from ingredients`() = runTest {
        val products = db.productDao()
        val bread = products.insert(product("Хлеб", kcal = 250.0, protein = 12.0, fat = 3.0, carbs = 50.0))
        val ham = products.insert(product("Ветчина", kcal = 100.0, protein = 20.0, fat = 2.0, carbs = 0.0))
        val cheese = products.insert(product("Сыр", kcal = 350.0, protein = 25.0, fat = 28.0, carbs = 0.0))

        val recipes = RecipeRepository(
            db, db.productDao(), db.recipeDao(), db.portionDao(),
        )
        val recipeId = recipes.saveRecipe(
            recipeId = null,
            name = "Бутерброд",
            ingredients = listOf(bread to 30.0, ham to 40.0, cheese to 20.0),
            portions = listOf("1 бутерброд" to 90.0),
        )

        val recipe = products.getById(recipeId)!!
        assertThat(recipe.kind).isEqualTo("RECIPE")
        val expectedKcal = (250 * 0.30 + 100 * 0.40 + 350 * 0.20) * 100 / 90.0
        assertThat(recipe.kcal).isWithin(0.01).of(expectedKcal)
        assertThat(recipes.getIngredients(recipeId)).hasSize(3)
    }

    @Test
    fun `editing recipe ingredients recomputes nutrition`() = runTest {
        val products = db.productDao()
        val bread = products.insert(product("Хлеб2", kcal = 250.0))
        val cheese = products.insert(product("Сыр2", kcal = 350.0))
        val recipes = RecipeRepository(db, db.productDao(), db.recipeDao(), db.portionDao())
        val recipeId = recipes.saveRecipe(null, "Сэндвич", listOf(bread to 30.0, cheese to 20.0), emptyList())
        val before = products.getById(recipeId)!!.kcal

        recipes.saveRecipe(recipeId, "Сэндвич", listOf(bread to 30.0, cheese to 40.0), emptyList())
        val after = products.getById(recipeId)!!.kcal
        assertThat(after).isGreaterThan(before)
        assertThat(db.recipeDao().getWithProducts(recipeId)).hasSize(2)
    }

    @Test
    fun `deleting ingredient product removes it from recipes and recomputes`() = runTest {
        val products = db.productDao()
        val bread = products.insert(product("Батон", kcal = 250.0))
        val cheese = products.insert(product("Гауда", kcal = 350.0, isCustom = true))
        val recipes = RecipeRepository(db, db.productDao(), db.recipeDao(), db.portionDao())
        val recipeId = recipes.saveRecipe(null, "Тост", listOf(bread to 30.0, cheese to 20.0), emptyList())

        val productRepo = ProductRepository(db.productDao(), db.portionDao(), db.recipeDao())
        productRepo.deleteCustom(productRepo.getById(bread)!!.copy(isCustom = true))

        val remaining = db.recipeDao().getWithProducts(recipeId)
        assertThat(remaining.map { it.product.name }).containsExactly("Гауда")
        val recipe = products.getById(recipeId)!!
        assertThat(recipe.kcal).isWithin(0.01).of(350.0)
    }

    @Test
    fun `deleting a recipe cascades its ingredient rows`() = runTest {
        val products = db.productDao()
        val bread = products.insert(product("Мука", kcal = 340.0))
        val recipes = RecipeRepository(db, db.productDao(), db.recipeDao(), db.portionDao())
        val recipeId = recipes.saveRecipe(null, "Лепёшка", listOf(bread to 50.0), emptyList())

        val productRepo = ProductRepository(db.productDao(), db.portionDao(), db.recipeDao())
        val recipe = productRepo.getById(recipeId)!!
        assertThat(recipe.isRecipe).isTrue()
        productRepo.deleteCustom(recipe)

        assertThat(db.recipeDao().getWithProducts(recipeId)).isEmpty()
        assertThat(products.getById(bread)).isNotNull()
    }

    // --- barcode (v3) ---

    @Test
    fun `import by barcode stores code and finds it back`() = runTest {
        val repo = ProductRepository(db.productDao(), db.portionDao(), db.recipeDao())
        val id = repo.importByBarcode(
            name = "Нутелла",
            category = "chocolate spreads",
            per100g = Macros(kcal = 539.0, protein = 6.8, fat = 30.9, carbs = 57.5),
            barcode = "3017620422003",
        )
        val stored = repo.getByBarcode("3017620422003")
        assertThat(stored?.id).isEqualTo(id)
        assertThat(stored?.barcode).isEqualTo("3017620422003")
        assertThat(stored?.isCustom).isTrue()
    }

    @Test
    fun `importing a known barcode returns existing product without duplicate`() = runTest {
        val repo = ProductRepository(db.productDao(), db.portionDao(), db.recipeDao())
        val first = repo.importByBarcode("Нутелла", "spread", Macros(539.0, 6.8, 30.9, 57.5), "301")
        val second = repo.importByBarcode("Nutella", "spread", Macros(540.0, 7.0, 31.0, 58.0), "301")
        assertThat(second).isEqualTo(first)
        assertThat(db.productDao().count()).isEqualTo(1)
    }

    @Test
    fun `import with colliding name keeps both rows`() = runTest {
        val repo = ProductRepository(db.productDao(), db.portionDao(), db.recipeDao())
        val manual = repo.addCustom("Кефир", "Молочка", Macros(40.0, 3.0, 1.0, 4.0))
        val imported = repo.importByBarcode("Кефир", "dairy", Macros(38.0, 2.9, 1.2, 3.8), "460")
        assertThat(imported).isNotEqualTo(manual)
        assertThat(db.productDao().getById(imported)?.name).isEqualTo("Кефир (460)")
    }

    @Test
    fun `null barcodes do not collide`() = runTest {
        db.productDao().insert(product("Кефир А"))
        db.productDao().insert(product("Кефир Б"))
        assertThat(db.productDao().count()).isEqualTo(2)
        assertThat(db.productDao().findByBarcode("4600")).isNull()
    }
}
