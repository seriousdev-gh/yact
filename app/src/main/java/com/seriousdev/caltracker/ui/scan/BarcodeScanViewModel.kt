package com.seriousdev.caltracker.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.OffProduct
import com.seriousdev.caltracker.data.OffResult
import com.seriousdev.caltracker.data.OpenFoodFactsClient
import com.seriousdev.caltracker.data.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the caller needs to open a prefilled new-product form for a scanned code. */
data class ScanPrefill(
    val barcode: String,
    val name: String? = null,
    val kcal: String? = null,
    val protein: String? = null,
    val fat: String? = null,
    val carbs: String? = null,
)

data class ScanUiState(
    /** Barcode being looked up; non-null while the spinner is up. */
    val pendingBarcode: String? = null,
    /** Offline message shown inside the scan UI (permission/error). */
    val statusText: String? = null,
    /** Catalog id of a locally matched product; the caller opens the meal dialog. */
    val matchedProductId: Long? = null,
    /** Product unknown locally; the caller opens a prefilled product form. */
    val requestProductForm: ScanPrefill? = null,
)

/**
 * Resolves scanned barcodes: local catalog first (meal logging is the point of
 * scanning), then Open Food Facts (prefill a new product). Camera permission
 * and the camera itself live in the composable; this class only does IO.
 */
class BarcodeScanViewModel(
    private val off: OpenFoodFactsClient,
    private val products: ProductRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = ui

    /** ML Kit called this back (or the user typed it). */
    fun onBarcode(barcode: String) {
        if (ui.value.pendingBarcode != null || ui.value.matchedProductId != null ||
            ui.value.requestProductForm != null
        ) return
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return
        ui.update { it.copy(pendingBarcode = trimmed, statusText = null) }
        viewModelScope.launch {
            val local = products.getByBarcode(trimmed)
            if (local != null) {
                ui.update { it.copy(pendingBarcode = null, matchedProductId = local.id) }
                return@launch
            }
            when (val result = off.fetch(trimmed)) {
                is OffResult.Found -> ui.update {
                    it.copy(pendingBarcode = null, requestProductForm = result.product.toPrefill())
                }

                OffResult.NotFound -> ui.update {
                    it.copy(pendingBarcode = null, requestProductForm = ScanPrefill(barcode = trimmed))
                }

                is OffResult.NoNutrition -> ui.update {
                    it.copy(
                        pendingBarcode = null,
                        requestProductForm = ScanPrefill(barcode = result.barcode, name = result.name),
                    )
                }

                is OffResult.NetworkError -> ui.update {
                    it.copy(
                        pendingBarcode = null,
                        statusText = "Нет связи с Open Food Facts. В каталоге кода нет — добавьте продукт вручную.",
                        requestProductForm = ScanPrefill(barcode = trimmed),
                    )
                }

                is OffResult.Invalid -> ui.update {
                    it.copy(pendingBarcode = null, statusText = "Сервер вернул неожиданный ответ. Повторите скан.")
                }
            }
        }
    }

    fun dismissStatus() {
        ui.update { it.copy(statusText = null, requestProductForm = null) }
    }

    /** The caller has consumed [ScanUiState.requestProductForm]. */
    fun productFormOpened() {
        ui.update { it.copy(requestProductForm = null) }
    }

    private fun OffProduct.toPrefill() = ScanPrefill(
        barcode = barcode,
        name = name,
        kcal = fmt(per100g.kcal),
        protein = fmt(per100g.protein),
        fat = fmt(per100g.fat),
        carbs = fmt(per100g.carbs),
    )

    private fun fmt(value: Double): String =
        if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)
}
