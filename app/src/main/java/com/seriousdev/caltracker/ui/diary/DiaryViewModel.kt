package com.seriousdev.caltracker.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.DiaryRepository
import com.seriousdev.caltracker.data.ProfileRepository
import com.seriousdev.caltracker.domain.DiaryTotals
import com.seriousdev.caltracker.domain.GoalsCalculator
import com.seriousdev.caltracker.domain.Macros
import com.seriousdev.caltracker.domain.MealEntry
import com.seriousdev.caltracker.domain.MealType
import com.seriousdev.caltracker.domain.Product
import com.seriousdev.caltracker.ui.format.dayLabel
import com.seriousdev.caltracker.ui.format.todayEpochDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiaryItem(
    val entry: MealEntry,
    val product: Product,
    val macros: Macros,
)

data class MealGroup(
    val type: MealType,
    val items: List<DiaryItem>,
    val subtotal: Macros,
)

data class DiaryUiState(
    val dayEpochDay: Long,
    val dayLabel: String,
    val isToday: Boolean,
    val groups: List<MealGroup>,
    val totals: Macros,
    val goals: Macros?,
    val isEmpty: Boolean,
    /** Meal types that have entries on the previous day (copyable). */
    val yesterdayMealTypes: Set<MealType> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val diaryRepository: DiaryRepository,
    profileRepository: ProfileRepository,
    initialDayEpochDay: Long = todayEpochDay(),
) : ViewModel() {

    private val day = MutableStateFlow(initialDayEpochDay)

    private val goals = profileRepository.observe()
        .map { profile -> profile?.let { GoalsCalculator.dailyGoals(it) } }

    val state: StateFlow<DiaryUiState> = day
        .flatMapLatest { dayValue ->
            combine(
                diaryRepository.observeWithProducts(dayValue),
                goals,
                diaryRepository.observeForDay(dayValue - 1),
            ) { rows, goalsValue, yesterdayRows ->
                val items = rows.map { (entry, product) ->
                    DiaryItem(entry, product, product.forGrams(entry.grams))
                }
                val groups = MealType.entries.mapNotNull { type ->
                    val ofTyped = items.filter { it.entry.mealType == type }
                    if (ofTyped.isEmpty()) {
                        null
                    } else {
                        MealGroup(
                            type = type,
                            items = ofTyped,
                            subtotal = ofTyped.map { it.macros }
                                .fold(Macros.ZERO) { acc, m -> acc + m },
                        )
                    }
                }
                DiaryUiState(
                    dayEpochDay = dayValue,
                    dayLabel = dayLabel(dayValue),
                    isToday = dayValue == todayEpochDay(),
                    groups = groups,
                    totals = DiaryTotals.sum(items.map { it.entry }, items.map { it.product }),
                    goals = goalsValue,
                    isEmpty = items.isEmpty(),
                    yesterdayMealTypes = yesterdayRows.map { it.mealType }.toSet(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialTodayState(initialDayEpochDay))

    private fun initialTodayState(dayValue: Long) = DiaryUiState(
        dayEpochDay = dayValue,
        dayLabel = dayLabel(dayValue),
        isToday = dayValue == todayEpochDay(),
        groups = emptyList(),
        totals = Macros.ZERO,
        goals = null,
        isEmpty = true,
    )

    fun previousDay() = day.let { it.value = it.value - 1 }

    fun nextDay() {
        if (day.value < todayEpochDay()) day.let { it.value = it.value + 1 }
    }

    fun deleteEntry(entryId: Long) {
        viewModelScope.launch { diaryRepository.delete(entryId) }
    }

    /** Copies yesterday's entries of [type] into the viewed day as new records. */
    fun copyMealFromYesterday(type: MealType) {
        viewModelScope.launch {
            val source = diaryRepository.entriesForMeal(day.value - 1, type)
            source.forEach { diaryRepository.add(day.value, type, it.productId, it.grams) }
        }
    }

    fun updateEntry(entryId: Long, grams: Double, mealType: MealType) {
        viewModelScope.launch {
            diaryRepository.updateGrams(entryId, grams)
            diaryRepository.updateMealType(entryId, mealType)
        }
    }
}
