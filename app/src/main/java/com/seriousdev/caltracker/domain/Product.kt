package com.seriousdev.caltracker.domain

/** A food product with nutrition values per 100 g. */
data class Product(
    val id: Long,
    val name: String,
    val category: String,
    val per100g: Macros,
    val isCustom: Boolean = false,
    val isFavorite: Boolean = false,
    val kind: ProductKind = ProductKind.PRODUCT,
    /** EAN/UPC when imported by barcode scan. */
    val barcode: String? = null,
) {
    val isRecipe: Boolean get() = kind == ProductKind.RECIPE

    /** Nutrition for [grams] of this product. */
    fun forGrams(grams: Double): Macros = per100g * (grams / 100.0)
}

/** Whether a products-row is plain food or a user-composed recipe. */
enum class ProductKind { PRODUCT, RECIPE }

/** A named serving of a product: "ломтик" = 15 g. */
data class ProductPortion(
    val id: Long = 0,
    val label: String,
    val grams: Double,
)

/** Manual macro goal in grams; kcal target is derived (4/9/4). */
data class MacroTargets(
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
) {
    /** Atwater energy of the targets. */
    fun toKcal(): Double = 4.0 * proteinG + 9.0 * fatG + 4.0 * carbsG
}

/** Meal slot a diary entry belongs to. */
enum class MealType(val ruLabel: String) {
    BREAKFAST("Завтрак"),
    LUNCH("Обед"),
    DINNER("Ужин"),
    SNACK("Перекус"),
}

/** One eaten product within a meal on a given day. */
data class MealEntry(
    val id: Long,
    val dayEpochDay: Long,
    val mealType: MealType,
    val productId: Long,
    val grams: Double,
)
