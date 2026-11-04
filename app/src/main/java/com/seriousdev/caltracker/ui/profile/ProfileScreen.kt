package com.seriousdev.caltracker.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.seriousdev.caltracker.CalTrackerApp
import com.seriousdev.caltracker.ui.scan.LlmSettingsSheet
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.data.ThemeMode
import com.seriousdev.caltracker.domain.ActivityLevel
import com.seriousdev.caltracker.domain.Goal
import com.seriousdev.caltracker.domain.Sex
import com.seriousdev.caltracker.ui.format.kcalText
import kotlinx.coroutines.launch

/**
 * Profile & daily-goals form. Used both as first-run onboarding ([onboarding])
 * and as the Profile tab.
 */
@Composable
fun ProfileScreen(onboarding: Boolean, onOnboardingDone: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val vm: ProfileViewModel = viewModel(factory = viewModelFactory {
        initializer { ProfileViewModel(container.profile, container.settingsStore) }
    })
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.reload() }

    ProfileScreenContent(onboarding = onboarding, state = state, vm = vm, onOnboardingDone = onOnboardingDone)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileScreenContent(
    onboarding: Boolean,
    state: ProfileFormState,
    vm: ProfileViewModel,
    onOnboardingDone: () -> Unit,
) {
    val targetPreview = if (state.loading) null else vm.effectiveTarget()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(if (onboarding) "Настройка профиля" else "Профиль") })
        },
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
            if (onboarding) {
                Text(
                    "YACT рассчитает дневную норму калорий и УБЖ. " +
                        "Все данные остаются на устройстве.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.sex == Sex.MALE,
                    onClick = { vm.onFormChange { s -> s.copy(sex = Sex.MALE) } },
                    label = { Text("Мужской") },
                )
                FilterChip(
                    selected = state.sex == Sex.FEMALE,
                    onClick = { vm.onFormChange { s -> s.copy(sex = Sex.FEMALE) } },
                    label = { Text("Женский") },
                )
            }

            NumberField(
                label = "Возраст",
                value = state.ageText,
                error = state.fieldErrors[ProfileField.AGE],
                onValueChange = { v -> vm.onFormChange { s -> s.copy(ageText = v) } },
            )
            NumberField(
                label = "Рост, см",
                value = state.heightText,
                error = state.fieldErrors[ProfileField.HEIGHT],
                onValueChange = { v -> vm.onFormChange { s -> s.copy(heightText = v) } },
            )
            NumberField(
                label = "Вес, кг",
                value = state.weightText,
                error = state.fieldErrors[ProfileField.WEIGHT],
                onValueChange = { v -> vm.onFormChange { s -> s.copy(weightText = v) } },
            )

            Text("Активность", style = MaterialTheme.typography.labelLarge)
            ActivityLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.activity == level,
                    onClick = { vm.onFormChange { s -> s.copy(activity = level) } },
                    label = { Text(level.ruLabel) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Цель", style = MaterialTheme.typography.labelLarge)
            Goal.entries.forEach { goal ->
                FilterChip(
                    selected = state.goal == goal,
                    onClick = { vm.onFormChange { s -> s.copy(goal = goal) } },
                    label = { Text(goal.ruLabel) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Дневная норма", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalModeChip("Авто (из профиля)", state.goalMode == GoalMode.AUTO) {
                    vm.onFormChange { s -> s.copy(goalMode = GoalMode.AUTO) }
                }
                GoalModeChip("Калории", state.goalMode == GoalMode.KCAL) {
                    vm.onFormChange { s -> s.copy(goalMode = GoalMode.KCAL) }
                }
                GoalModeChip("УБЖ", state.goalMode == GoalMode.MACROS) {
                    vm.onFormChange { s -> s.copy(goalMode = GoalMode.MACROS) }
                }
            }
            if (state.goalMode == GoalMode.KCAL) {
                NumberField(
                    label = "Цель, ккал",
                    value = state.manualKcalText,
                    error = state.fieldErrors[ProfileField.MANUAL_KCAL],
                    onValueChange = { v -> vm.onFormChange { s -> s.copy(manualKcalText = v) } },
                )
            }
            if (state.goalMode == GoalMode.MACROS) {
                NumberField(
                    label = "Углеводы, г (30–600)",
                    value = state.targetCarbsText,
                    error = state.fieldErrors[ProfileField.TARGET_CARBS],
                    onValueChange = { v -> vm.onFormChange { s -> s.copy(targetCarbsText = v) } },
                )
                NumberField(
                    label = "Белки, г (30–400)",
                    value = state.targetProteinText,
                    error = state.fieldErrors[ProfileField.TARGET_PROTEIN],
                    onValueChange = { v -> vm.onFormChange { s -> s.copy(targetProteinText = v) } },
                )
                NumberField(
                    label = "Жиры, г (20–300)",
                    value = state.targetFatText,
                    error = state.fieldErrors[ProfileField.TARGET_FAT],
                    onValueChange = { v -> vm.onFormChange { s -> s.copy(targetFatText = v) } },
                )
            }

            targetPreview?.let { target ->
                Text(
                    "Дневная норма: ${kcalText(target)} ккал",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Button(
                onClick = { vm.save(onOnboardingDone) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.savedOnce && !onboarding) "Сохранить изменения" else "Сохранить")
            }

            if (!onboarding) {
                ThemeSection()
                LlmAccessSection()
                BackupSection()
            }
        }
    }
}

