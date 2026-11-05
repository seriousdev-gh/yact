package com.seriousdev.caltracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.runtime.NavEntry
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.ui.IngredientPickResult
import com.seriousdev.caltracker.ui.add.AddFoodScreen
import com.seriousdev.caltracker.ui.catalog.CatalogScreen
import com.seriousdev.caltracker.ui.catalog.PrefilledProduct
import com.seriousdev.caltracker.ui.catalog.ProductFormScreen
import com.seriousdev.caltracker.ui.scan.BarcodePickScreen
import com.seriousdev.caltracker.ui.scan.BarcodeScanScreen
import com.seriousdev.caltracker.ui.scan.LabelScanScreen
import com.seriousdev.caltracker.ui.catalog.RecipeFormScreen
import com.seriousdev.caltracker.ui.diary.DiaryScreen
import com.seriousdev.caltracker.ui.nav.AppKey
import com.seriousdev.caltracker.ui.profile.ProfileScreen
import com.seriousdev.caltracker.ui.stats.StatsScreen

@androidx.camera.core.ExperimentalGetImage
@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    var onboardingDone by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { onboardingDone = container.settingsStore.isOnboardingDone() }

    when (onboardingDone) {
        null -> Unit // first frame: splash background
        false -> ProfileScreen(
            onboarding = true,
            onOnboardingDone = { onboardingDone = true },
        )
        true -> MainShell()
    }
}

