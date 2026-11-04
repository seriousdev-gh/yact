package com.seriousdev.caltracker.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import kotlinx.coroutines.flow.filter

/** Create/edit a custom product. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFormScreen(
    productId: Long?,
    navId: Long,
    prefill: PrefilledProduct? = null,
    onScanBarcode: () -> Unit,
    onScanLabel: () -> Unit,
    onSaved: (Long) -> Unit,
    onDone: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ProductFormViewModel = viewModel(key = "product-form-${productId ?: "new"}-$navId", factory = viewModelFactory {
        initializer { ProductFormViewModel(productId, container.products, prefill) }
    })
    val state by vm.state.collectAsState()

    LaunchedEffect(navId) {
        container.formFills.take(navId)?.let(vm::applyFormFill)
        container.formFills.signals()
            .filter { it == navId }
            .collect { container.formFills.take(navId)?.let(vm::applyFormFill) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (productId == null) "Новый продукт" else "Изменить продукт") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isSeed) {
                Text(state.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Каталожный продукт — здесь можно добавить только порции.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> vm.onFormChange { it.copy(name = v) } },
                label = { Text("Название") },
                isError = state.errors["name"] != null,
                supportingText = state.errors["name"]?.let { { Text(it) } }
                    ?: if (state.prefilled) { { Text("Распознано с этикетки — проверьте значения") } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.barcodeText,
                onValueChange = { v -> vm.onFormChange { it.copy(barcodeText = v.filter { c -> c.isDigit() }) } },
                label = { Text("Штрихкод (если есть)") },
                isError = state.errors["barcode"] != null,
                supportingText = state.errors["barcode"]?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                trailingIcon = {
                    IconButton(onClick = onScanBarcode) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Сканировать штрихкод")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = onScanLabel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Заполнить при помощи ИИ")
            }

            NumberField(
                label = "Калории, ккал (пусто = рассчитать)",
                value = state.kcalText,
                error = state.errors["kcal"],
                onValueChange = { v -> vm.onFormChange { it.copy(kcalText = v) } },
            )
            NumberField(
                label = "Углеводы, г",
                value = state.carbsText,
                error = state.errors["carbs"],
                onValueChange = { v -> vm.onFormChange { it.copy(carbsText = v) } },
            )
            NumberField(
                label = "Белки, г",
                value = state.proteinText,
                error = state.errors["protein"],
                onValueChange = { v -> vm.onFormChange { it.copy(proteinText = v) } },
            )
            NumberField(
                label = "Жиры, г",
                value = state.fatText,
                error = state.errors["fat"],
                onValueChange = { v -> vm.onFormChange { it.copy(fatText = v) } },
            )
            }

            PortionEditor(
                rows = state.portions,
                error = state.errors["portions"],
                onRowsChange = { rows -> vm.onFormChange { it.copy(portions = rows) } },
            )

            Button(
                onClick = { vm.save(onSaved) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

            if (productId != null && !state.isSeed) {
                var confirmDelete by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить продукт", color = MaterialTheme.colorScheme.error)
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Удалить продукт?") },
                        text = { Text("Записи дневника с этим продуктом тоже будут удалены.") },
                        confirmButton = {
                            TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
