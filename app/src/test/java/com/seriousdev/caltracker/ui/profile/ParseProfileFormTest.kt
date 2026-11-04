package com.seriousdev.caltracker.ui.profile

import com.google.common.truth.Truth.assertThat
import com.seriousdev.caltracker.domain.ActivityLevel
import com.seriousdev.caltracker.domain.Goal
import com.seriousdev.caltracker.domain.Sex
import org.junit.Test

class ParseProfileFormTest {

    private fun state(
        age: String = "30",
        height: String = "180",
        weight: String = "80",
        manual: String = "",
        goalMode: GoalMode = GoalMode.AUTO,
        protein: String = "",
        fat: String = "",
        carbs: String = "",
    ) = ProfileFormState(
        loading = false,
        sex = Sex.MALE,
        ageText = age,
        heightText = height,
        weightText = weight,
        activity = ActivityLevel.MODERATE,
        goal = Goal.MAINTAIN,
        goalMode = goalMode,
        manualKcalText = manual,
        targetProteinText = protein,
        targetFatText = fat,
        targetCarbsText = carbs,
    )

    private fun valid(parsed: ParsedProfile) = (parsed as ParsedProfile.Valid).profile

    @Test
    fun `valid form yields profile`() {
        val profile = valid(parseProfileForm(state()))
        assertThat(profile.ageYears).isEqualTo(30)
        assertThat(profile.heightCm).isEqualTo(180.0)
        assertThat(profile.manualKcalTarget).isNull()
        assertThat(profile.macroTargets).isNull()
    }

    @Test
    fun `comma decimal separator is accepted`() {
        assertThat(parseProfileForm(state(height = "170,5", weight = "65,2")))
            .isInstanceOf(ParsedProfile.Valid::class.java)
    }

    @Test
    fun `age out of range is rejected`() {
        val parsed = parseProfileForm(state(age = "5"))
        assertThat(parsed).isInstanceOf(ParsedProfile.Invalid::class.java)
        assertThat((parsed as ParsedProfile.Invalid).errors.keys).contains(ProfileField.AGE)
    }

    @Test
    fun `non numeric height is rejected`() {
        val parsed = parseProfileForm(state(height = "метр восемьдесят"))
        assertThat((parsed as ParsedProfile.Invalid).errors[ProfileField.HEIGHT]).isNotNull()
    }

    @Test
    fun `weight at boundaries accepted, outside rejected`() {
        assertThat(parseProfileForm(state(weight = "2"))).isInstanceOf(ParsedProfile.Valid::class.java)
        assertThat(parseProfileForm(state(weight = "500"))).isInstanceOf(ParsedProfile.Valid::class.java)
        assertThat(parseProfileForm(state(weight = "1.9"))).isInstanceOf(ParsedProfile.Invalid::class.java)
        assertThat(parseProfileForm(state(weight = "501"))).isInstanceOf(ParsedProfile.Invalid::class.java)
    }

    @Test
    fun `auto mode ignores stale manual text`() {
        val profile = valid(parseProfileForm(state(goalMode = GoalMode.AUTO, manual = "1850")))
        assertThat(profile.manualKcalTarget).isNull()
    }

    @Test
    fun `manual kcal override parsed`() {
        val profile = valid(parseProfileForm(state(goalMode = GoalMode.KCAL, manual = "1850")))
        assertThat(profile.manualKcalTarget).isEqualTo(1850.0)
    }

    @Test
    fun `manual kcal out of range rejected`() {
        val parsed = parseProfileForm(state(goalMode = GoalMode.KCAL, manual = "100"))
        assertThat((parsed as ParsedProfile.Invalid).errors.keys).contains(ProfileField.MANUAL_KCAL)
    }

    @Test
    fun `kcal mode requires a value`() {
        val parsed = parseProfileForm(state(goalMode = GoalMode.KCAL, manual = ""))
        assertThat((parsed as ParsedProfile.Invalid).errors.keys).contains(ProfileField.MANUAL_KCAL)
    }

    @Test
    fun `macro targets parsed with comma separator`() {
        val profile = valid(
            parseProfileForm(state(goalMode = GoalMode.MACROS, protein = "120", fat = "70", carbs = "250,5")),
        )
        assertThat(profile.macroTargets?.proteinG).isEqualTo(120.0)
        assertThat(profile.macroTargets?.fatG).isEqualTo(70.0)
        assertThat(profile.macroTargets?.carbsG).isEqualTo(250.5)
        assertThat(profile.manualKcalTarget).isNull()
    }

    @Test
    fun `macro mode rejects missing and out-of-range targets`() {
        val missing = parseProfileForm(state(goalMode = GoalMode.MACROS, protein = "120", fat = "70"))
        assertThat((missing as ParsedProfile.Invalid).errors.keys).contains(ProfileField.TARGET_CARBS)

        val outOfRange = parseProfileForm(
            state(goalMode = GoalMode.MACROS, protein = "20", fat = "70", carbs = "250"),
        )
        assertThat((outOfRange as ParsedProfile.Invalid).errors.keys).contains(ProfileField.TARGET_PROTEIN)
    }
}
