package com.seriousdev.caltracker.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.data.OffProduct
import com.seriousdev.caltracker.data.OffResult
import com.seriousdev.caltracker.data.OpenFoodFactsClient
import com.seriousdev.caltracker.ui.catalog.PrefilledProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@androidx.camera.core.ExperimentalGetImage
@Composable
fun BarcodePickScreen(
    formNavId: Long,
    navId: Long,
    onDone: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: BarcodePickViewModel = viewModel(key = "barcode-pick-$navId", factory = viewModelFactory {
        initializer { BarcodePickViewModel(container.openFoodFacts) }
    })
    val state by vm.state.collectAsState()

    state.picked?.let { prefill ->
        LaunchedEffect(prefill) {
            container.formFills.put(formNavId, prefill)
            onDone()
        }
    }

    val context = LocalContext.current
    var hasCameraPermission by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var showManualEntry by rememberSaveable { mutableStateOf(false) }
    var manualCode by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Сканировать штрихкод") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showManualEntry = !showManualEntry }) {
                        Icon(Icons.Filled.Keyboard, contentDescription = "Ввести код вручную")
                    }
                },
            )
        },
    ) { insets ->
        Box(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
        ) {
            if (hasCameraPermission) {
                CameraScanPreview(
                    active = state.pendingBarcode == null,
                    onBarcode = vm::onBarcode,
                )
            } else {
                Text(
                    "Нет доступа к камере. Нажмите на клавиатуру вверху и введите код вручную.",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }

            if (showManualEntry) {
                Card(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = manualCode,
                            onValueChange = { v -> manualCode = v.filter { it.isDigit() } },
                            label = { Text("Штрихкод (EAN/UPC)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.onBarcode(manualCode); manualCode = ""; showManualEntry = false },
                            enabled = manualCode.length >= 8,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Найти") }
                    }
                }
            }

            state.pendingBarcode?.let { code ->
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Ищем $code…",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }

            state.statusText?.let { message ->
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .widthIn(max = 420.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, textAlign = TextAlign.Start)
                        TextButton(onClick = vm::dismissStatus, modifier = Modifier.align(Alignment.End)) {
                            Text("Понятно")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Looks a scanned code up in Open Food Facts and produces a form fill:
 * full data when OFF knows the product, just the barcode otherwise (including
 * network failures — the user can still type the values).
 */
class BarcodePickViewModel(private val off: OpenFoodFactsClient) : ViewModel() {

    data class PickState(
        val pendingBarcode: String? = null,
        val picked: PrefilledProduct? = null,
        val statusText: String? = null,
    )

    private val ui = MutableStateFlow(PickState())
    val state: StateFlow<PickState> = ui

    fun onBarcode(barcode: String) {
        if (ui.value.pendingBarcode != null || ui.value.picked != null) return
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return
        ui.update { it.copy(pendingBarcode = trimmed, statusText = null) }
        viewModelScope.launch {
            when (val result = off.fetch(trimmed)) {
                is OffResult.Found -> ui.update {
                    it.copy(pendingBarcode = null, picked = result.product.toFill())
                }

                OffResult.NotFound -> ui.update {
                    it.copy(pendingBarcode = null, picked = PrefilledProduct(name = "", kcal = null, protein = null, fat = null, carbs = null, barcode = trimmed))
                }

                is OffResult.NoNutrition -> ui.update {
                    it.copy(
                        pendingBarcode = null,
                        picked = PrefilledProduct(name = result.name, kcal = null, protein = null, fat = null, carbs = null, barcode = result.barcode),
                    )
                }

                is OffResult.NetworkError -> ui.update {
                    it.copy(
                        pendingBarcode = null,
                        statusText = "Нет связи с Open Food Facts — заполнил только штрихкод.",
                        picked = PrefilledProduct(name = "", kcal = null, protein = null, fat = null, carbs = null, barcode = trimmed),
                    )
                }

                is OffResult.Invalid -> ui.update {
                    it.copy(pendingBarcode = null, statusText = "Сервер вернул неожиданный ответ. Повторите скан.")
                }
            }
        }
    }

    fun dismissStatus() {
        ui.update { it.copy(statusText = null) }
    }

    private fun OffProduct.toFill() = PrefilledProduct(
        name = name,
        kcal = fmt(per100g.kcal),
        protein = fmt(per100g.protein),
        fat = fmt(per100g.fat),
        carbs = fmt(per100g.carbs),
        barcode = barcode,
    )

    private fun fmt(value: Double): String =
        if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)
}
