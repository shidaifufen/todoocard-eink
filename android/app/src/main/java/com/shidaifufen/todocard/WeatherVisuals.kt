package com.shidaifufen.todocard

import android.graphics.*
import kotlin.math.*

/** WMO symbols drawn as vectors: no dependency on system emoji fonts. */
internal object WeatherVisuals {
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

    fun chart(c: Canvas, hours: List<Render.WeatherHour>, top: Float, height: Float) {
        val p = Paint().apply { color = Color.BLACK; textSize = 16f; typeface = Typeface.SANS_SERIF }
        if (height >= 100f) c.drawText("未来时段 · 温度趋势", 32f, top + 20f, p)
        val h = hours.take(6)
        if (h.size < 2) { c.drawText("刷新天气后显示温度曲线", 32f, top + 56f, p); return }
        val min = h.minOf { it.temperature } - 2; val max = h.maxOf { it.temperature } + 2
        val line = Paint().apply { color = Color.BLUE; strokeWidth = 3f; style = Paint.Style.STROKE }
        var px = 0f; var py = 0f
        h.forEachIndexed { i, v ->
            val x = 53f + i * 422f / (h.size - 1)
            val y = top + height - 25 - (v.temperature - min) / (max - min) * (height - 74).coerceAtLeast(12f)
            if (i > 0) c.drawLine(px, py, x, y, line)
            c.drawCircle(x, y, 4f, Paint().apply { color = Color.RED })
            val t = "${v.temperature.roundToInt()}°"
            c.drawText(t, x - p.measureText(t) / 2, y - 10, p)
            c.drawText(v.time.take(5), x - p.measureText(v.time.take(5)) / 2, top + height - 2, p)
            px = x; py = y
        }
    }
}
