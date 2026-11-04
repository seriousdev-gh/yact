package com.seriousdev.caltracker.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** One editable portion row in a form (raw user text). */
data class PortionRow(
    val label: String = "",
    val gramsText: String = "",
)

/**
 * Editor for a product's named portions ("ломтик" = 15 g).
 * Rows are label + grams, add/remove controlled by [rows].
 */
@Composable
fun PortionEditor(
    rows: List<PortionRow>,
    error: String?,
    onRowsChange: (List<PortionRow>) -> Unit,
) {
    Text("Порции", style = MaterialTheme.typography.labelLarge)
    Text(
        "Например: «ломтик» — 15 г. Тогда в дневнике можно вносить «1.5 ломтика».",
        style = MaterialTheme.typography.bodySmall,
    )
    rows.forEachIndexed { index, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = row.label,
                onValueChange = { v ->
                    onRowsChange(rows.toMutableList().also { it[index] = row.copy(label = v) })
                },
                label = { Text("Название, напр. ломтик") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.gramsText,
                onValueChange = { v ->
                    onRowsChange(rows.toMutableList().also { it[index] = row.copy(gramsText = v) })
                },
                label = { Text("г") },
                isError = error != null && row.gramsText.isBlank(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(0.5f),
            )
            IconButton(onClick = { onRowsChange(rows.filterIndexed { i, _ -> i != index }) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Удалить порцию")
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { onRowsChange(rows + PortionRow()) }) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить порцию")
        }
    }
}

/**
 * Parses and validates portion rows. Blank label + blank grams is treated as
 * an unfinished row and ignored; duplicates (label, grams) collapse to one.
 */
fun parsePortionRows(rows: List<PortionRow>): Pair<List<Pair<String, Double>>?, String?> {
    val out = mutableListOf<Pair<String, Double>>()
    for ((index, row) in rows.withIndex()) {
        val label = row.label.trim()
        val gramsText = row.gramsText.trim()
        if (label.isBlank() && gramsText.isBlank()) continue
        if (label.isBlank()) return null to "Порция №${index + 1}: укажите название"
        if (label.length > 40) return null to "Порция №${index + 1}: название до 40 символов"
        val grams = gramsText.replace(',', '.').toDoubleOrNull()
        if (grams == null || grams <= 0.0 || grams > 5000.0) {
            return null to "Порция №${index + 1}: граммы 0–5000"
        }
        val pair = label to grams
        if (pair !in out) out += pair
    }
    return out to null
}
