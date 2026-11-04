package com.seriousdev.caltracker.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.seriousdev.caltracker.domain.MealType
import com.seriousdev.caltracker.domain.Product
import com.seriousdev.caltracker.domain.ProductPortion
import com.seriousdev.caltracker.ui.format.gramsText
import com.seriousdev.caltracker.ui.format.kcalText
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Portion/grams + meal-type dialog shared by the add-food flow and diary
 * entry editing. When the product has portions, adding preselects the first
 * one. With [initialGrams] (edit) it pre-fills the amount and, when the
 * grams are a whole multiple of one of the product's portions, highlights
 * that portion. [onDelete] (edit mode) adds a "Удалить" action; [onEditProduct]
 * navigates to the product/recipe form (e.g. to define a new portion).
 * [showMealTypePicker] = false (recipe ingredients) hides the meal-type chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortionDialog(
    product: Product,
    portions: List<ProductPortion>,
    onDismiss: () -> Unit,
    onConfirm: (grams: Double, mealType: MealType) -> Unit,
    initialGrams: Double? = null,
    initialMealType: MealType = MealType.BREAKFAST,
    confirmLabel: String = "Добавить",
    onDelete: (() -> Unit)? = null,
    onEditProduct: (() -> Unit)? = null,
    showMealTypePicker: Boolean = true,
) {
    var gramsText by rememberSaveable(product.id, initialGrams) {
        mutableStateOf(initialGrams?.let(::trimNumber) ?: "100")
    }
    var qtyText by rememberSaveable(product.id, initialGrams) { mutableStateOf("1") }
    var selectedPortionIndex by rememberSaveable(product.id, initialGrams) { mutableStateOf(-1) }
    var mealType by rememberSaveable(product.id, initialGrams) { mutableStateOf(initialMealType) }

    // Portions may load after the dialog opens; once they arrive pick a
    // default: add mode preselects the first portion, edit mode snaps the
    // initial grams to a matching portion (e.g. 30 g -> "2 ломтика · 15 г").
    var needsPreselect by rememberSaveable(product.id, initialGrams) { mutableStateOf(true) }
    LaunchedEffect(portions, needsPreselect) {
        if (!needsPreselect || portions.isEmpty()) return@LaunchedEffect
        needsPreselect = false
        if (initialGrams == null) {
            selectedPortionIndex = 0
            return@LaunchedEffect
        }
        val grams = initialGrams
        val index = portions.indexOfFirst { portion ->
            val qty = grams / portion.grams
            qty >= 1.0 && abs(qty - qty.roundToInt()) < 1e-6
        }
        if (index >= 0) {
            selectedPortionIndex = index
            qtyText = trimNumber(grams / portions[index].grams)
        }
    }

    val portion = portions.getOrNull(selectedPortionIndex)
    val entered = (portion?.let { qtyText } ?: gramsText).replace(',', '.').toDoubleOrNull()
    val grams = if (portion != null) entered?.let { it * portion.grams } else entered
    val macros = product.forGrams(grams ?: 0.0)
    val valid = grams != null && grams in 1.0..5000.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(product.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.imePadding()) {
                if (portions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = portion == null,
                            onClick = { selectedPortionIndex = -1 },
                            label = { Text("граммы") },
                        )
                        portions.forEachIndexed { index, p ->
                            FilterChip(
                                selected = index == selectedPortionIndex,
                                onClick = { selectedPortionIndex = index },
                                label = { Text("${p.label} · ${gramsText(p.grams)}") },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = if (portion != null) qtyText else gramsText,
                    onValueChange = {
                        if (portion != null) qtyText = it else gramsText = it
                    },
                    label = { Text(if (portion != null) "Количество, ${portion.label}" else "Граммы") },
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                if (showMealTypePicker) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MealType.entries.forEach { type ->
                            FilterChip(
                                selected = mealType == type,
                                onClick = { mealType = type },
                                label = { Text(type.ruLabel) },
                            )
                        }
                    }
                }
                Text(
                    "${kcalText(macros.kcal)} ккал · У ${kcalText(macros.carbs)} г · " +
                        "Б ${kcalText(macros.protein)} г · Ж ${kcalText(macros.fat)} г",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (onEditProduct != null) {
                    TextButton(onClick = onEditProduct) {
                        Text(
                            when {
                                product.isRecipe -> "Изменить рецепт"
                                product.isCustom -> "Изменить продукт"
                                else -> "Добавить порции"
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { grams?.let { onConfirm(it, mealType) } },
                enabled = valid,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** "30" for whole numbers, "1.5" otherwise — for pre-filling numeric fields. */
private fun trimNumber(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString()
