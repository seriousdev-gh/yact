package com.seriousdev.caltracker.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.ProductRepository
import com.seriousdev.caltracker.data.RecipeRepository
import com.seriousdev.caltracker.domain.Macros
import com.seriousdev.caltracker.domain.Product
import com.seriousdev.caltracker.ui.nav.IngredientPrefill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One ingredient line: chosen product (or null while picking) + grams text. */
data class IngredientRow(
    val product: Product? = null,
    val gramsText: String = "",
)

data class RecipeFormState(
    val loading: Boolean = true,
    val name: String = "",
    val ingredients: List<IngredientRow> = listOf(IngredientRow()),
    val portions: List<PortionRow> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
)

/**
 * Create/edit a recipe: named ingredients with grams; nutrition is recomputed
 * by [RecipeRepository] on save. Empty new recipes get a suggested portion
 * "порция" equal to the total ingredient weight. [prefill] seeds rows for a
 * new recipe (diary multi-select -> create recipe).
 */
class RecipeFormViewModel(
    private val recipeId: Long?,
    private val productRepository: ProductRepository,
    private val recipeRepository: RecipeRepository,
    prefill: List<IngredientPrefill> = emptyList(),
) : ViewModel() {

    private val form = MutableStateFlow(RecipeFormState())
    val state: StateFlow<RecipeFormState> = form

    init {
        viewModelScope.launch {
            recipeId?.let { id ->
                val product = productRepository.getById(id)
                val ingredients = recipeRepository.getIngredients(id)
                val portions = productRepository.getPortions(id)
                form.update {
                    it.copy(
                        loading = false,
                        name = product?.name.orEmpty(),
                        ingredients = ingredients.map { (p, grams) ->
                            IngredientRow(product = p, gramsText = num(grams))
                        }.ifEmpty { listOf(IngredientRow()) },
                        portions = portions.map { p -> PortionRow(label = p.label, gramsText = num(p.grams)) },
                    )
                }
            }
            if (recipeId == null && prefill.isNotEmpty()) {
                val rows = prefill.mapNotNull { draft ->
                    productRepository.getById(draft.productId)
                        ?.let { IngredientRow(product = it, gramsText = num(draft.grams)) }
                }
                if (rows.isNotEmpty()) form.update { it.copy(ingredients = rows) }
                suggestPortionIfEmpty()
            }
            if (form.value.loading) form.update { it.copy(loading = false) }
        }
    }

    fun onFormChange(transform: (RecipeFormState) -> RecipeFormState) = form.update(transform)

    /** Appends an ingredient delivered by the ingredient pick flow (search/scan). */
    fun addPickedIngredient(productId: Long, grams: Double) {
        viewModelScope.launch {
            val product = productRepository.getById(productId) ?: return@launch
            form.update { s ->
                val rows = s.ingredients.toMutableList()
                val row = IngredientRow(product = product, gramsText = num(grams))
                val emptyIndex = rows.indexOfFirst { it.product == null }
                if (emptyIndex >= 0) rows[emptyIndex] = row else rows += row
                s.copy(ingredients = rows)
            }
            suggestPortionIfEmpty()
        }
    }

    /** Total grams and per-100 g nutrition of the current rows, for the live preview. */
    fun preview(): Pair<Double, Macros>? {
        val rows = form.value.ingredients.mapNotNull { row ->
            val grams = row.gramsText.trim().replace(',', '.').toDoubleOrNull()
            row.product?.let { it to (grams ?: 0.0) }
        }
        val totalGrams = rows.sumOf { it.second }
        if (rows.isEmpty() || totalGrams <= 0.0) return null
        val totals = rows.fold(Macros.ZERO) { acc, (product, grams) -> acc + product.forGrams(grams) }
        return totalGrams to totals * (100.0 / totalGrams)
    }

    /** Suggests a "порция" row once, when the recipe first gets a full ingredient. */
    fun suggestPortionIfEmpty() {
        val s = form.value
        if (recipeId != null || s.portions.isNotEmpty()) return
        val total = s.ingredients.sumOf {
            it.gramsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        }
        if (total > 0.0) {
            form.update { it.copy(portions = listOf(PortionRow(label = "порция", gramsText = num(total)))) }
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        val s = form.value
        val errors = mutableMapOf<String, String>()
        if (s.name.isBlank()) errors["name"] = "Введите название"

        val filled = s.ingredients.filter { it.product != null }
        if (filled.isEmpty()) errors["ingredients"] = "Добавьте хотя бы один ингредиент"
        val ingredientPairs = filled.mapNotNull { row ->
            val grams = row.gramsText.trim().replace(',', '.').toDoubleOrNull()
            if (grams == null || grams <= 0.0 || grams > 5000.0) {
                errors["ingredients"] = "Граммы каждого ингредиента: 0–5000"
                null
            } else {
                row.product!!.id to grams
            }
        }

        val (portions, portionError) = parsePortionRows(s.portions)
        if (portions == null) errors["portions"] = portionError.orEmpty()

        if (errors.isNotEmpty()) {
            form.update { it.copy(errors = errors) }
            return
        }
        if (ingredientPairs.size != filled.size) return

        viewModelScope.launch {
            try {
                val distinctPairs = ingredientPairs
                    .groupBy { it.first }
                    .map { (id, pairs) -> id to pairs.sumOf { it.second } }
                recipeRepository.saveRecipe(recipeId, s.name, distinctPairs, portions!!)
                onSaved()
            } catch (e: Exception) {
                form.update { it.copy(errors = mapOf("name" to "С таким названием уже есть продукт или рецепт")) }
            }
        }
    }

    /** Deletes this recipe; cascades ingredient rows. */
    fun delete(onDeleted: () -> Unit) {
        val id = recipeId ?: return
        viewModelScope.launch {
            productRepository.getById(id)
                ?.takeIf { it.isRecipe }
                ?.let { productRepository.deleteCustom(it) }
            onDeleted()
        }
    }

    private fun num(value: Double): String =
        if (value == Math.round(value).toDouble()) Math.round(value).toString() else value.toString()
}
