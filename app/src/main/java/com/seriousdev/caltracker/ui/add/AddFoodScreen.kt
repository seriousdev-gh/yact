package com.seriousdev.caltracker.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.domain.Product
import com.seriousdev.caltracker.domain.ProductPortion
import com.seriousdev.caltracker.ui.format.kcalText

/**
 * Product search + portion dialog. Picking a product opens the gram/meal dialog;
 * saving appends to the diary for [dayEpochDay]. With [onPickedForRecipe]
 * (recipe-ingredient mode) the meal-type chips are hidden and the confirmed
 * (product, grams) is delivered to the callback instead of the diary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(
    dayEpochDay: Long,
    navId: Long,
    preselectedProductId: Long? = null,
    onEditProduct: (Product) -> Unit,
    onDone: () -> Unit,
    onPickedForRecipe: ((productId: Long, grams: Double) -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val vm: AddFoodViewModel = viewModel(key = "add-$dayEpochDay-$navId", factory = viewModelFactory {
        initializer { AddFoodViewModel(dayEpochDay, container.products, container.diary) }
    })
    val results by vm.results.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recent by vm.recent.collectAsState()
    val saved by vm.saved.collectAsState()
    val queryText by vm.searchQuery.collectAsState()

    LaunchedEffect(saved) { if (saved) onDone() }

    // The dialog is driven by product id so it survives a round trip to the
    // product form: on return the product is re-fetched (new portions visible)
    // and the dialog closes by itself if the product was deleted meanwhile.
    var pendingId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Bumped when leaving for the product form so the product/portions are
    // re-fetched on return even if this entry's composition survived.
    var productRefresh by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(preselectedProductId) {
        if (preselectedProductId != null && pendingId == null) pendingId = preselectedProductId
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Добавить продукт") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.padding(insets).fillMaxSize()) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { vm.searchQuery.value = it },
                placeholder = { Text("Поиск по названию") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val favoriteIds = favorites.map { it.id }.toSet()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (queryText.isBlank()) {
                    if (favorites.isNotEmpty()) {
                        item { Text("Избранное", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        items(favorites, key = { "fav-${it.id}" }) { product ->
                            ProductRow(
                                product = product,
                                onClick = { pendingId = product.id },
                                onToggleFavorite = { vm.setFavorite(product.id, !product.isFavorite) },
                            )
                        }
                    }
                    val recentOnly = recent.filter { it.id !in favoriteIds }
                    if (recentOnly.isNotEmpty()) {
                        item { Text("Недавние", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        items(recentOnly, key = { "rec-${it.id}" }) { product ->
                            ProductRow(
                                product = product,
                                onClick = { pendingId = product.id },
                                onToggleFavorite = { vm.setFavorite(product.id, !product.isFavorite) },
                            )
                        }
                    }
                    item { Text("Все продукты", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                }
                items(results, key = { "all-${it.id}" }) { product ->
                    ProductRow(
                        product = product,
                        onClick = { pendingId = product.id },
                        onToggleFavorite = { vm.setFavorite(product.id, !product.isFavorite) },
                    )
                }
            }
        }
    }

    val pendingProduct by produceState<Product?>(initialValue = null, pendingId, productRefresh) {
        value = pendingId?.let { container.products.getById(it) }
    }
    val portions by produceState<List<ProductPortion>?>(
        initialValue = null,
        pendingProduct?.id,
        productRefresh,
    ) {
        value = pendingProduct?.let { container.products.getPortions(it.id) }
    }
    val product = pendingProduct
    val portionList = portions
    if (product != null && portionList != null) {
        PortionDialog(
            product = product,
            portions = portionList,
            showMealTypePicker = onPickedForRecipe == null,
            onDismiss = { pendingId = null },
            onConfirm = { grams, meal ->
                if (onPickedForRecipe != null) onPickedForRecipe(product.id, grams)
                else vm.add(product.id, meal, grams)
                pendingId = null
            },
            onEditProduct = {
                productRefresh++
                onEditProduct(product)
            },
        )
    }
}

@Composable
private fun ProductRow(
    product: Product,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(product.name + if (product.isRecipe) " · рецепт" else "")
        },
        supportingContent = {
            Text("${kcalText(product.per100g.kcal)} ккал/100 г")
        },
        trailingContent = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (product.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (product.isFavorite) "Убрать из избранного" else "В избранное",
                    tint = if (product.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
