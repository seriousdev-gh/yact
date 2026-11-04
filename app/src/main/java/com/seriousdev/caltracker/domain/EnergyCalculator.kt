package com.seriousdev.caltracker.domain

enum class Sex { MALE, FEMALE }

enum class ActivityLevel(val coefficient: Double, val ruLabel: String) {
    SEDENTARY(1.2, "Минимальная активность"),
    LIGHT(1.375, "Лёгкая активность 1–3 тренировки/нед"),
    MODERATE(1.55, "Средняя активность 3–5 тренировок/нед"),
    HIGH(1.725, "Высокая активность 6–7 тренировок/нед"),
    EXTREME(1.9, "Очень высокая активность, физическая работа"),
}

enum class Goal(val ruLabel: String) {
    MAINTAIN("Поддержание веса"),
    LOSE("Похудение"),
    GAIN("Набор массы"),
}

/** Everything the energy calculation needs from the user. */
data class Profile(
    val sex: Sex,
    val ageYears: Int,
    val heightCm: Double,
    val weightKg: Double,
    val activityLevel: ActivityLevel,
    val goal: Goal,
    /** When set (and no [macroTargets]), wins over the computed daily target. */
    val manualKcalTarget: Double? = null,
    /** When all three are set, the kcal target is derived from them (4/9/4). */
    val macroTargets: MacroTargets? = null,
)

/**
 * Daily energy expenditure via the Mifflin-St Jeor equation scaled by activity level,
 * then adjusted by goal (−15 % loss, +10 % gain).
 */
object EnergyCalculator {

    /** Basal metabolic rate, kcal/day. */
    fun bmr(profile: Profile): Double = when (profile.sex) {
        Sex.MALE -> 10.0 * profile.weightKg + 6.25 * profile.heightCm - 5.0 * profile.ageYears + 5.0
        Sex.FEMALE -> 10.0 * profile.weightKg + 6.25 * profile.heightCm - 5.0 * profile.ageYears - 161.0
    }

    /** Total daily energy expenditure before the goal adjustment. */
    fun tdee(profile: Profile): Double = bmr(profile) * profile.activityLevel.coefficient

    /** Recommended daily kcal target, honoring manual goals when present. */
    fun dailyKcalTarget(profile: Profile): Double {
        profile.macroTargets?.let { return it.toKcal() }
        profile.manualKcalTarget?.let { return it }
        val tdee = tdee(profile)
        return when (profile.goal) {
            Goal.MAINTAIN -> tdee
            Goal.LOSE -> tdee * 0.85
            Goal.GAIN -> tdee * 1.10
        }
    }
}

/**
 * Turns a kcal target into macro targets: 30 % protein, 25 % fat, 45 % carbs
 * of energy (Atwater factors 4/9/4), and resolves manual macro goals.
 */
object GoalsCalculator {

    const val PROTEIN_SHARE = 0.30
    const val FAT_SHARE = 0.25
    const val CARBS_SHARE = 0.45

    /** Grams of protein/fat/carbs for [kcalTarget]. */
    fun macrosFor(kcalTarget: Double): Macros = Macros(
        kcal = kcalTarget,
        protein = kcalTarget * PROTEIN_SHARE / 4.0,
        fat = kcalTarget * FAT_SHARE / 9.0,
        carbs = kcalTarget * CARBS_SHARE / 4.0,
    )

    /**
     * Effective daily goals. Manual [Profile.macroTargets] win (kcal derived);
     * otherwise kcal target (manual or computed) split by shares.
     */
    fun effectiveGoals(profile: Profile): Macros {
        profile.macroTargets?.let { targets ->
            return Macros(
                kcal = targets.toKcal(),
                protein = targets.proteinG,
                fat = targets.fatG,
                carbs = targets.carbsG,
            )
        }
        return macrosFor(EnergyCalculator.dailyKcalTarget(profile))
    }

    fun dailyGoals(profile: Profile): Macros = effectiveGoals(profile)
}

/** Sums diary entries into total nutrition using the referenced products. */
object DiaryTotals {

    /**
     * @throws NoSuchElementException if an entry references an unknown [products] id.
     */
    fun sum(entries: List<MealEntry>, products: Map<Long, Product>): Macros =
        entries.fold(Macros.ZERO) { acc, entry ->
            acc + products.getValue(entry.productId).forGrams(entry.grams)
        }

    fun sum(entries: List<MealEntry>, products: Collection<Product>): Macros =
        sum(entries, products.associateBy { it.id })
}
