package com.seriousdev.caltracker.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenFoodFactsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenFoodFactsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenFoodFactsClient(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val body = """
        {
          "status": 1,
          "product": {
            "code": "3017620422003",
            "product_name": "Nutella",
            "product_name_ru": "Нутелла",
            "brands": "Ferrero,Other",
            "categories_tags": ["en:spread", "en:chocolate-spreads"],
            "nutriments": {
              "energy-kcal_100g": 539.0,
              "proteins_100g": 6.8,
              "fat_100g": 30.9,
              "carbohydrates_100g": 57.5
            }
          }
        }
    """.trimIndent()

    @Test
    fun `maps full product with ru name and macros`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        val result = client.fetch("3017620422003")
        assertThat(result).isInstanceOf(OffResult.Found::class.java)
        val p = (result as OffResult.Found).product
        assertThat(p.name).isEqualTo("Нутелла")
        assertThat(p.brand).isEqualTo("Ferrero")
        assertThat(p.category).isEqualTo("chocolate spreads")
        assertThat(p.per100g.kcal).isWithin(1e-9).of(539.0)
        assertThat(p.per100g.protein).isWithin(1e-9).of(6.8)
        assertThat(p.barcode).isEqualTo("3017620422003")

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v2/product/3017620422003.json")
        assertThat(recorded.getHeader("User-Agent")).startsWith("CalTracker/")
    }

    @Test
    fun `kJ only products are converted to kcal`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"status":1,"product":{"code":"1","generic_name":"Йогурт",
                   "nutriments":{"energy-kj_100g":376.0,"proteins_100g":5.0,
                   "fat_100g":3.0,"carbohydrates_100g":7.0}}}""",
            ),
        )
        val p = (client.fetch("1") as OffResult.Found).product
        assertThat(p.name).isEqualTo("Йогурт")
        assertThat(p.per100g.kcal).isWithin(0.01).of(376.0 / 4.184)
    }

    @Test
    fun `missing product yields NotFound`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        assertThat(client.fetch("000")).isEqualTo(OffResult.NotFound)

        server.enqueue(MockResponse().setBody("""{"status":0}"""))
        assertThat(client.fetch("000")).isEqualTo(OffResult.NotFound)
    }

    @Test
    fun `product without energy yields NoNutrition`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"status":1,"product":{"code":"7","product_name":"Чай"}}"""),
        )
        val result = client.fetch("7")
        assertThat(result).isInstanceOf(OffResult.NoNutrition::class.java)
        assertThat((result as OffResult.NoNutrition).name).isEqualTo("Чай")
    }

    @Test
    fun `broken json yields Invalid`() = runTest {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertThat(client.fetch("1")).isInstanceOf(OffResult.Invalid::class.java)
    }
}
