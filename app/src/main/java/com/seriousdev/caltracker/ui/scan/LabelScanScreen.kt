package com.seriousdev.caltracker.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seriousdev.caltracker.LocalAppContainer
import com.seriousdev.caltracker.ui.catalog.PrefilledProduct
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Photograph a nutrition label and have the user's configured vision LLM fill in
 * the product form. Nothing leaves the device until the user confirms the consent
 * dialog and taps "Распознать".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelScanScreen(navId: Long, onDone: () -> Unit, onRecognized: (PrefilledProduct) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: LabelScanViewModel = viewModel(key = "label-scan-$navId", factory = viewModelFactory {
        initializer { LabelScanViewModel(container.labelExtractor, container.llmSettings) }
    })
    val state by vm.state.collectAsState()

    var hasCameraPermission by remember {
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

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showConsent by rememberSaveable { mutableStateOf(false) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    LaunchedEffect(state.done) {
        state.done?.let { extracted ->
            val info = extracted.info
            onRecognized(
                PrefilledProduct(
                    name = info.name,
                    kcal = fmt(info.per100g.kcal),
                    protein = fmt(info.per100g.protein),
                    fat = fmt(info.per100g.fat),
                    carbs = fmt(info.per100g.carbs),
                ),
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Распознать этикетку") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки ИИ")
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
            val captured = state.capturedBitmap
            if (captured != null) {
                Image(
                    bitmap = captured.asImageBitmap(),
                    contentDescription = "Снятая этикетка",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            } else if (hasCameraPermission) {
                CameraLabelPreview(active = !state.busy, imageCapture = imageCapture)
            } else {
                Text(
                    "Нет доступа к камере — распознавание недоступно.",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!state.llmConfigured) {
                    Text(
                        "Для распознавания настройте модель ИИ (OpenAI-совместимый vision API).",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { showSettings = true }) { Text("Настроить ИИ") }
                }
                if (state.busy) {
                    CircularProgressIndicator()
                    Text("Модель читает этикетку…", color = MaterialTheme.colorScheme.inverseOnSurface)
                }
                state.error?.let { message ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(message)
                            state.errorDetail?.let { detail ->
                                Text(
                                    "Ответ модели:",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 160.dp)
                                            .verticalScroll(rememberScrollState()),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (captured == null) {
                        Button(
                            onClick = {
                                imageCapture.takePicture(
                                    mainExecutor,
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bytes = try {
                                                image.planes.firstOrNull()?.buffer?.let { buf ->
                                                    val arr = ByteArray(buf.remaining())
                                                    buf.get(arr)
                                                    arr
                                                }
                                            } finally {
                                                image.close()
                                            }
                                            if (bytes != null) vm.onCaptured(bytes)
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            // Error card surfaces on the next busy=false recomposition.
                                        }
                                    },
                                )
                            },
                            enabled = hasCameraPermission && !state.busy && state.llmConfigured,
                        ) {
                            Icon(Icons.Filled.Camera, contentDescription = null)
                            Text(" Снять этикетку")
                        }
                    } else {
                        OutlinedButton(onClick = { vm.clearCapture() }, enabled = !state.busy) {
                            Text("Переснять")
                        }
                        Button(
                            onClick = {
                                if (state.consentGranted) vm.recognize() else showConsent = true
                            },
                            enabled = !state.busy && state.llmConfigured,
                        ) { Text("Распознать") }
                    }
                }
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Фото уйдёт в интернет") },
            text = {
                Text(
                    "Снимок этикетки будет отправлен в сервис, который вы указали в настройках ИИ " +
                        "(${state.llmEndpoint}). Приложение не хранит фото. Продолжить?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConsent = false
                        vm.grantConsentAndRecognize()
                    },
                ) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("Отмена") }
            },
        )
    }

    if (showSettings) {
        LlmSettingsSheet(onDismiss = { showSettings = false })
    }
}

private fun fmt(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)

/** Simple CameraX preview + one-shot capture for the label photo. */
@Composable
private fun CameraLabelPreview(active: Boolean, imageCapture: ImageCapture) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        provider = try {
            suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    {
                        try {
                            cont.resume(future.get())
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    },
                    ContextCompat.getMainExecutor(context),
                )
            }
        } catch (e: Exception) {
            cameraError = "Камера недоступна."
            null
        }
    }

    LaunchedEffect(provider) {
        val p = provider ?: return@LaunchedEffect
        p.unbindAll()
        try {
            p.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider },
                imageCapture,
            )
        } catch (e: Exception) {
            cameraError = "Камера недоступна."
        }
    }

    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    cameraError?.let { text ->
        Text(
            text,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.fillMaxSize().padding(32.dp),
            textAlign = TextAlign.Center,
        )
    }
}
