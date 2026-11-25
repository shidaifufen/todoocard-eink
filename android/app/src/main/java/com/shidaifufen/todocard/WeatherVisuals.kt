package com.shidaifufen.todocard

import android.graphics.*
import kotlin.math.*

/** WMO symbols drawn as vectors: no dependency on system emoji fonts. */
internal object WeatherVisuals {
    internal data class DayRange(val label: String, val low: Int, val high: Int)
    internal fun parseDayRange(text: String): DayRange? {
        val match = Regex("(-?\\d+)\\s*[~～至]\\s*(-?\\d+)\\s*°?\\s*$").find(text.trim()) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        return DayRange(text.substring(0, match.range.first).trim(), minOf(first, second), maxOf(first, second))
    }

    /** Horizontal range bars leave enough room for the date, weather and both temperatures. */
    fun forecastBars(c: Canvas, forecasts: List<String>, firstBaseline: Float = 681f,
                     rowGap: Float = 29f, leftInset: Float = 32f, barColor: Int = Color.BLUE,
                     width: Float = 464f, fontScale: Float = 1f) {
        val rows = forecasts.take(3)
        val ranges = rows.map(::parseDayRange)
        val valid = ranges.filterNotNull()
        val text = chartText(20f, fontScale)
        if (rows.isEmpty()) {
            c.drawText("刷新天气后显示三天预报", leftInset, firstBaseline, text)
            return
        }
        val lo = valid.minOfOrNull { it.low } ?: 0
        val hi = valid.maxOfOrNull { it.high } ?: 1
        val span = (hi - lo).coerceAtLeast(1)
        val values = ranges.map { range -> range?.let { "${it.low}~${it.high}°" }.orEmpty() }
        val valueWidth = values.maxOf { text.measureText(it) }
        val labelWidth = width * .39f
        val barStart = leftInset + labelWidth + 9f
        val valueStart = leftInset + width - valueWidth
        val barWidth = (valueStart - barStart - 12f).coerceAtLeast(6f)
        rows.forEachIndexed { index, raw ->
            val y = firstBaseline + index * rowGap
            val item = ranges[index]
            if (item == null) {
                var label = raw
                while (text.measureText(label) > width && label.length > 1) label = label.dropLast(1)
                c.drawText(label, leftInset, y, text)
            } else {
                var label = item.label
                while (text.measureText(label) > labelWidth && label.length > 1) label = label.dropLast(1)
                c.drawText(label, leftInset, y, text)
                val left = barStart + (item.low - lo) * (barWidth - 5f) / span
                val right = barStart + (item.high - lo) * (barWidth - 5f) / span
                c.drawRoundRect(left, y - 12f, maxOf(left + 5f, right), y - 3f, 4f, 4f,
                    Paint().apply { color = barColor })
                c.drawText(values[index], valueStart, y, text)
            }
        }
    }

    fun icon(c: Canvas, code: Int, x: Float, y: Float, size: Float) {
        c.save(); c.translate(x, y); c.scale(size / 100f, size / 100f)
        val ink = Paint().apply { color = Color.BLACK; strokeWidth = 4f; style = Paint.Style.STROKE }
        val sun = Paint(ink).apply { color = Color.RED }
        if (code == 0 || code in 1..2) {
            c.drawCircle(48f, 40f, 18f, sun)
            repeat(8) { val a = it * PI / 4
                c.drawLine(48f + cos(a).toFloat() * 25, 40f + sin(a).toFloat() * 25,
                    48f + cos(a).toFloat() * 33, 40f + sin(a).toFloat() * 33, sun) }
        }
        if (code != 0) {
            val cloud = Path().apply { moveTo(21f, 70f); cubicTo(-1f, 68f, 0f, 39f, 25f, 39f)
                cubicTo(26f, 16f, 61f, 14f, 67f, 38f); cubicTo(95f, 28f, 108f, 69f, 80f, 70f); close() }
            c.drawPath(cloud, Paint().apply { color = Color.WHITE }); c.drawPath(cloud, ink)
        }
        when {
            code in 71..77 || code in 85..86 -> repeat(3) { i -> val px = 25f + i * 24
                c.drawLine(px - 5, 82f, px + 5, 92f, ink); c.drawLine(px + 5, 82f, px - 5, 92f, ink) }
            code in 51..67 || code in 80..82 || code in 95..99 -> repeat(3) { i ->
                c.drawLine(28f + i * 24, 79f, 21f + i * 24, 95f, Paint(ink).apply { color = Color.BLUE }) }
            code in 45..48 -> { c.drawLine(12f, 81f, 88f, 81f, ink); c.drawLine(22f, 92f, 78f, 92f, ink) }
            code < 0 -> c.drawText("?", 43f, 62f, Paint().apply { textSize = 26f; color = Color.BLACK })
        }
        if (code in 95..99) c.drawPath(Path().apply { moveTo(57f, 68f); lineTo(44f, 83f)
            lineTo(55f, 83f); lineTo(43f, 99f) }, sun)
        c.restore()
    }

    private fun chartText(size: Float, scale: Float) = Paint().apply {
        color = Color.BLACK
        textSize = (size * scale).coerceAtLeast(18f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        isAntiAlias = false
    }

    fun chart(c: Canvas, hours: List<Render.WeatherHour>, top: Float, height: Float,
              lineColor: Int = Color.BLUE, spacing: Float = 1f, fontScale: Float = 1f,
              left: Float = 32f, width: Float = 464f) {
        val p = chartText(20f, fontScale)
        val title = chartText(23f, fontScale)
        val titleBaseline = top - title.ascent()
        c.drawText("未来时段 · 温度趋势", left, titleBaseline, title)
        val h = hours.filter { it.temperature.isFinite() }.take(6)
        if (h.size < 2) {
            c.drawText("刷新天气后显示温度曲线", left, titleBaseline + title.descent() - p.ascent() + 7f, p)
            return
        }
        val min = h.minOf { it.temperature } - 1
        val max = h.maxOf { it.temperature } + 1
        val timeBaseline = top + height - p.descent()
        val plotTop = titleBaseline + title.descent() - p.ascent() + 9f * spacing
        val plotBottom = timeBaseline + p.ascent() - 9f * spacing
        val side = h.maxOf { maxOf(p.measureText(it.time.take(5)), p.measureText("${it.temperature.roundToInt()}°")) } / 2
        val line = Paint().apply { color = lineColor; strokeWidth = 3f; style = Paint.Style.STROKE }
        var px = 0f; var py = 0f
        h.forEachIndexed { i, v ->
            val x = left + side + i * (width - 2 * side) / (h.size - 1)
            val y = plotBottom - (v.temperature - min) / (max - min) * (plotBottom - plotTop).coerceAtLeast(0f)
            if (i > 0) c.drawLine(px, py, x, y, line)
            c.drawCircle(x, y, 4f, Paint().apply { color = Color.RED })
            val t = "${v.temperature.roundToInt()}°"
            c.drawText(t, x - p.measureText(t) / 2, y - 7f - p.descent(), p)
            c.drawText(v.time.take(5), x - p.measureText(v.time.take(5)) / 2, timeBaseline, p)
            px = x; py = y
        }
    }
}
