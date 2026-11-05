package com.seriousdev.caltracker.data

import androidx.room.withTransaction
import com.seriousdev.caltracker.domain.ActivityLevel
import com.seriousdev.caltracker.domain.Goal
import com.seriousdev.caltracker.domain.MacroTargets
import com.seriousdev.caltracker.domain.Macros
import com.seriousdev.caltracker.domain.MealEntry
import com.seriousdev.caltracker.domain.MealType
import com.seriousdev.caltracker.domain.Product
import com.seriousdev.caltracker.domain.ProductKind
import com.seriousdev.caltracker.domain.ProductPortion
import com.seriousdev.caltracker.domain.Profile
import com.seriousdev.caltracker.domain.Sex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

fun ProductEntity.toDomain() = Product(
    id = id,
    name = name,
    category = category,
    per100g = Macros(kcal = kcal, protein = protein, fat = fat, carbs = carbs),
    isCustom = isCustom,
    isFavorite = isFavorite,
    kind = ProductKind.valueOf(kind),
    barcode = barcode,
)

fun MealEntryEntity.toDomain() = MealEntry(
    id = id,
    dayEpochDay = dayEpochDay,
    mealType = MealType.valueOf(mealType),
    productId = productId,
    grams = grams,
)

fun ProfileEntity.toDomain() = Profile(
    sex = Sex.valueOf(sex),
    ageYears = java.time.Period.between(
        java.time.LocalDate.of(birthYear, 1, 1),
        java.time.LocalDate.now(),
    ).years,
    heightCm = heightCm,
    weightKg = weightKg,
    activityLevel = ActivityLevel.valueOf(activityLevel),
    goal = Goal.valueOf(goal),
    manualKcalTarget = manualKcalTarget,
    macroTargets = if (targetProteinG != null && targetFatG != null && targetCarbsG != null) {
        MacroTargets(proteinG = targetProteinG, fatG = targetFatG, carbsG = targetCarbsG)
    } else {
        null
    },
)

