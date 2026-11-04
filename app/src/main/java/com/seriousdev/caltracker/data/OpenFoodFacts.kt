package com.seriousdev.caltracker.data

import com.seriousdev.caltracker.domain.Macros
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** A product as needed by the catalog, resolved from Open Food Facts. */
data class OffProduct(
    val name: String,
    val brand: String?,
    val category: String,
    val per100g: Macros,
    val barcode: String,
)

sealed class OffResult {
    data class Found(val product: OffProduct) : OffResult()
    data object NotFound : OffResult()
    /** Product exists in OFF but carries no usable nutrition facts. */
    data class NoNutrition(val name: String, val barcode: String) : OffResult()
    data class NetworkError(val cause: Exception) : OffResult()
    data class Invalid(val cause: Exception) : OffResult()
}

/**
 * Minimal Open Food Facts product lookup (barcode -> nutrition).
 * One request per user action; the User-Agent is the OFF API etiquette.
 */
class OpenFoodFactsClient(
    private val baseUrl: String = "https://world.openfoodfacts.org",
    userAgent: String = "CalTracker/2.0 (Android; contact: borisdrovnin@gmail.com)",
    callFactory: Call.Factory? = null,
) {

    /**
     * Dev-only: this machine's network blocks direct TLS to some hosts, so debug
     * builds route through the host HTTP proxy via `adb reverse tcp:2081 tcp:2081`
     * (.tmp/fwd2081.ps1 forwards 0.0.0.0:2081 -> 127.0.0.1:2080 xray).
     */
    companion object {
        fun forBuild(debug: Boolean): OpenFoodFactsClient =
            if (debug) {
                OpenFoodFactsClient(
                    callFactory = OkHttpClient.Builder()
                        .proxy(
                            java.net.Proxy(
                                java.net.Proxy.Type.HTTP,
                                java.net.InetSocketAddress("127.0.0.1", 2081),
                            ),
                        )
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build(),
                )
            } else {
                OpenFoodFactsClient()
            }
    }

    private val client: Call.Factory = callFactory ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val userAgentValue = userAgent

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class OffResponse(
        val status: Int? = null,
        val product: OffProductDto? = null,
    )

    @Serializable
    private data class OffProductDto(
        @SerialName("code") val code: String? = null,
        @SerialName("product_name") val productName: String? = null,
        @SerialName("product_name_ru") val productNameRu: String? = null,
        @SerialName("generic_name") val genericName: String? = null,
        val brands: String? = null,
        @SerialName("categories_tags") val categoriesTags: List<String> = emptyList(),
        val nutriments: OffNutrimentsDto? = null,
    )

    @Serializable
    private data class OffNutrimentsDto(
        @SerialName("energy-kcal_100g") val energyKcal100g: Double? = null,
        @SerialName("energy-kj_100g") val energyKj100g: Double? = null,
        @SerialName("proteins_100g") val proteins100g: Double? = null,
        @SerialName("fat_100g") val fat100g: Double? = null,
        @SerialName("carbohydrates_100g") val carbohydrates100g: Double? = null,
    )

    suspend fun fetch(barcode: String): OffResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v2/product/${barcode.urlSegment()}.json")
            .header("User-Agent", userAgentValue)
            .build()
        val (code, body) = try {
            client.newCall(request).awaitResponse()
        } catch (e: IOException) {
            return@withContext OffResult.NetworkError(e)
        }
        if (code == 404) return@withContext OffResult.NotFound

        val parsed = try {
            json.decodeFromString(OffResponse.serializer(), body)
        } catch (e: Exception) {
            return@withContext OffResult.Invalid(e)
        }

        val dto = parsed.product
        if (parsed.status != 1 || dto == null) return@withContext OffResult.NotFound

        val name = listOfNotNull(dto.productNameRu, dto.productName, dto.genericName)
            .firstOrNull { it.isNotBlank() }
            ?: "Продукт ${dto.code ?: barcode}"

        val n = dto.nutriments
        val kcal = n?.energyKcal100g ?: n?.energyKj100g?.let { it / 4.184 }
        if (kcal == null || kcal <= 0.0) {
            return@withContext OffResult.NoNutrition(name, dto.code ?: barcode)
        }

        OffResult.Found(
            OffProduct(
                name = name,
                brand = dto.brands?.takeIf { it.isNotBlank() }?.substringBefore(','),
                category = dto.categoriesTags.lastOrNull()?.substringAfterLast(':')
                    ?.replace('_', ' ')?.replace('-', ' ')?.ifBlank { null } ?: "Импорт",
                per100g = Macros(
                    kcal = kcal,
                    protein = n?.proteins100g ?: 0.0,
                    fat = n?.fat100g ?: 0.0,
                    carbs = n?.carbohydrates100g ?: 0.0,
                ),
                barcode = dto.code ?: barcode,
            ),
        )
    }

    private fun String.urlSegment(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private suspend fun Call.awaitResponse(): Pair<Int, String> = suspendCoroutine { cont ->
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
}
