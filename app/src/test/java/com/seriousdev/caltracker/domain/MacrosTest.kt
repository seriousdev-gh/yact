package com.seriousdev.caltracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MacrosTest {

    @Test
    fun `plus adds componentwise`() {
        val a = Macros(100.0, 5.0, 3.0, 12.0)
        val b = Macros(50.0, 2.5, 1.5, 6.0)
        assertThat(a + b).isEqualTo(Macros(150.0, 7.5, 4.5, 18.0))
    }

    @Test
    fun `times scales all fields`() {
        val a = Macros(100.0, 5.0, 3.0, 12.0)
        assertThat(a * 1.5).isEqualTo(Macros(150.0, 7.5, 4.5, 18.0))
    }

    @Test
    fun `zero is neutral for plus`() {
        val a = Macros(100.0, 5.0, 3.0, 12.0)
        assertThat(a + Macros.ZERO).isEqualTo(a)
    }
}

class ProductTest {

    private val buckwheat = Product(
        id = 1,
        name = "Гречка",
        category = "Злаки и крупы",
        per100g = Macros(kcal = 343.0, protein = 12.0, fat = 3.4, carbs = 71.5),
    )

    @Test
    fun `forGrams scales per-100 values`() {
        assertThat(buckwheat.forGrams(150.0).kcal).isWithin(1e-9).of(343.0 * 1.5)
        assertThat(buckwheat.forGrams(50.0).protein).isWithin(1e-9).of(6.0)
    }

    @Test
    fun `forGrams zero grams is zero`() {
        assertThat(buckwheat.forGrams(0.0)).isEqualTo(Macros.ZERO)
    }
}

class DiaryTotalsTest {

    private val rice = Product(1, "Рис", "Злаки и крупы", Macros(349.0, 6.7, 0.7, 78.9))
    private val egg = Product(2, "Яйцо куриное", "Яйца", Macros(157.0, 12.7, 10.6, 1.1))

    @Test
    fun `sum adds scaled products`() {
        val entries = listOf(
            MealEntry(1, 20000, MealType.BREAKFAST, productId = 1, grams = 200.0),
            MealEntry(2, 20000, MealType.BREAKFAST, productId = 2, grams = 100.0),
        )
        val total = DiaryTotals.sum(entries, listOf(rice, egg))
        assertThat(total.kcal).isWithin(1e-9).of(349.0 * 2.0 + 157.0)
        assertThat(total.protein).isWithin(1e-9).of(6.7 * 2.0 + 12.7)
        assertThat(total.fat).isWithin(1e-9).of(0.7 * 2.0 + 10.6)
        assertThat(total.carbs).isWithin(1e-9).of(78.9 * 2.0 + 1.1)
    }

    @Test
    fun `sum of no entries is zero`() {
        assertThat(DiaryTotals.sum(emptyList(), listOf(rice))).isEqualTo(Macros.ZERO)
    }

    @Test(expected = NoSuchElementException::class)
    fun `sum fails fast on unknown product`() {
        DiaryTotals.sum(
            listOf(MealEntry(1, 20000, MealType.LUNCH, productId = 99, grams = 100.0)),
            listOf(rice),
        )
    }
}
