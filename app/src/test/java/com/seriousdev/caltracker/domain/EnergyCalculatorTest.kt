package com.seriousdev.caltracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnergyCalculatorTest {

    private fun profile(
        sex: Sex = Sex.MALE,
        age: Int = 30,
        height: Double = 180.0,
        weight: Double = 80.0,
        activity: ActivityLevel = ActivityLevel.MODERATE,
        goal: Goal = Goal.MAINTAIN,
        manual: Double? = null,
    ) = Profile(sex, age, height, weight, activity, goal, manual)

    @Test
    fun `bmr male matches mifflin-st jeor`() {
        // 10*80 + 6.25*180 - 5*30 + 5 = 1780
        assertThat(EnergyCalculator.bmr(profile())).isWithin(1e-9).of(1780.0)
    }

    @Test
    fun `bmr female matches mifflin-st jeor`() {
        // 10*60 + 6.25*165 - 5*25 - 161 = 1345.25
        assertThat(
            EnergyCalculator.bmr(profile(sex = Sex.FEMALE, age = 25, height = 165.0, weight = 60.0)),
        ).isWithin(1e-9).of(1345.25)
    }

    @Test
    fun `tdee applies activity coefficient`() {
        assertThat(EnergyCalculator.tdee(profile()))
            .isWithin(1e-9).of(1780.0 * 1.55)
    }

    @Test
    fun `maintain target equals tdee`() {
        assertThat(EnergyCalculator.dailyKcalTarget(profile()))
            .isWithin(1e-9).of(1780.0 * 1.55)
    }

    @Test
    fun `lose target is 85 percent of tdee`() {
        assertThat(EnergyCalculator.dailyKcalTarget(profile(goal = Goal.LOSE)))
            .isWithin(1e-9).of(1780.0 * 1.55 * 0.85)
    }

    @Test
    fun `gain target is 110 percent of tdee`() {
        assertThat(EnergyCalculator.dailyKcalTarget(profile(goal = Goal.GAIN)))
            .isWithin(1e-9).of(1780.0 * 1.55 * 1.10)
    }

    @Test
    fun `manual target overrides computed`() {
        assertThat(EnergyCalculator.dailyKcalTarget(profile(manual = 2000.0)))
            .isWithin(1e-9).of(2000.0)
    }
}

class GoalsCalculatorTest {

    private val target = 2000.0

    @Test
    fun `macro grams match energy shares`() {
        val goals = GoalsCalculator.macrosFor(target)
        assertThat(goals.protein * 4.0).isWithin(1e-9).of(target * GoalsCalculator.PROTEIN_SHARE)
        assertThat(goals.fat * 9.0).isWithin(1e-9).of(target * GoalsCalculator.FAT_SHARE)
        assertThat(goals.carbs * 4.0).isWithin(1e-9).of(target * GoalsCalculator.CARBS_SHARE)
    }

    @Test
    fun `shares sum to whole energy`() {
        assertThat(GoalsCalculator.PROTEIN_SHARE + GoalsCalculator.FAT_SHARE + GoalsCalculator.CARBS_SHARE)
            .isWithin(1e-9).of(1.0)
    }

    @Test
    fun `daily goals use profile kcal target`() {
        val profile = Profile(Sex.FEMALE, 25, 165.0, 60.0, ActivityLevel.SEDENTARY, Goal.MAINTAIN)
        assertThat(GoalsCalculator.dailyGoals(profile).kcal)
            .isWithin(1e-9).of(EnergyCalculator.dailyKcalTarget(profile))
    }

    @Test
    fun `manual macro targets win and derive kcal`() {
        val profile = Profile(
            Sex.MALE, 30, 180.0, 80.0, ActivityLevel.LIGHT, Goal.MAINTAIN,
            macroTargets = MacroTargets(proteinG = 120.0, fatG = 70.0, carbsG = 250.0),
        )
        val goals = GoalsCalculator.dailyGoals(profile)
        assertThat(goals.protein).isWithin(1e-9).of(120.0)
        assertThat(goals.fat).isWithin(1e-9).of(70.0)
        assertThat(goals.carbs).isWithin(1e-9).of(250.0)
        // 4*120 + 9*70 + 4*250 = 480 + 630 + 1000 = 2110
        assertThat(goals.kcal).isWithin(1e-9).of(2110.0)
    }

    @Test
    fun `macro targets beat manual kcal target`() {
        val profile = Profile(
            Sex.MALE, 30, 180.0, 80.0, ActivityLevel.LIGHT, Goal.MAINTAIN,
            manualKcalTarget = 2500.0,
            macroTargets = MacroTargets(proteinG = 100.0, fatG = 60.0, carbsG = 200.0),
        )
        assertThat(EnergyCalculator.dailyKcalTarget(profile))
            .isWithin(1e-9).of(4.0 * 100 + 9.0 * 60 + 4.0 * 200)
    }
}
