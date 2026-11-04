package com.seriousdev.caltracker.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class LabelExtractorTest {

    private lateinit var server: MockWebServer

    private fun extractor() = LabelExtractor(
        settings = { LlmSettings(baseUrl = server.url("/v1").toString(), apiKey = "k1", model = "vision-mini") },
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun chatResponse(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":${quote(content)}}}]}""",
    )

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `sends vision message with auth and model`() = runTest {
        server.enqueue(chatResponse("""{"name":"Хлеб","kcal":250,"protein":8,"fat":3,"carbs":49}"""))
        val result = extractor().extractLabel("data:image/jpeg;base64,AAAA")
        assertThat(result).isInstanceOf(ExtractResult.Extracted::class.java)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/chat/completions")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer k1")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"model\":\"vision-mini\"")
        // Strict gateways (e.g. OpenRouter) reject string content parts:
        // every element of content[] must be an object.
        assertThat(body).contains("\"content\":[{\"type\":\"text\",\"text\":")
        assertThat(body).contains("\"type\":\"image_url\"")
        assertThat(body).contains("data:image/jpeg;base64,AAAA")
    }

    @Test
    fun `parses plain json reply`() = runTest {
        server.enqueue(chatResponse("""{"name":"Творог 5%","kcal":121,"protein":17.2,"fat":5.0,"carbs":1.8}"""))
        val info = (extractor().extractLabel("data:image/jpeg;base64,B") as ExtractResult.Extracted).info
        assertThat(info.name).isEqualTo("Творог 5%")
        assertThat(info.per100g.kcal).isWithin(1e-9).of(121.0)
        assertThat(info.per100g.protein).isWithin(1e-9).of(17.2)
        assertThat(info.per100g.carbs).isWithin(1e-9).of(1.8)
    }

    @Test
    fun `tolerates code fences and chatter`() = runTest {
        server.enqueue(
            chatResponse("Конечно!\n```json\n{\"name\":\"Кефир\",\"kcal\":40,\"protein\":3,\"fat\":1,\"carbs\":4}\n```"),
        )
        val info = (extractor().extractLabel("data:image/jpeg;base64,B") as ExtractResult.Extracted).info
        assertThat(info.name).isEqualTo("Кефир")
    }

    @Test
    fun `accepts numeric strings with comma decimal`() = runTest {
        server.enqueue(
            chatResponse("""{"name":"Йогурт","kcal":"58,7","protein":2.9,"fat":"1,2","carbs":"8,5"}"""),
        )
        val info = (extractor().extractLabel("data:image/jpeg;base64,B") as ExtractResult.Extracted).info
        assertThat(info.per100g.kcal).isWithin(1e-9).of(58.7)
        assertThat(info.per100g.fat).isWithin(1e-9).of(1.2)
    }

    @Test
    fun `zero kcal means no data`() = runTest {
        server.enqueue(chatResponse("""{"name":"","kcal":0,"protein":0,"fat":0,"carbs":0}"""))
        assertThat(extractor().extractLabel("data:image/jpeg;base64,B"))
            .isInstanceOf(ExtractResult.NoData::class.java)
    }

    @Test
    fun `junk reply means no data`() = runTest {
        server.enqueue(chatResponse("Я не вижу здесь этикетки."))
        assertThat(extractor().extractLabel("data:image/jpeg;base64,B"))
            .isInstanceOf(ExtractResult.NoData::class.java)
    }

    @Test
    fun `server error becomes network error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        assertThat(extractor().extractLabel("data:image/jpeg;base64,B"))
            .isInstanceOf(ExtractResult.NetworkError::class.java)
    }

    @Test
    fun `unconfigured settings are rejected before any request`() = runTest {
        val blank = LabelExtractor(settings = { LlmSettings() })
        assertThat(blank.extractLabel("data:image/jpeg;base64,B"))
            .isInstanceOf(ExtractResult.Invalid::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
