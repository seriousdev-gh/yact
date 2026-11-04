package com.seriousdev.caltracker.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.ExtractResult
import com.seriousdev.caltracker.data.LabelExtractor
import com.seriousdev.caltracker.data.LlmSettingsStore
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LabelScanUiState(
    val llmConfigured: Boolean = false,
    val llmEndpoint: String = "",
    val consentGranted: Boolean = false,
    /** Downscaled label photo ready for preview/analysis. */
    val capturedBitmap: Bitmap? = null,
    val jpegBase64: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Raw model/server reply shown under [error] so failures are diagnosable. */
    val errorDetail: String? = null,
    val done: ExtractResult.Extracted? = null,
)

/**
 * Holds the captured label photo and drives the one-shot LLM extraction.
 * Camera ownership stays in the composable; this class receives JPEG bytes.
 */
class LabelScanViewModel(
    private val extractor: LabelExtractor,
    private val llmSettings: LlmSettingsStore,
) : ViewModel() {

    private val ui = MutableStateFlow(LabelScanUiState())
    val state: StateFlow<LabelScanUiState> = ui

    init {
        viewModelScope.launch {
            llmSettings.settings.collect { s ->
                ui.update {
                    it.copy(
                        llmConfigured = s.isConfigured,
                        llmEndpoint = s.baseUrl.ifBlank { "сервис не настроен" },
                    )
                }
            }
        }
        viewModelScope.launch {
            llmSettings.consentGranted.collect { granted ->
                ui.update { it.copy(consentGranted = granted) }
            }
        }
    }

    /** [jpegBytes] straight from ImageCapture; downscaled so the payload stays sane. */
    fun onCaptured(jpegBytes: ByteArray) {
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: run {
            ui.update { it.copy(error = "Не удалось прочитать снимок.") }
            return
        }
        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > MAX_EDGE) {
            val ratio = MAX_EDGE.toDouble() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        ui.update { it.copy(capturedBitmap = scaled, jpegBase64 = b64, error = null, errorDetail = null, done = null) }
    }

    fun clearCapture() = ui.update {
        it.copy(capturedBitmap = null, jpegBase64 = null, error = null, errorDetail = null, done = null)
    }

    fun grantConsentAndRecognize() {
        viewModelScope.launch {
            llmSettings.grantConsent()
            recognize()
        }
    }

    fun recognize() {
        val dataUrl = ui.value.jpegBase64 ?: return
        ui.update { it.copy(busy = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            val result = extractor.extractLabel("data:image/jpeg;base64,$dataUrl")
            when (result) {
                is ExtractResult.Extracted -> ui.update {
                    it.copy(busy = false, done = result, error = null, errorDetail = null)
                }

                is ExtractResult.NoData -> ui.update {
                    it.copy(
                        busy = false,
                        error = "Модель не вернула данные КБЖУ (${result.detail ?: "ответ пуст"}). " +
                            "Можно переснять ближе к таблице КБЖУ или заполнить поля вручную.",
                        errorDetail = result.modelReply,
                    )
                }

                is ExtractResult.NetworkError -> ui.update {
                    it.copy(
                        busy = false,
                        error = "Сервер ИИ недоступен.",
                        errorDetail = result.cause.message?.take(400),
                    )
                }

                is ExtractResult.Invalid -> ui.update {
                    it.copy(
                        busy = false,
                        error = "Сервер вернул неожиданный формат ответа. Проверьте модель и адрес в настройках.",
                        errorDetail = result.modelReply ?: result.cause.message?.take(400),
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_EDGE = 1280
    }
}
