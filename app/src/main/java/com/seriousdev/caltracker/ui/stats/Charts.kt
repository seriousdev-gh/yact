package com.seriousdev.caltracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Line chart of `epochDay to value` points scaled to the window
 * [fromDay, toDay]. Points are connected in date order; missing days are skipped.
 */
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>,
    fromDay: Long,
    toDay: Long,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.second }
        val minY = values.min()
        val maxY = values.max()
        val spanY = (maxY - minY).let { if (it == 0.0) 1.0 else it }
        val spanX = (toDay - fromDay).coerceAtLeast(1).toDouble()
        val pad = 8.dp.toPx()

        fun pos(day: Long, value: Double): Offset = Offset(
            x = pad + ((day - fromDay) / spanX).toFloat() * (size.width - 2 * pad),
            y = size.height - pad - (((value - minY) / spanY).toFloat()) * (size.height - 2 * pad),
        )

        val path = Path()
        points.forEachIndexed { index, (day, value) ->
            val o = pos(day, value)
            if (index == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        points.forEach { (day, value) ->
            drawCircle(lineColor, radius = 3.dp.toPx(), center = pos(day, value))
        }
    }
}

/**
 * Daily kcal bars for the window with an optional dashed goal line.
 */
@Composable
fun KcalBarChart(
    dayKcal: List<Pair<Long, Double>>,
    fromDay: Long,
    toDay: Long,
    goalKcal: Double?,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.error
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        if (dayKcal.isEmpty()) return@Canvas
        val maxKcal = (dayKcal.maxOf { it.second } * 1.1).let { m -> goalKcal?.let { maxOf(m, it * 1.1) } ?: m }
        val pad = 8.dp.toPx()
        val days = (toDay - fromDay + 1).toInt()
        val slot = (size.width - 2 * pad) / days
        val barWidth = (slot * 0.6f).coerceAtMost(14.dp.toPx())

        dayKcal.forEach { (day, kcal) ->
            val index = (day - fromDay).toInt()
            if (index < 0 || index >= days) return@forEach
            val h = ((kcal / maxKcal).toFloat()) * (size.height - 2 * pad)
            val cx = pad + slot * (index + 0.5f)
            drawRect(
                color = barColor,
                topLeft = Offset(cx - barWidth / 2f, size.height - pad - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
            )
        }

        goalKcal?.let { goal ->
            val y = size.height - pad - ((goal / maxKcal).toFloat()) * (size.height - 2 * pad)
            drawLine(
                color = goalColor,
                start = Offset(pad, y),
                end = Offset(size.width - pad, y),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
    }
}
