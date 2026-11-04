package com.seriousdev.caltracker.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.domain.Product
import com.seriousdev.caltracker.ui.format.kcalText

/**
 * Built-in catalog: category chips + search + custom products section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onCreateProduct: () -> Unit,
    onCreateRecipe: () -> Unit,
    onEditProduct: (Long) -> Unit,
    onEditRecipe: (Long) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: CatalogViewModel = viewModel(factory = viewModelFactory {
        initializer { CatalogViewModel(container.products) }
    })
    val products by vm.products.collectAsState()
    val custom by vm.customProducts.collectAsState()

    var fabMenuOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Каталог продуктов") }) },
        floatingActionButton = {
            androidx.compose.foundation.layout.Box {
                FloatingActionButton(onClick = { fabMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Создать")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = fabMenuOpen,
                    onDismissRequest = { fabMenuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Новый продукт") },
                        onClick = { fabMenuOpen = false; onCreateProduct() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Новый рецепт") },
                        onClick = { fabMenuOpen = false; onCreateRecipe() },
                    )
                }
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                OutlinedTextField(
                    value = vm.searchQuery.collectAsState().value,
                    onValueChange = { vm.searchQuery.value = it },
                    placeholder = { Text("Поиск") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (custom.isNotEmpty()) {
                item { Text("Мои продукты", style = MaterialTheme.typography.titleSmall) }
                items(custom, key = { "custom-${it.id}" }) { product ->
                    CatalogRow(
                        product = product,
                        onClick = {
                            if (product.isRecipe) onEditRecipe(product.id) else onEditProduct(product.id)
                        },
                        onToggleFavorite = { vm.setFavorite(product, !product.isFavorite) },
                        trailing = {
                            IconButton(
                                onClick = {
                                    if (product.isRecipe) onEditRecipe(product.id) else onEditProduct(product.id)
                                },
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Изменить")
                            }
                        },
                    )
                }
                item { Text("Каталог", style = MaterialTheme.typography.titleSmall) }
            }
            items(products.filter { !it.isCustom && !it.isRecipe }, key = { "cat-${it.id}" }) { product ->
                CatalogRow(
                    product = product,
                    onClick = { onEditProduct(product.id) },
                    onToggleFavorite = { vm.setFavorite(product, !product.isFavorite) },
                    trailing = null,
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(
    product: Product,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    ListItem(
        headlineContent = {
            Text(product.name + if (product.isRecipe) " · рецепт" else "")
        },
        supportingContent = {
            Text("${kcalText(product.per100g.kcal)} ккал/100 г")
        },
        trailingContent = {
            Row {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (product.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (product.isFavorite) "Убрать из избранного" else "В избранное",
                        tint = if (product.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                trailing?.invoke()
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
