package com.seriousdev.caltracker.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.ui.format.kcalText
import com.seriousdev.caltracker.ui.format.todayEpochDay

/** Weight log + weight/kcal charts for 30/90/365-day windows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val container = LocalAppContainer.current
    val vm: StatsViewModel = viewModel(factory = viewModelFactory {
        initializer { StatsViewModel(container.weights, container.diary, container.profile) }
    })
    val state by vm.state.collectAsState()
    val today = todayEpochDay()
    val fromDay = today - state.range.days + 1

    var weightText by remember(state.latestWeightKg) {
        mutableStateOf(state.latestWeightKg?.let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() } ?: "")
    }
    val weightValue = weightText.replace(',', '.').toDoubleOrNull()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Статистика") }) },
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatsRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { vm.selectRange(range) },
                        label = { Text(range.ruLabel) },
                    )
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Вес сегодня", style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = { weightText = it },
                            label = { Text("кг") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = weightValue != null && weightValue !in 2.0..500.0,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { weightValue?.let(vm::recordWeight) },
                            enabled = weightValue != null && weightValue in 2.0..500.0,
                        ) { Text("Отметить") }
                    }
                    state.latestWeightKg?.let {
                        Text("Последнее взвешивание: ${it} кг", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Масса тела", style = MaterialTheme.typography.titleSmall)
                    if (state.weightPoints.size < 2) {
                        Text("Нужно минимум два взвешивания.", style = MaterialTheme.typography.bodyMedium)
                    }
                    LineChart(points = state.weightPoints, fromDay = fromDay, toDay = today)
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Калории по дням", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Среднее: ${state.averageKcal?.let { "${kcalText(it)} ккал" } ?: "—"} · " +
                            "Цель: ${state.goalKcal?.let { "${kcalText(it)} ккал" } ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    KcalBarChart(
                        dayKcal = state.dayKcal,
                        fromDay = fromDay,
                        toDay = today,
                        goalKcal = state.goalKcal,
                    )
                }
            }
        }
    }
}