/** Light/dark/system theme picker. */
@Composable
private fun ThemeSection() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val mode by container.settingsStore.themeModeFlow.collectAsState(
        initial = ThemeMode.SYSTEM,
    )

    Text("Тема", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(
            selected = mode == ThemeMode.SYSTEM,
            onClick = { scope.launch { container.settingsStore.setThemeMode(ThemeMode.SYSTEM) } },
            label = { Text("Системная") },
        )
        androidx.compose.material3.FilterChip(
            selected = mode == ThemeMode.LIGHT,
            onClick = { scope.launch { container.settingsStore.setThemeMode(ThemeMode.LIGHT) } },
            label = { Text("Светлая") },
        )
        androidx.compose.material3.FilterChip(
            selected = mode == ThemeMode.DARK,
            onClick = { scope.launch { container.settingsStore.setThemeMode(ThemeMode.DARK) } },
            label = { Text("Тёмная") },
        )
    }
}

/** Entry point to the label-recognition (LLM) endpoint settings. */
@Composable
private fun LlmAccessSection() {
    var showSheet by rememberSaveable { mutableStateOf(false) }

    Text("Сервис распознавания (ИИ)", style = MaterialTheme.typography.labelLarge)
    Text(
        "Адрес, модель и ключ OpenAI-совместимого vision API для распознавания этикеток. " +
            "Ключ хранится только на устройстве.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = { showSheet = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Настройки ИИ")
    }

    if (showSheet) {
        LlmSettingsSheet(onDismiss = { showSheet = false })
    }
}

/** Export/import of the whole database via the system file picker. */
@Composable
private fun BackupSection() {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            busy = true
            status = null
            scope.launch {
                try {
                    val result = container.backupManager.export(uri)
                    status = "Копия сохранена (${result.sizeBytes / 1024} КБ)"
                } catch (e: Exception) {
                    status = "Экспорт не удался: ${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    Text("Резервная копия", style = MaterialTheme.typography.labelLarge)
    Text(
        "Все данные приложения в одном файле: продукты, дневник, цели, вес.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                val date = java.time.LocalDate.now().toString()
                exportLauncher.launch("caltracker-backup-$date.db")
            },
            enabled = !busy,
        ) { Text("Экспорт") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = !busy,
        ) { Text("Импорт") }
    }
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Восстановить из копии?") },
            text = { Text("Импорт заменит все текущие данные. Текущая база будет сохранена в резервальный файл на устройстве.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        busy = true
                        status = null
                        scope.launch {
                            try {
                                container.backupManager.importAndStage(uri)
                                // The live DB file was swapped; old repositories point
                                // at a closed database. Rebuild the DI root - the
                                // activity recreates itself and everything rebinds.
                                (context.applicationContext as CalTrackerApp).rebuildContainer()
                            } catch (e: Exception) {
                                status = "Импорт не удался: ${e.message}"
                                busy = false
                            }
                        }
                    },
                ) { Text("Восстановить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun GoalModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
