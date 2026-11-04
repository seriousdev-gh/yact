package com.seriousdev.caltracker.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedDataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val allowedCategories = setOf(
        "Злаки и крупы", "Хлеб и выпечка", "Мясо и птица", "Рыба и морепродукты",
        "Молочные продукты", "Яйца", "Овощи", "Фрукты и ягоды", "Фрукты сушёные",
        "Орехи и семечки", "Бобовые", "Сладости", "Напитки", "Соусы и жиры",
        "Готовые блюда", "Прочее",
    )

    @Test
    fun `seed asset passes sanity constraints`() {
        val seeds = SeedProducts.readAsset(context)

        assertThat(seeds.size).isAtLeast(500)

        val names = seeds.map { it.name.trim().lowercase() }
        assertThat(names).containsNoDuplicates()

        for (seed in seeds) {
            with(seed) {
                assertThat(name).isNotEmpty()
                assertThat(category).isIn(allowedCategories)
                assertThat(kcal.isNaN()).isFalse()
                assertThat(protein).isAtLeast(0.0)
                assertThat(fat).isAtLeast(0.0)
                assertThat(carbs).isAtLeast(0.0)
                assertThat(kcal).isGreaterThan(0.0)
                assertThat(kcal).isAtMost(900.0)
                val implied = 4.0 * protein + 9.0 * fat + 4.0 * carbs
                assertThat(Math.abs(kcal - implied)).isAtMost(0.20 * kcal)
            }
        }
    }
}
