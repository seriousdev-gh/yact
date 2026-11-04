package com.seriousdev.caltracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.DiaryRepository
import com.seriousdev.caltracker.data.ProfileRepository
import com.seriousdev.caltracker.data.WeightRepository
import com.seriousdev.caltracker.domain.EnergyCalculator
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

enum class StatsRange(val days: Int, val ruLabel: String) {
    MONTH(30, "30 дней"),
    QUARTER(90, "90 дней"),
    YEAR(365, "365 дней"),
}

data class StatsUiState(
    val range: StatsRange = StatsRange.MONTH,
    val latestWeightKg: Double? = null,
    val weightPoints: List<Pair<Long, Double>> = emptyList(),
    val dayKcal: List<Pair<Long, Double>> = emptyList(),
    val goalKcal: Double? = null,
) {
    val averageKcal: Double?
        get() = dayKcal.takeIf { it.isNotEmpty() }?.let { rows -> rows.map { it.second }.average() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val weights: WeightRepository,
    private val diary: DiaryRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.MONTH)
    val state: StateFlow<StatsUiState>

    init {
        val today = todayEpochDay()
        state = combine(
            range,
            profiles.observe(),
            range.flatMapLatest { r -> weights.observeRange(today - r.days + 1, today) },
            range.flatMapLatest { r -> diary.observeDayKcalRange(today - r.days + 1, today) },
        ) { rangeValue, profile, weightRows, kcalRows ->
            StatsUiState(
                range = rangeValue,
                latestWeightKg = weightRows.maxByOrNull { it.first }?.second,
                weightPoints = weightRows,
                dayKcal = kcalRows.map { it.dayEpochDay to it.kcal },
                goalKcal = profile?.let { EnergyCalculator.dailyKcalTarget(it) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
    }

    fun selectRange(value: StatsRange) {
        range.value = value
    }

    /** Records [weightKg] for today and syncs it into the profile. */
    fun recordWeight(weightKg: Double) {
        viewModelScope.launch {
            weights.record(todayEpochDay(), weightKg)
            profiles.current()?.let { profile ->
                profiles.save(profile.copy(weightKg = weightKg))
            }
        }
    }
}