/** Products: catalog search, categories, custom product CRUD, favorites, portions. */
open class ProductRepository(
    private val dao: ProductDao,
    private val portionDao: ProductPortionDao,
    private val recipeDao: RecipeIngredientDao,
) {

    open fun search(query: String): Flow<List<Product>> =
        dao.search(query.replace("%", "\\%").replace("_", "\\_")).map { list -> list.map { it.toDomain() } }

    open fun observeAll(): Flow<List<Product>> = dao.observeAll().map { it.map(ProductEntity::toDomain) }

    open fun observeCustom(): Flow<List<Product>> = dao.observeCustom().map { it.map(ProductEntity::toDomain) }

    /** Custom products and recipes. */
    open fun observeUserContent(): Flow<List<Product>> =
        dao.observeUserContent().map { it.map(ProductEntity::toDomain) }

    open fun observeFavorites(): Flow<List<Product>> =
        dao.observeFavorites().map { it.map(ProductEntity::toDomain) }

    /** Distinct products eaten since [sinceDayEpochDay], most recent use first. */
    open fun observeRecentlyUsed(sinceDayEpochDay: Long, max: Int = 8): Flow<List<Product>> =
        dao.observeRecentlyUsed(sinceDayEpochDay, max).map { it.map(ProductEntity::toDomain) }

    open suspend fun setFavorite(productId: Long, favorite: Boolean) = dao.setFavorite(productId, favorite)

    open suspend fun getById(id: Long): Product? = dao.getById(id)?.toDomain()

    /** Named portions of a product (also recipes), in insertion order. */
    open fun observePortions(productId: Long): Flow<List<ProductPortion>> =
        portionDao.observeForProduct(productId).map { list ->
            list.map { ProductPortion(id = it.id, label = it.label, grams = it.grams) }
        }

    open suspend fun getPortions(productId: Long): List<ProductPortion> =
        portionDao.getForProduct(productId).map { ProductPortion(id = it.id, label = it.label, grams = it.grams) }

    open suspend fun replacePortions(productId: Long, portions: List<Pair<String, Double>>) =
        portionDao.replaceAll(productId, portions)

    open suspend fun addCustom(name: String, category: String, per100g: Macros, barcode: String? = null): Long = dao.insert(
        ProductEntity(
            name = name.trim(),
            category = category,
            kcal = per100g.kcal,
            protein = per100g.protein,
            fat = per100g.fat,
            carbs = per100g.carbs,
            isCustom = true,
            barcode = barcode,
        ),
    )

    open suspend fun getByBarcode(barcode: String): Product? = dao.findByBarcode(barcode)?.toDomain()

    /**
     * Imports a product found by barcode scan. If this barcode is already in the
     * catalog returns its id unchanged; a name clash makes [name] suffixed with the
     * brand-less fallback pattern instead of failing.
     */
    open suspend fun importByBarcode(name: String, category: String, per100g: Macros, barcode: String): Long {
        dao.findByBarcode(barcode)?.let { return it.id }
        var candidate = name.trim()
        if (dao.findByName(candidate) != null) candidate = "$candidate ($barcode)"
        return dao.insert(
            ProductEntity(
                name = candidate,
                category = category,
                kcal = per100g.kcal,
                protein = per100g.protein,
                fat = per100g.fat,
                carbs = per100g.carbs,
                isCustom = true,
                barcode = barcode,
            ),
        )
    }

    /** Updates editable fields of a user product, preserving favorite and kind. */
    open suspend fun updateCustom(product: Product) {
        val existing = dao.getById(product.id) ?: return
        dao.update(
            existing.copy(
                name = product.name.trim(),
                category = product.category,
                kcal = product.per100g.kcal,
                protein = product.per100g.protein,
                fat = product.per100g.fat,
                carbs = product.per100g.carbs,
                barcode = product.barcode,
            ),
        )
    }

    /**
     * Deletes a user product or recipe. Removes it from any recipes that use it
     * and recomputes them; diary entries cascade.
     */
    open suspend fun deleteCustom(product: Product) {
        require(product.isCustom || product.isRecipe) { "Only user content can be deleted" }
        val affectedRecipes = recipeDao.recipesUsing(product.id)
        recipeDao.removeIngredientFromAll(product.id)
        dao.getById(product.id)?.let { dao.delete(it) }
        affectedRecipes.filter { it != product.id }.forEach { recomputeRecipe(it) }
    }

    /** Recomputes a recipe product's per-100 g values from its ingredients. */
    open suspend fun recomputeRecipe(recipeId: Long) {
        val recipe = dao.getById(recipeId) ?: return
        val ingredients = recipeDao.getWithProducts(recipeId)
        val totals = ingredients.fold(Macros.ZERO) { acc, row ->
            acc + row.product.toDomain().forGrams(row.ingredient.grams)
        }
        val totalGrams = ingredients.sumOf { it.ingredient.grams }
        val per100 = if (totalGrams > 0) totals * (100.0 / totalGrams) else Macros.ZERO
        dao.update(
            recipe.copy(
                kcal = per100.kcal,
                protein = per100.protein,
                fat = per100.fat,
                carbs = per100.carbs,
            ),
        )
    }
}

/** Recipes: user-composed products whose nutrition is computed from ingredients. */
open class RecipeRepository(
    private val db: AppDatabase,
    private val productDao: ProductDao,
    private val recipeDao: RecipeIngredientDao,
    private val portionDao: ProductPortionDao,
) {

    companion object {
        const val CATEGORY = "Рецепты"
    }

    /** Ingredients with products, in insertion order. */
    open fun observeIngredients(recipeId: Long): Flow<List<Pair<Product, Double>>> =
        recipeDao.observeWithProducts(recipeId).map { rows ->
            rows.map { it.product.toDomain() to it.ingredient.grams }
        }

    open suspend fun getIngredients(recipeId: Long): List<Pair<Product, Double>> =
        recipeDao.getWithProducts(recipeId).map { it.product.toDomain() to it.ingredient.grams }

    /** Creates or updates ([recipeId] non-null) a recipe and recomputes its nutrition. */
    open suspend fun saveRecipe(
        recipeId: Long?,
        name: String,
        ingredients: List<Pair<Long, Double>>,
        portions: List<Pair<String, Double>>,
    ): Long = db.withTransaction {
        val trimmed = name.trim()
        val id = if (recipeId == null) {
            productDao.insert(
                ProductEntity(
                    name = trimmed,
                    category = CATEGORY,
                    kcal = 0.0,
                    protein = 0.0,
                    fat = 0.0,
                    carbs = 0.0,
                    kind = ProductKind.RECIPE.name,
                ),
            )
        } else {
            productDao.getById(recipeId)?.let { productDao.update(it.copy(name = trimmed)) }
            recipeId
        }
        recipeDao.clear(id)
        ingredients.forEach { (productId, grams) ->
            recipeDao.insertAll(listOf(RecipeIngredientEntity(recipeId = id, ingredientProductId = productId, grams = grams)))
        }
        portionDao.replaceAll(id, portions)
        val recipe = productDao.getById(id)!!
        applyIngredients(recipe, ingredients)
        id
    }

    private suspend fun applyIngredients(
        recipe: ProductEntity,
        ingredients: List<Pair<Long, Double>>,
    ) {
        var totals = Macros.ZERO
        var totalGrams = 0.0
        ingredients.forEach { (productId, grams) ->
            val product = productDao.getById(productId) ?: return@forEach
            totals += Macros(product.kcal, product.protein, product.fat, product.carbs) * (grams / 100.0)
            totalGrams += grams
        }
        val per100 = if (totalGrams > 0) totals * (100.0 / totalGrams) else Macros.ZERO
        productDao.update(
            recipe.copy(
                kcal = per100.kcal,
                protein = per100.protein,
                fat = per100.fat,
                carbs = per100.carbs,
            ),
        )
    }
}

