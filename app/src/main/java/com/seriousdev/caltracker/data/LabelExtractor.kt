package com.seriousdev.caltracker.data

import com.seriousdev.caltracker.domain.Macros
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Nutrition facts read off a product label photo. */
data class LabelInfo(
    val name: String,
    val per100g: Macros,
)

sealed class ExtractResult {
    data class Extracted(val info: LabelInfo) : ExtractResult()
    /** The model answered but no usable nutrition numbers were present. */
    data class NoData(val detail: String?, val modelReply: String? = null) : ExtractResult()
    data class NetworkError(val cause: Exception) : ExtractResult()
    data class Invalid(val cause: Exception, val modelReply: String? = null) : ExtractResult()
}

/**
 * Extracts per-100 g nutrition facts from a label photo using a user-configured
 * OpenAI-compatible chat completions endpoint (OpenAI, OpenRouter, llama.cpp
 * server, Ollama /v1, …). The JPEG is sent inline as a data URL.
 */
class LabelExtractor(
    private val settings: suspend () -> LlmSettings,
    callFactory: Call.Factory? = null,
) {

    private val client: Call.Factory = callFactory ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** [jpegDataUrl] is a `data:image/jpeg;base64,…` URL of the (downscaled) label photo. */
    suspend fun extractLabel(jpegDataUrl: String): ExtractResult = withContext(Dispatchers.IO) {
        val cfg = settings()
        if (!cfg.isConfigured) {
            return@withContext ExtractResult.Invalid(IllegalStateException("LLM не настроен"))
        }

        val body = buildJsonObject {
            put("model", cfg.model)
            put("temperature", 0.0)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", PROMPT)
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", jpegDataUrl) }
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }.toString()

        val request = Request.Builder()
            .url(cfg.chatCompletionsUrl)
            .header("Content-Type", "application/json")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val (code, response) = try {
            client.newCall(request).awaitResponse()
        } catch (e: IOException) {
            return@withContext ExtractResult.NetworkError(e)
        }
        if (code !in 200..299) {
            return@withContext ExtractResult.NetworkError(IOException("Сервер вернул HTTP $code: ${response.take(200)}"))
        }

        val content = try {
            json.parseToJsonElement(response).chatMessageContent()
        } catch (e: Exception) {
            return@withContext ExtractResult.Invalid(e, modelReply = response.take(500))
        }
        if (content == null || content.isBlank()) {
            return@withContext ExtractResult.NoData("Пустой ответ модели", modelReply = response.take(500))
        }
        parseLabelJson(content)
    }

    /**
     * Accepts the JSON object the prompt demands, tolerating code fences and
     * surrounding chatter; numeric strings ("12,5 g") are normalized.
     * Failures carry the model's raw reply so the UI can show what came back.
     */
    private fun parseLabelJson(text: String): ExtractResult {
        val raw = text.take(800)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return ExtractResult.NoData("в ответе нет JSON-объекта", modelReply = raw)
        }
        val obj = try {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObjectOrNull()
        } catch (e: Exception) {
            return ExtractResult.NoData("JSON в ответе повреждён", modelReply = raw)
        } ?: return ExtractResult.NoData("в ответе нет JSON-объекта", modelReply = raw)

        val kcal = obj.numberOrNull("kcal") ?: obj.numberOrNull("energy_kcal")
        if (kcal == null || kcal <= 0.0) {
            return ExtractResult.NoData("нет значения kcal", modelReply = raw)
        }
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNullSafe()
            ?: "Продукт с этикетки"
        return ExtractResult.Extracted(
            LabelInfo(
                name = name,
                per100g = Macros(
                    kcal = kcal,
                    protein = obj.numberOrNull("protein") ?: obj.numberOrNull("proteins") ?: 0.0,
                    fat = obj.numberOrNull("fat") ?: obj.numberOrNull("fats") ?: 0.0,
                    carbs = obj.numberOrNull("carbs") ?: obj.numberOrNull("carbohydrates") ?: 0.0,
                ),
            ),
        )
    }

    private fun JsonObject.numberOrNull(key: String): Double? = when (val el = this[key]) {
        is JsonPrimitive -> el.doubleOrNull ?: el.contentOrNullSafe()?.replace(',', '.')?.toDoubleOrNull()
        else -> null
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content.takeIf { it.isNotBlank() } else content.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonElement.chatMessageContent(): String? {
        val choices = (this as? JsonObject)?.get("choices") as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        return when (val content = (first["message"] as? JsonObject)?.get("content")) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.filterIsInstance<JsonObject>()
                .mapNotNull { (it["text"] as? JsonPrimitive)?.content }
                .joinToString("\n")
            else -> null
        }
    }

    private suspend fun Call.awaitResponse(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { cont.resume(it.code to (it.body?.string() ?: "")) }
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        const val PROMPT =
            "Ты читаешь фотографию этикетки продуктового товара. Верни СТРОГО JSON-объект " +
                "без пояснений: {\"name\": \"название продукта\", \"kcal\": число, " +
                "\"protein\": число, \"fat\": число, \"carbs\": число}. " +
                "Все числа — пищевая ценность на 100 г продукта (ккал и граммы). " +
                "Если какое-то значение на этикетке отсутствует, верни 0. " +
                "Если на фото нет пищевой ценности, верни {\"name\": \"\", \"kcal\": 0, \"protein\": 0, \"fat\": 0, \"carbs\": 0}."
    }
}
