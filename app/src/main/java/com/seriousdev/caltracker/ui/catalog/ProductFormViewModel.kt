package com.seriousdev.caltracker.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.ProductRepository
import com.seriousdev.caltracker.domain.Macros
import com.seriousdev.caltracker.domain.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProductFormState(
    val loading: Boolean = true,
    val name: String = "",
    val kcalText: String = "",
    val proteinText: String = "",
    val fatText: String = "",
    val carbsText: String = "",
    val barcodeText: String = "",
    val portions: List<PortionRow> = emptyList(),
    /** Seeded product: only its portions may be edited here. */
    val isSeed: Boolean = false,
    /** Fields came from label recognition; user confirms before saving. */
    val prefilled: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
)

/** Label-recognition or barcode-scan values used to seed a fresh product form. */
data class PrefilledProduct(
    val name: String,
    val kcal: String?,
    val protein: String?,
    val fat: String?,
    val carbs: String?,
    val barcode: String? = null,
)

/**
 * Create/edit form for user-defined products. Empty kcal field auto-computes
 * from macros (Atwater 4/9/4); an explicit kcal must be within 20 % of implied.
 */
class ProductFormViewModel(
    private val productId: Long?,
    private val productRepository: ProductRepository,
    prefill: PrefilledProduct? = null,
) : ViewModel() {

    private val form = MutableStateFlow(
        if (productId == null && prefill != null) {
            ProductFormState(
                name = prefill.name,
                kcalText = prefill.kcal.orEmpty(),
                proteinText = prefill.protein.orEmpty(),
                fatText = prefill.fat.orEmpty(),
                carbsText = prefill.carbs.orEmpty(),
                barcodeText = prefill.barcode.orEmpty(),
                prefilled = true,
            )
        } else {
            ProductFormState()
        },
    )
    val state: StateFlow<ProductFormState> = form

    init {
        viewModelScope.launch {
            productId?.let { id ->
                productRepository.getById(id)?.let { product ->
                    form.update {
                        it.copy(
                            loading = false,
                            name = product.name,
                            kcalText = num(product.per100g.kcal),
                            proteinText = num(product.per100g.protein),
                            fatText = num(product.per100g.fat),
                            carbsText = num(product.per100g.carbs),
                            barcodeText = product.barcode.orEmpty(),
                            portions = productRepository.getPortions(id).map { p ->
                                PortionRow(label = p.label, gramsText = num(p.grams))
                            },
                            isSeed = !product.isCustom,
                        )
                    }
                }
            }
            if (form.value.loading) form.update { it.copy(loading = false) }
        }
    }

    fun onFormChange(transform: (ProductFormState) -> ProductFormState) = form.update(transform)

    /** Applies values delivered by an in-form barcode pick or label recognition. */
    fun applyFormFill(fill: PrefilledProduct) {
        form.update {
            it.copy(
                name = fill.name.ifBlank { it.name },
                kcalText = fill.kcal ?: it.kcalText,
                proteinText = fill.protein ?: it.proteinText,
                fatText = fill.fat ?: it.fatText,
                carbsText = fill.carbs ?: it.carbsText,
                barcodeText = fill.barcode ?: it.barcodeText,
                prefilled = true,
                errors = emptyMap(),
            )
        }
    }

    /** Deletes the edited custom product (cascades to its diary entries). */
    fun delete(onDeleted: () -> Unit) {
        val id = productId ?: return
        viewModelScope.launch {
            productRepository.getById(id)?.let { productRepository.deleteCustom(it) }
            onDeleted()
        }
    }

    fun save(onSaved: (Long) -> Unit = {}) {
        val s = form.value
        val (portions, portionError) = parsePortionRows(s.portions)
        if (portions == null) {
            form.update { it.copy(errors = mapOf("portions" to portionError.orEmpty())) }
            return
        }

        // Seeded products: only portions are editable.
        if (s.isSeed) {
            val id = productId ?: return
            viewModelScope.launch {
                productRepository.replacePortions(id, portions)
                onSaved(id)
            }
            return
        }

        val errors = mutableMapOf<String, String>()

        fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

        val protein = parse(s.proteinText)
        val fat = parse(s.fatText)
        val carbs = parse(s.carbsText)
        if (protein == null || protein < 0 || protein > 100) errors["protein"] = "Белок 0–100 г"
        if (fat == null || fat < 0 || fat > 100) errors["fat"] = "Жиры 0–100 г"
        if (carbs == null || carbs < 0 || carbs > 100) errors["carbs"] = "Углеводы 0–100 г"

        val implied = protein?.let { 4.0 * it } ?: 0.0
        val impliedF = fat?.let { 9.0 * it } ?: 0.0
        val impliedC = carbs?.let { 4.0 * it } ?: 0.0
        val impliedKcal = implied + impliedF + impliedC

        val kcal = s.kcalText.trim().let { if (it.isEmpty()) impliedKcal else parse(it) }
        if (kcal == null) errors["kcal"] = "Введите число или оставьте пустым"
        else if (kcal < 1.0 || kcal > 900.0) errors["kcal"] = "Калории 1–900"
        else if (kcal > 0 && Math.abs(kcal - impliedKcal) > 0.20 * kcal) {
            errors["kcal"] = "КБЖУ не согласованы (±20 %)"
        }
        if (s.name.isBlank()) errors["name"] = "Введите название"

        if (errors.isNotEmpty()) {
            form.update { it.copy(errors = errors) }
            return
        }

        val macros = Macros(kcal!!, protein!!, fat!!, carbs!!)
        val barcode = s.barcodeText.trim().ifEmpty { null }
        if (barcode != null && !barcode.all { it.isDigit() }) {
            form.update { it.copy(errors = mapOf("barcode" to "Штрихкод — только цифры")) }
            return
        }
        viewModelScope.launch {
            val existing = productId
            try {
                val savedId = if (existing == null) {
                    productRepository.addCustom(s.name, "Прочее", macros, barcode)
                } else {
                    val priorCategory = productRepository.getById(existing)?.category ?: "Прочее"
                    productRepository.updateCustom(
                        Product(id = existing, name = s.name.trim(), category = priorCategory, per100g = macros, isCustom = true, barcode = barcode),
                    )
                    existing
                }
                productRepository.replacePortions(savedId, portions)
                onSaved(savedId)
            } catch (e: Exception) {
                form.update { it.copy(errors = mapOf("name" to "Продукт с таким названием уже есть")) }
            }
        }
    }

    private fun num(value: Double): String =
        if (value == Math.round(value).toDouble()) Math.round(value).toString() else value.toString()
}
