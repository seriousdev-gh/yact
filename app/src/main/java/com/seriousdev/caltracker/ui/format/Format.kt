package com.seriousdev.caltracker.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ruLocale = Locale("ru")
private val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMMM", ruLocale)

/** Whole-kcal display, e.g. `1840`. */
fun kcalText(value: Double): String = Math.round(value).toString()

/** Grams display, e.g. `150 г`. */
fun gramsText(value: Double): String = "${Math.round(value)} г"

/** Macro display line, e.g. `У 180 г · Б 45 г · Ж 60 г`. */
fun macrosShortText(protein: Double, fat: Double, carbs: Double): String =
    "У ${Math.round(carbs)} г · Б ${Math.round(protein)} г · Ж ${Math.round(fat)} г"

/** Localized day caption; "Сегодня"/"Вчера"/"Завтра" for adjacent days. */
fun dayLabel(dayEpochDay: Long): String {
    val today = LocalDate.now().toEpochDay()
    val relative = when (dayEpochDay - today) {
        0L -> "Сегодня"
        -1L -> "Вчера"
        1L -> "Завтра"
        else -> null
    }
    val date = LocalDate.ofEpochDay(dayEpochDay).format(dayFormatter)
    return if (relative != null) "$relative, $date" else date.replaceFirstChar { it.titlecase(ruLocale) }
}

fun LocalDate.toEpochDayLong(): Long = toEpochDay()

fun epochDayToDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

fun instantToEpochDay(instantMs: Long): Long =
    Instant.ofEpochMilli(instantMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
