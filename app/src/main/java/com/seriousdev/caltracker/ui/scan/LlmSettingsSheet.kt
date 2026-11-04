package com.seriousdev.caltracker.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.seriousdev.caltracker.LocalAppContainer
import kotlinx.coroutines.launch

/** Bottom sheet with the three LLM endpoint fields, backed by DataStore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsSheet(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by container.llmSettings.settings.collectAsState(initial = null)
    var baseUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var apiKey by rememberSaveable { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        settings?.let { s ->
            if (baseUrl == null && apiKey == null && model == null) {
                baseUrl = s.baseUrl
                apiKey = s.apiKey
                model = s.model
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Сервис распознавания (ИИ)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Любой OpenAI-совместимый vision API: OpenAI, OpenRouter, свой llama.cpp/Ollama. " +
                    "Ключ хранится только на устройстве.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = baseUrl.orEmpty(),
                onValueChange = { baseUrl = it },
                label = { Text("Адрес API (https://…/v1)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.orEmpty(),
                onValueChange = { model = it },
                label = { Text("Модель (vision)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey.orEmpty(),
                onValueChange = { apiKey = it },
                label = { Text("API-ключ (если нужен)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        container.llmSettings.saveSettings(
                            baseUrl = baseUrl.orEmpty(),
                            apiKey = apiKey.orEmpty(),
                            model = model.orEmpty(),
                        )
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
        }
    }
}
