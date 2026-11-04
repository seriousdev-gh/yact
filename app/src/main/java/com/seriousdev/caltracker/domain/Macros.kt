package com.seriousdev.caltracker.domain

/**
 * Energy and macronutrient amounts, grams for macros and kcal for [kcal].
 */
data class Macros(
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
) {
    operator fun plus(other: Macros) = Macros(
        kcal = kcal + other.kcal,
        protein = protein + other.protein,
        fat = fat + other.fat,
        carbs = carbs + other.carbs,
    )

    operator fun times(factor: Double) = Macros(
        kcal = kcal * factor,
        protein = protein * factor,
        fat = fat * factor,
        carbs = carbs * factor,
    )

    companion object {
        val ZERO = Macros(0.0, 0.0, 0.0, 0.0)
    }
}
