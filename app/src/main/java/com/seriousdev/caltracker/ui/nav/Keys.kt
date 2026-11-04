package com.seriousdev.caltracker.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

private val navIdCounter = AtomicLong(System.currentTimeMillis() shl 16)

/** Unique id for one navigation push; survives process-death restore via key serialization. */
private fun newNavId(): Long = navIdCounter.incrementAndGet()

/** One prefill ingredient row: catalog product + grams. */
@Serializable
data class IngredientPrefill(val productId: Long, val grams: Double)

/** Route keys for the Nav3 back stack (serializable for saved-state persistence). */
@Serializable
sealed interface AppKey : NavKey {

    @Serializable
    data object Today : AppKey
    @Serializable
    data object Catalog : AppKey
    @Serializable
    data object Stats : AppKey
    @Serializable
    data object Profile : AppKey

    /**
     * Add-food flow for a specific day; pushed on top of a root tab.
     * [navId] makes every push a distinct entry (fresh ViewModel/saved state per open).
     * [preselectedProductId] opens that product's portion dialog immediately.
     */
    @Serializable
    data class AddFood(
        val dayEpochDay: Long,
        val preselectedProductId: Long? = null,
        val navId: Long = newNavId(),
    ) : AppKey

    /**
     * Create (productId == null) or edit a custom product.
     * The prefill* fields seed a new-product form (e.g. from label recognition).
     * [logMealDay] makes a successful create continue into the add-food flow
     * for that day with the new product preselected; [ingredientRecipeNavId]
     * instead continues into the ingredient pick for the recipe form with
     * that nav id.
     */
    @Serializable
    data class ProductForm(
        val productId: Long? = null,
        val prefillName: String? = null,
        val prefillKcal: String? = null,
        val prefillProtein: String? = null,
        val prefillFat: String? = null,
        val prefillCarbs: String? = null,
        val prefillBarcode: String? = null,
        val logMealDay: Long? = null,
        val ingredientRecipeNavId: Long? = null,
        val navId: Long = newNavId(),
    ) : AppKey

    /**
     * Create (recipeId == null) or edit a recipe. [prefill] seeds ingredient
     * rows for a new recipe (e.g. from the diary multi-select "create recipe").
     */
    @Serializable
    data class RecipeForm(
        val recipeId: Long? = null,
        val prefill: List<IngredientPrefill> = emptyList(),
        val navId: Long = newNavId(),
    ) : AppKey

    /**
     * Product search started from a recipe form; behaves like the add-food
     * flow (portion dialog without meal type). The picked (product, grams)
     * is delivered to the recipe form with nav id [recipeFormNavId].
     */
    @Serializable
    data class IngredientPick(
        val recipeFormNavId: Long,
        val preselectedProductId: Long? = null,
        val navId: Long = newNavId(),
    ) : AppKey

    /**
     * Barcode scanner -> meal dialog (known product) or prefilled product form.
     * With [recipeFormNavId] it runs in recipe-ingredient mode instead (results
     * continue into [IngredientPick] for that form); then [dayEpochDay] is unused.
     */
    @Serializable
    data class BarcodeScan(
        val dayEpochDay: Long? = null,
        val recipeFormNavId: Long? = null,
        val navId: Long = newNavId(),
    ) : AppKey

    /** Barcode scanner started from a product form; result fills that form ([formNavId]). */
    @Serializable
    data class BarcodePick(val formNavId: Long, val navId: Long = newNavId()) : AppKey

    /**
     * Label photo -> LLM extraction. [formNavId] is the product form that
     * started the recognition; the result fills that form in place.
     */
    @Serializable
    data class LabelScan(val formNavId: Long, val navId: Long = newNavId()) : AppKey

    companion object {
        /** The four bottom-bar root tabs. */
        val roots: List<AppKey> = listOf(Today, Catalog, Stats, Profile)
    }
}
