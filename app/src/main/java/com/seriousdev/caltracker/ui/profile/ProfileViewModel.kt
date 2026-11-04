package com.seriousdev.caltracker.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.ProfileRepository
import com.seriousdev.caltracker.data.SettingsStore
import com.seriousdev.caltracker.domain.ActivityLevel
import com.seriousdev.caltracker.domain.EnergyCalculator
import com.seriousdev.caltracker.domain.Goal
import com.seriousdev.caltracker.domain.MacroTargets
import com.seriousdev.caltracker.domain.Profile
import com.seriousdev.caltracker.domain.Sex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the daily goal is set: computed from profile, manual kcal, or manual macros. */
enum class GoalMode { AUTO, KCAL, MACROS }

/** Raw form state; numbers are kept as user-entered text. */
data class ProfileFormState(
    val loading: Boolean = true,
    val sex: Sex = Sex.MALE,
    val ageText: String = "",
    val heightText: String = "",
    val weightText: String = "",
    val activity: ActivityLevel = ActivityLevel.LIGHT,
    val goal: Goal = Goal.MAINTAIN,
    val goalMode: GoalMode = GoalMode.AUTO,
    /** Manual kcal override; used when [goalMode] is KCAL. */
    val manualKcalText: String = "",
    /** Manual macro targets in grams; used when [goalMode] is MACROS. */
    val targetProteinText: String = "",
    val targetFatText: String = "",
    val targetCarbsText: String = "",
    val savedOnce: Boolean = false,
    val fieldErrors: Map<ProfileField, String> = emptyMap(),
)

enum class ProfileField { AGE, HEIGHT, WEIGHT, MANUAL_KCAL, TARGET_PROTEIN, TARGET_FAT, TARGET_CARBS }

/** Result of parsing + validating the form. */
sealed interface ParsedProfile {
    data class Valid(val profile: Profile) : ParsedProfile
    data class Invalid(val errors: Map<ProfileField, String>) : ParsedProfile
}

/**
 * Parses and validates [ProfileFormState] against the spec ranges
 * (age 10–120, height 50–280 cm, weight 2–500 kg; KCAL mode: kcal 500–10000;
 * MACROS mode: protein 30–400 g, fat 20–300 g, carbs 30–600 g).
 */
fun parseProfileForm(state: ProfileFormState): ParsedProfile {
    val errors = mutableMapOf<ProfileField, String>()

    fun parseInRange(text: String, min: Double, max: Double, field: ProfileField, message: String): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull()
        return when {
            value == null -> { errors[field] = "Введите число"; null }
            value < min || value > max -> { errors[field] = message; null }
            else -> value
        }
    }

    val age = parseInRange(state.ageText, 10.0, 120.0, ProfileField.AGE, "Возраст 10–120")?.toInt()
    val height = parseInRange(state.heightText, 50.0, 280.0, ProfileField.HEIGHT, "Рост 50–280 см")
    val weight = parseInRange(state.weightText, 2.0, 500.0, ProfileField.WEIGHT, "Вес 2–500 кг")

    val manual = when (state.goalMode) {
        GoalMode.AUTO, GoalMode.MACROS -> null
        GoalMode.KCAL -> parseInRange(
            state.manualKcalText, 500.0, 10_000.0, ProfileField.MANUAL_KCAL, "Цель 500–10000 ккал",
        )
    }

    val macroTargets = if (state.goalMode == GoalMode.MACROS) {
        val protein = parseInRange(state.targetProteinText, 30.0, 400.0, ProfileField.TARGET_PROTEIN, "Белки 30–400 г")
        val fat = parseInRange(state.targetFatText, 20.0, 300.0, ProfileField.TARGET_FAT, "Жиры 20–300 г")
        val carbs = parseInRange(state.targetCarbsText, 30.0, 600.0, ProfileField.TARGET_CARBS, "Углеводы 30–600 г")
        if (protein != null && fat != null && carbs != null) MacroTargets(protein, fat, carbs) else null
    } else {
        null
    }

    if (errors.isNotEmpty() || age == null || height == null || weight == null) {
        return ParsedProfile.Invalid(errors)
    }
    return ParsedProfile.Valid(
        Profile(
            sex = state.sex,
            ageYears = age,
            heightCm = height,
            weightKg = weight,
            activityLevel = state.activity,
            goal = state.goal,
            manualKcalTarget = manual,
            macroTargets = macroTargets,
        ),
    )
}

class ProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val form = MutableStateFlow(ProfileFormState())
    val state: StateFlow<ProfileFormState> = form

    /** True once the user edited the form without saving; guards [reload]. */
    private var dirty = false

    init {
        viewModelScope.launch {
            profileRepository.observe().collect { loaded ->
                if (loaded != null && form.value.loading) {
                    form.update { toForm(loaded, it) }
                } else if (form.value.loading) {
                    form.update { it.copy(loading = false) }
                }
            }
        }
    }

    /**
     * Re-seeds the form from the latest stored profile when the tab is (re)opened,
     * so values changed elsewhere (e.g. weight logged in Stats) stay in sync.
     * Unsaved local edits are preserved.
     */
    fun reload() {
        if (dirty) return
        viewModelScope.launch {
            val loaded = profileRepository.current() ?: return@launch
            form.update { toForm(loaded, it) }
        }
    }

    private fun toForm(loaded: Profile, prev: ProfileFormState) = prev.copy(
        loading = false,
        sex = loaded.sex,
        ageText = loaded.ageYears.toString(),
        heightText = trimZero(loaded.heightCm),
        weightText = trimZero(loaded.weightKg),
        activity = loaded.activityLevel,
        goal = loaded.goal,
        goalMode = when {
            loaded.macroTargets != null -> GoalMode.MACROS
            loaded.manualKcalTarget != null -> GoalMode.KCAL
            else -> GoalMode.AUTO
        },
        manualKcalText = loaded.manualKcalTarget?.let(::trimZero) ?: "",
        targetProteinText = loaded.macroTargets?.let { trimZero(it.proteinG) } ?: "",
        targetFatText = loaded.macroTargets?.let { trimZero(it.fatG) } ?: "",
        targetCarbsText = loaded.macroTargets?.let { trimZero(it.carbsG) } ?: "",
        savedOnce = true,
    )

    fun onFormChange(transform: (ProfileFormState) -> ProfileFormState) {
        dirty = true
        form.update(transform)
    }

    fun computedTarget(): Double? =
        (parseProfileForm(form.value) as? ParsedProfile.Valid)?.profile?.let {
            EnergyCalculator.tdee(it)
        }

    /** Effective daily target: manual override if set, otherwise computed goal. */
    fun effectiveTarget(): Double? =
        (parseProfileForm(form.value) as? ParsedProfile.Valid)?.profile?.let {
            EnergyCalculator.dailyKcalTarget(it)
        }

    fun save(onSaved: () -> Unit = {}) {
        when (val parsed = parseProfileForm(form.value)) {
            is ParsedProfile.Invalid -> form.update { it.copy(fieldErrors = parsed.errors) }
            is ParsedProfile.Valid -> viewModelScope.launch {
                profileRepository.save(parsed.profile)
                settingsStore.setOnboardingDone(true)
                dirty = false
                form.update { it.copy(savedOnce = true, fieldErrors = emptyMap()) }
                onSaved()
            }
        }
    }

    private fun trimZero(value: Double): String =
        if (value == Math.round(value).toDouble()) Math.round(value).toString() else value.toString()
}
