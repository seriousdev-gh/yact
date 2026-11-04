package com.seriousdev.caltracker.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.ui.format.kcalText
import com.seriousdev.caltracker.ui.format.macrosShortText
import com.seriousdev.caltracker.ui.nav.IngredientPrefill
import kotlinx.coroutines.flow.filter

/** Create/edit a recipe from existing products. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeFormScreen(
    recipeId: Long?,
    navId: Long,
    prefill: List<IngredientPrefill> = emptyList(),
    onAddIngredient: () -> Unit,
    onScanBarcode: () -> Unit,
    onDone: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: RecipeFormViewModel = viewModel(key = "recipe-form-${recipeId ?: "new"}-$navId",
        factory = viewModelFactory {
            initializer {
                RecipeFormViewModel(recipeId, container.products, container.recipes, prefill)
            }
        })
    val state by vm.state.collectAsState()

    // Ingredient picks (search/scan flow) parked for this form instance.
    LaunchedEffect(navId) {
        container.ingredientPicks.take(navId)?.let { vm.addPickedIngredient(it.productId, it.grams) }
        container.ingredientPicks.signals()
            .filter { it == navId }
            .collect { container.ingredientPicks.take(navId)?.let { vm.addPickedIngredient(it.productId, it.grams) } }
    }

    var addMenuOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (recipeId == null) "Новый рецепт" else "Изменить рецепт") },
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
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> vm.onFormChange { it.copy(name = v) } },
                label = { Text("Название") },
                isError = state.errors["name"] != null,
                supportingText = state.errors["name"]?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Ингредиенты", style = MaterialTheme.typography.labelLarge)
            state.errors["ingredients"]?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.ingredients.none { row -> row.product != null }) {
                Text(
                    "Пока пусто. Добавьте ингредиенты через «+»: поиск или скан штрихкода.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.ingredients.forEachIndexed { index, row ->
                val product = row.product ?: return@forEachIndexed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        product.name,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = row.gramsText,
                        onValueChange = { v ->
                            vm.onFormChange { s ->
                                s.copy(ingredients = s.ingredients.toMutableList()
                                    .also { it[index] = row.copy(gramsText = v) })
                            }
                            vm.suggestPortionIfEmpty()
                        },
                        label = { Text("г") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(0.45f),
                    )
                    IconButton(
                        onClick = {
                            vm.onFormChange { s ->
                                s.copy(ingredients = s.ingredients.filterIndexed { i, _ -> i != index }
                                    .ifEmpty { listOf(IngredientRow()) })
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Убрать ингредиент")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    IconButton(onClick = { addMenuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Добавить ингредиент")
                    }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Добавить вручную") },
                            onClick = { addMenuOpen = false; onAddIngredient() },
                        )
                        DropdownMenuItem(
                            text = { Text("Сканировать штрихкод") },
                            onClick = { addMenuOpen = false; onScanBarcode() },
                        )
                    }
                }
            }

            vm.preview()?.let { (totalGrams, per100) ->
                Text(
                    "Всего ${kcalText(totalGrams)} г · На 100 г: ${kcalText(per100.kcal)} ккал · " +
                        macrosShortText(per100.protein, per100.fat, per100.carbs),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            PortionEditor(
                rows = state.portions,
                error = state.errors["portions"],
                onRowsChange = { rows -> vm.onFormChange { it.copy(portions = rows) } },
            )

            Button(
                onClick = { vm.save(onDone) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

            if (recipeId != null) {
                var confirmDelete by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить рецепт", color = MaterialTheme.colorScheme.error)
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Удалить рецепт?") },
                        text = { Text("Ингредиенты останутся в каталоге; записи дневника с этим рецептом будут удалены.") },
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