/** Diary: meal entries per day and aggregates. */
open class DiaryRepository(private val dao: MealEntryDao) {

    open fun observeForDay(dayEpochDay: Long): Flow<List<MealEntry>> =
        dao.observeForDay(dayEpochDay).map { list -> list.map { it.toDomain() } }

    /** Entries for a day paired with their products, in insertion order. */
    open fun observeWithProducts(dayEpochDay: Long): Flow<List<Pair<MealEntry, Product>>> =
        dao.observeWithProductsForDay(dayEpochDay).map { rows ->
            rows.map { it.entry.toDomain() to it.product.toDomain() }
        }

    open suspend fun add(dayEpochDay: Long, mealType: MealType, productId: Long, grams: Double): Long =
        dao.insert(
            MealEntryEntity(
                dayEpochDay = dayEpochDay,
                mealType = mealType.name,
                productId = productId,
                grams = grams,
                timestampMs = System.currentTimeMillis(),
            ),
        )

    open suspend fun updateGrams(entryId: Long, grams: Double) {
        val entry = dao.entryById(entryId) ?: return
        dao.update(entry.copy(grams = grams))
    }

    open suspend fun updateMealType(entryId: Long, mealType: MealType) {
        val entry = dao.entryById(entryId) ?: return
        dao.update(entry.copy(mealType = mealType.name))
    }

    open suspend fun delete(entryId: Long) {
        dao.entryById(entryId)?.let { dao.delete(it) }
    }

    open fun observeDayKcalRange(fromDay: Long, toDay: Long): Flow<List<DayKcal>> =
        dao.observeDayKcalRange(fromDay, toDay)
}

/** Profile singleton row. */
open class ProfileRepository(private val dao: ProfileDao) {

    open fun observe(): Flow<Profile?> = dao.observe().map { it?.toDomain() }

    /** One-shot read of the current profile. */
    open suspend fun current(): Profile? = dao.observe().first()?.toDomain()

    open suspend fun save(profile: Profile) = dao.upsert(
        ProfileEntity(
            sex = profile.sex.name,
            birthYear = java.time.YearMonth.now().year - profile.ageYears,
            heightCm = profile.heightCm,
            weightKg = profile.weightKg,
            activityLevel = profile.activityLevel.name,
            goal = profile.goal.name,
            // Macro targets and a manual kcal target are mutually exclusive; macros win.
            manualKcalTarget = if (profile.macroTargets != null) null else profile.manualKcalTarget,
            targetProteinG = profile.macroTargets?.proteinG,
            targetFatG = profile.macroTargets?.fatG,
            targetCarbsG = profile.macroTargets?.carbsG,
            updatedAtMs = System.currentTimeMillis(),
        ),
    )
}

/** Weight observations. */
open class WeightRepository(private val dao: WeightLogDao) {

    open fun observeLatest(): Flow<Double?> = dao.observeLatest().map { it?.weightKg }

    open fun observeRange(fromDay: Long, toDay: Long): Flow<List<Pair<Long, Double>>> =
        dao.observeRange(fromDay, toDay).map { list -> list.map { it.dayEpochDay to it.weightKg } }

    open suspend fun record(dayEpochDay: Long, weightKg: Double) = dao.record(
        WeightEntryEntity(dayEpochDay = dayEpochDay, weightKg = weightKg, timestampMs = System.currentTimeMillis()),
    )
}
