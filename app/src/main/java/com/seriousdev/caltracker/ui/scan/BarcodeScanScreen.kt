package com.seriousdev.caltracker.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.seriousdev.caltracker.LocalAppContainer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Full-screen barcode scanner: CameraX preview + ML Kit decoding.
 * A barcode that is already in the catalog hands off to the caller via
 * [onMatched] (open the meal dialog); anything else hands off a prefill for a
 * new-product form via [onOpenProductForm]. Manual code entry covers broken cameras.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.camera.core.ExperimentalGetImage
@Composable
fun BarcodeScanScreen(
    navId: Long,
    onDone: () -> Unit,
    onMatched: (Long) -> Unit,
    onOpenProductForm: (ScanPrefill) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: BarcodeScanViewModel = viewModel(key = "barcode-scan-$navId", factory = viewModelFactory {
        initializer { BarcodeScanViewModel(container.openFoodFacts, container.products) }
    })
    val state by vm.state.collectAsState()

    state.requestProductForm?.let { prefill ->
        LaunchedEffect(prefill) {
            vm.productFormOpened()
            onOpenProductForm(prefill)
        }
    }

    state.matchedProductId?.let { id ->
        LaunchedEffect(id) { onMatched(id) }
    }

    val context = LocalContext.current
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
                    Spacer(Modifier.height(8.dp))
                    Text("Ищем $code…", color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }

            state.statusText?.let { text ->
                Card(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Text(text, Modifier.padding(16.dp))
                }
            }

        }
    }
}

/** CameraX preview feeding ML Kit barcode analysis; pauses while [active] is false. */
@androidx.camera.core.ExperimentalGetImage
@Composable
internal fun CameraScanPreview(active: Boolean, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build(),
        )
    }
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
            cameraError = "Камера недоступна. Введите штрихкод вручную."
            null
        }
    }

    LaunchedEffect(provider, active) {
        val p = provider ?: return@LaunchedEffect
        imageAnalysis.clearAnalyzer()
        if (active) {
            imageAnalysis.setAnalyzer(
                ContextCompat.getMainExecutor(context),
                BarcodeImageAnalyzer(scanner, onBarcode),
            )
        }
        p.unbindAll()
        try {
            p.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider },
                imageAnalysis,
            )
        } catch (e: Exception) {
            cameraError = "Камера недоступна. Введите штрихкод вручную."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            imageAnalysis.clearAnalyzer()
            provider?.unbindAll()
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    cameraError?.let { text ->
        Text(
            text,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.CenterVertically).padding(32.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@androidx.camera.core.ExperimentalGetImage
private class BarcodeImageAnalyzer(
    private val scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    private val onBarcode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let(onBarcode)
            }
            .addOnCompleteListener { proxy.close() }
    }
}
