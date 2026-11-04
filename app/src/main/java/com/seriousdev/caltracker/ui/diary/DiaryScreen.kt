package com.seriousdev.caltracker.ui.diary

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.domain.MealType
import com.seriousdev.caltracker.domain.Product
import com.seriousdev.caltracker.domain.ProductPortion
import com.seriousdev.caltracker.ui.add.PortionDialog
import com.seriousdev.caltracker.ui.format.gramsText
import com.seriousdev.caltracker.ui.format.kcalText
import com.seriousdev.caltracker.ui.format.macrosShortText
import com.seriousdev.caltracker.ui.nav.IngredientPrefill

/**
 * "Today" diary: progress against daily goals plus meal groups for the selected day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    onAddFood: (Long) -> Unit,
    onScanBarcode: (Long) -> Unit,
    onEditProduct: (Product) -> Unit,
    onCreateRecipe: (List<IngredientPrefill>) -> Unit,
    dayEpochDay: Long,
) {
    val container = LocalAppContainer.current
    val vm: DiaryViewModel = viewModel(key = "diary-$dayEpochDay", factory = viewModelFactory {
        initializer { DiaryViewModel(container.diary, container.profile, dayEpochDay) }
    })
    val state by vm.state.collectAsState()

    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var fabMenuOpen by rememberSaveable { mutableStateOf(false) }
    // Multi-select mode (entered by long-pressing an entry): entry ids in tap
    // order; non-empty == selection mode active. Feeds "создать рецепт".
    var selectedIds by rememberSaveable(
        stateSaver = listSaver<List<Long>, Long>(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList()) }
    // Bumped when the dialog leaves for the product form so the freshly saved
    // portions are reloaded on return.
    var portionRefresh by rememberSaveable { mutableStateOf(0) }
    val editingItem = editing?.let { id -> state.groups.flatMap { it.items }.firstOrNull { it.entry.id == id } }
    val selecting = selectedIds.isNotEmpty()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("Выбрано: ${selectedIds.size}") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptyList() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Выйти из режима выбора")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            val byId = state.groups.flatMap { it.items }.associateBy { it.entry.id }
                            onCreateRecipe(selectedIds.mapNotNull { id ->
                                byId[id]?.let { item -> IngredientPrefill(item.product.id, item.entry.grams) }
                            })
                            selectedIds = emptyList()
                        }) { Text("Создать рецепт") }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = vm::previousDay) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий день")
                            }
                            Text(state.dayLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            IconButton(onClick = vm::nextDay, enabled = !state.isToday) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий день")
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting) androidx.compose.foundation.layout.Box {
                FloatingActionButton(onClick = { fabMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить продукт")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = fabMenuOpen,
                    onDismissRequest = { fabMenuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Добавить вручную") },
                        onClick = { fabMenuOpen = false; onAddFood(state.dayEpochDay) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Сканировать штрихкод") },
                        onClick = { fabMenuOpen = false; onScanBarcode(state.dayEpochDay) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GoalCard(state) }
            if (state.isEmpty) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(
                            "Записей пока нет. Нажмите «+», чтобы добавить продукт.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            val visibleTypes = MealType.entries.filter { type ->
                state.groups.any { it.type == type } || state.yesterdayMealTypes.contains(type)
            }
            items(visibleTypes, key = { it.name }) { type ->
                val group = state.groups.firstOrNull { it.type == type }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(type.ruLabel, style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.yesterdayMealTypes.contains(type)) {
                                TextButton(onClick = { vm.copyMealFromYesterday(type) }) {
                                    Text("со вчера", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (group != null) {
                                Text("${kcalText(group.subtotal.kcal)} ккал", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    group?.items?.forEach { item ->
                        DiaryItemRow(
                            item = item,
                            selected = if (selecting) item.entry.id in selectedIds else null,
                            onClick = {
                                if (selecting) {
                                    selectedIds = if (item.entry.id in selectedIds)
                                        selectedIds - item.entry.id
                                    else selectedIds + item.entry.id
                                } else editing = item.entry.id
                            },
                            onLongClick = {
                                if (!selecting) selectedIds = listOf(item.entry.id)
                            },
                        )
                    }
                }
            }
        }
    }

    // Same dialog as adding: portion chips + grams + meal type, plus "Удалить".
    // The entry's grams pre-highlight the matching portion chip.
    editingItem?.let { item ->
        val portions by produceState<List<ProductPortion>?>(
            initialValue = null,
            item.product.id,
            portionRefresh,
        ) {
            value = container.products.getPortions(item.product.id)
        }
        val list = portions
        if (list != null) {
            PortionDialog(
                product = item.product,
                portions = list,
                initialGrams = item.entry.grams,
                initialMealType = item.entry.mealType,
                confirmLabel = "Сохранить",
                onDismiss = { editing = null },
                onConfirm = { grams, mealType ->
                    vm.updateEntry(item.entry.id, grams, mealType)
                    editing = null
                },
                onDelete = {
                    vm.deleteEntry(item.entry.id)
                    editing = null
                },
                // Keep the dialog open across the round trip; a deleted product
                // drops the entry (cascade), so `editingItem` disappears and the
                // dialog closes with it.
                onEditProduct = {
                    portionRefresh++
                    onEditProduct(item.product)
                },
            )
        }
    }
}

@Composable
private fun GoalCard(state: DiaryUiState) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val kcalTarget = state.goals?.kcal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("${kcalText(state.totals.kcal)} / ${kcalTarget?.let(::kcalText) ?: "—"} ккал", style = MaterialTheme.typography.headlineSmall)
                    kcalTarget?.let { target ->
                        val remaining = target - state.totals.kcal
                        Text(
                            if (remaining >= 0) "осталось ${kcalText(remaining)}" else "профицит ${kcalText(-remaining)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            kcalTarget?.let { target ->
                GoalProgressBar(
                    progress = { (state.totals.kcal / target).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MacroBlock("Углеводы", state.totals.carbs, state.goals?.carbs, Modifier.weight(1f))
                MacroBlock("Белки", state.totals.protein, state.goals?.protein, Modifier.weight(1f))
                MacroBlock("Жиры", state.totals.fat, state.goals?.fat, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MacroBlock(label: String, value: Double, target: Double?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            "${kcalText(value)} / ${target?.let(::kcalText) ?: "—"} г",
            style = MaterialTheme.typography.bodySmall,
        )
        if (target != null && target > 0.0) {
            GoalProgressBar(
                progress = { (value / target).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Goal progress bar: fill uses `primary`; the track is `onSurface` at 30% -
 * a neutral contrast against any card surface in both light and dark themes
 * (M3's primary-at-30% default and the surface-container tokens both blend
 * into the card).
 */
@Composable
private fun GoalProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
    )
}

@Composable
private fun DiaryItemRow(
    item: DiaryItem,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.product.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${gramsText(item.entry.grams)} · ${macrosShortText(item.macros.protein, item.macros.fat, item.macros.carbs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("${kcalText(item.macros.kcal)} ккал", style = MaterialTheme.typography.labelLarge)
        }
    }
}