private data class BottomTab(val key: AppKey, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@androidx.camera.core.ExperimentalGetImage
@Composable
private fun MainShell() {
    val backStack = rememberNavBackStack(AppKey.Today)

    val tabs = listOf(
        BottomTab(AppKey.Today, "Сегодня", Icons.Filled.Restaurant),
        BottomTab(AppKey.Catalog, "Каталог", Icons.Filled.List),
        BottomTab(AppKey.Stats, "Статистика", Icons.Filled.Insights),
        BottomTab(AppKey.Profile, "Профиль", Icons.Filled.Person),
    )
    val topKey = backStack.lastOrNull()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Pushed flows (AddFood, ProductForm) run full-screen.
            if (topKey == null || topKey in AppKey.roots) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = topKey == tab.key,
                            onClick = {
                                if (topKey != tab.key) {
                                    backStack.clear()
                                    backStack.add(tab.key)
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { insets ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(insets),
            entryProvider = { key ->
                NavEntry(key) {
                    ScreenContent(key, backStack)
                }
            },
        )
    }
}

@androidx.camera.core.ExperimentalGetImage
@Composable
private fun ScreenContent(key: NavKey, backStack: NavBackStack<NavKey>) {
    when (key) {
        AppKey.Today -> DiaryScreen(
            dayEpochDay = com.seriousdev.caltracker.ui.format.todayEpochDay(),
            onAddFood = { day, meal -> backStack.add(AppKey.AddFood(day, mealType = meal)) },
            onScanBarcode = { day, meal -> backStack.add(AppKey.BarcodeScan(dayEpochDay = day, mealType = meal)) },
            onEditProduct = { product ->
                backStack.add(
                    if (product.isRecipe) AppKey.RecipeForm(product.id) else AppKey.ProductForm(product.id),
                )
            },
            onCreateRecipe = { items ->
                backStack.add(AppKey.RecipeForm(recipeId = null, prefill = items))
            },
        )
        AppKey.Catalog -> CatalogScreen(
            onCreateProduct = { backStack.add(AppKey.ProductForm(null)) },
            onCreateRecipe = { backStack.add(AppKey.RecipeForm(null)) },
            onEditProduct = { id -> backStack.add(AppKey.ProductForm(id)) },
            onEditRecipe = { id -> backStack.add(AppKey.RecipeForm(id)) },
        )
        AppKey.Stats -> StatsScreen()
        AppKey.Profile -> ProfileScreen(onboarding = false)
        is AppKey.AddFood -> AddFoodScreen(
            dayEpochDay = key.dayEpochDay,
            navId = key.navId,
            preselectedProductId = key.preselectedProductId,
            preselectedMealType = key.mealType,
            onEditProduct = { product ->
                backStack.add(
                    if (product.isRecipe) AppKey.RecipeForm(product.id) else AppKey.ProductForm(product.id),
                )
            },
            onDone = { backStack.remove(key) },
        )
        is AppKey.ProductForm -> ProductFormScreen(
            productId = key.productId,
            navId = key.navId,
            prefill = if (key.productId == null && (key.prefillName != null || key.prefillBarcode != null)) {
                PrefilledProduct(
                    name = key.prefillName.orEmpty(),
                    kcal = key.prefillKcal,
                    protein = key.prefillProtein,
                    fat = key.prefillFat,
                    carbs = key.prefillCarbs,
                    barcode = key.prefillBarcode,
                )
            } else {
                null
            },
            onScanBarcode = { backStack.add(AppKey.BarcodePick(formNavId = key.navId)) },
            onScanLabel = { backStack.add(AppKey.LabelScan(formNavId = key.navId)) },
            onSaved = { id ->
                backStack.remove(key)
                key.logMealDay?.let { day ->
                    backStack.add(AppKey.AddFood(dayEpochDay = day, mealType = key.logMealType, preselectedProductId = id))
                }
                key.ingredientRecipeNavId?.let { recipeNavId ->
                    backStack.add(AppKey.IngredientPick(recipeFormNavId = recipeNavId, preselectedProductId = id))
                }
            },
            onDone = { backStack.remove(key) },
        )
        is AppKey.RecipeForm -> RecipeFormScreen(
            recipeId = key.recipeId,
            navId = key.navId,
            prefill = key.prefill,
            onAddIngredient = { backStack.add(AppKey.IngredientPick(recipeFormNavId = key.navId)) },
            onScanBarcode = { backStack.add(AppKey.BarcodeScan(recipeFormNavId = key.navId)) },
            onDone = { backStack.remove(key) },
        )
        is AppKey.IngredientPick -> {
            val container = LocalAppContainer.current
            AddFoodScreen(
                dayEpochDay = -1L, // unused: picks go to the recipe form, not the diary
                navId = key.navId,
                preselectedProductId = key.preselectedProductId,
                onEditProduct = { product ->
                    backStack.add(
                        if (product.isRecipe) AppKey.RecipeForm(product.id) else AppKey.ProductForm(product.id),
                    )
                },
                onPickedForRecipe = { productId, grams ->
                    container.ingredientPicks.put(key.recipeFormNavId, IngredientPickResult(productId, grams))
                    backStack.remove(key)
                },
                onDone = { backStack.remove(key) },
            )
        }
        is AppKey.BarcodeScan -> BarcodeScanScreen(
            navId = key.navId,
            onDone = { backStack.remove(key) },
            onMatched = { id ->
                backStack.remove(key)
                if (key.recipeFormNavId != null) {
                    backStack.add(AppKey.IngredientPick(key.recipeFormNavId, preselectedProductId = id))
                } else {
                    key.dayEpochDay?.let { day ->
                        backStack.add(AppKey.AddFood(dayEpochDay = day, mealType = key.mealType, preselectedProductId = id))
                    }
                }
            },
            onOpenProductForm = { prefill ->
                backStack.remove(key)
                backStack.add(
                    AppKey.ProductForm(
                        prefillName = prefill.name,
                        prefillKcal = prefill.kcal,
                        prefillProtein = prefill.protein,
                        prefillFat = prefill.fat,
                        prefillCarbs = prefill.carbs,
                        prefillBarcode = prefill.barcode,
                        logMealDay = key.dayEpochDay,
                        logMealType = key.mealType,
                        ingredientRecipeNavId = key.recipeFormNavId,
                    ),
                )
            },
        )
        is AppKey.BarcodePick -> BarcodePickScreen(
            formNavId = key.formNavId,
            navId = key.navId,
            onDone = { backStack.remove(key) },
        )
        is AppKey.LabelScan -> {
            val container = LocalAppContainer.current
            LabelScanScreen(
                navId = key.navId,
                onDone = { backStack.remove(key) },
                onRecognized = { prefill ->
                    container.formFills.put(key.formNavId, prefill)
                    backStack.remove(key)
                },
            )
        }
        else -> Unit
    }
}
