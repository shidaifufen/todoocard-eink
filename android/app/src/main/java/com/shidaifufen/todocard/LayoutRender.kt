package com.shidaifufen.todocard

import android.graphics.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Fixed 528×792 coordinate space. Every element is clipped to its declared box. */
internal object LayoutRender {
    fun render(card: Render.Card, template: LayoutPack.Template): Bitmap {
        val bitmap = Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val values = values(card)
            for (node in template.nodes) {
                val n = JSONObject(node.json)
                if (n.optString("showIf").let { it.isNotBlank() && values[it].isNullOrBlank() }) continue
                canvas.save()
                try {
                    canvas.clipRect(node.x, node.y, node.x + node.width, node.y + node.height)
                    canvas.translate(node.x, node.y)
                    draw(canvas, node, n, card, values)
                } finally { canvas.restore() }
            }
            return bitmap
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    private fun values(card: Render.Card): Map<String, String> {
        val date = LocalDate.now()
        val t = card.weatherTemp.toIntOrNull()
        return mapOf(
            "weatherCity" to card.weatherCity, "weatherText" to card.weatherText,
            "weatherTemp" to card.weatherTemp.ifBlank { "--" }, "weatherHigh" to card.weatherHigh.ifBlank { "--" },
            "weatherLow" to card.weatherLow.ifBlank { "--" }, "weatherAqi" to card.weatherAqi.ifBlank { "--" },
            "weatherHumidity" to card.weatherHumidity.ifBlank { "--" }, "weatherFeels" to card.weatherFeels.ifBlank { "--" },
            "weatherSunrise" to card.weatherSunrise.ifBlank { "--:--" }, "weatherSunset" to card.weatherSunset.ifBlank { "--:--" },
            "weatherUpdated" to card.weatherUpdated.ifBlank { "手动资料" },
            "wearAdvice" to when { t == null -> "选择城市后显示"; t < 10 -> "厚外套，注意保暖"; t < 18 -> "外套加长袖"; t < 26 -> "长袖或薄外套"; else -> "轻薄短袖，注意补水" },
            "date" to date.toString(), "time" to LocalTime.now().toString().take(5),
            "week" to listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1],
            "year" to date.year.toString(), "month" to date.monthValue.toString(), "day" to date.dayOfMonth.toString(),
            "quote" to card.quote, "quoteBy" to card.quoteBy, "poemTitle" to card.poemTitle,
            "poemBy" to card.poemBy, "poemNote" to card.poemNote, "poemText" to card.poemLines.joinToString("\n"),
            "customTitle" to card.customTitle, "customText" to card.customLines.joinToString("\n"),
            "todoCount" to card.todos.size.toString(), "yearPercent" to (date.dayOfYear * 100 / date.lengthOfYear()).toString())
    }
    private fun bind(text: String, values: Map<String, String>) = Regex("\\{\\{([A-Za-z][A-Za-z0-9]*)}}").replace(text) {
        values[it.groupValues[1]].orEmpty().take(4000)
    }.take(12000)
    private fun color(name: String, card: Render.Card): Int = when (name) {
        "red" -> Color.RED; "blue" -> Color.BLUE; "green" -> Color.GREEN; "yellow" -> Color.YELLOW; "white" -> Color.WHITE
        "aqiColor" -> when (val aqi = card.weatherAqi.toIntOrNull()) { null -> Color.WHITE; in 0..50 -> Color.GREEN; in 51..100 -> Color.YELLOW; else -> Color.RED }
        "aqiInk" -> if ((card.weatherAqi.toIntOrNull() ?: 0) > 100) Color.WHITE else Color.BLACK
        else -> Color.BLACK
    }
    private fun draw(c: Canvas, node: LayoutPack.Node, n: JSONObject, card: Render.Card, values: Map<String, String>) {
        val w = node.width; val h = node.height
        when (node.type) {
            "rect" -> {
                val radius = n.optDouble("radius", 0.0).toFloat()
                c.drawRoundRect(0f, 0f, w, h, radius, radius, Paint().apply { color = color(n.optString("fill", "white"), card) })
                if (n.has("border")) {
                    val stroke = n.optDouble("borderWidth", 2.0).toFloat()
                    c.drawRoundRect(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2, radius, radius,
                        Paint().apply { color = color(n.getString("border"), card); style = Paint.Style.STROKE; strokeWidth = stroke })
                }
            }
            "text", "list" -> {
                val value = if (node.type == "text") bind(n.optString("text"), values) else {
                    val list = when (n.getString("source")) {
                        "todos" -> card.todos; "poemLines" -> card.poemLines; "weatherForecast" -> card.weatherForecast
                        "countdowns" -> card.countdowns.map { (name, date) -> name + "  " + runCatching {
                            ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(date)).toString() + " 天"
                        }.getOrDefault(date) }
                        else -> card.customLines
                    }
                    list.take(80).mapIndexed { i, v -> if (n.optBoolean("numbered")) "${i + 1}. $v" else v }.joinToString("\n")
                        .ifBlank { bind(n.optString("emptyText"), values) }
                }
                text(c, value.take(12000), n, w, h, card)
            }
            "weatherIcon" -> WeatherVisuals.icon(c, card.weatherCode, 0f, 0f, minOf(w, h))
            "weatherHours" -> {
                c.scale(w / 464f, h / 122f); c.translate(-32f, 0f)
                WeatherVisuals.chart(c, card.weatherHours, 0f, 122f, color(n.optString("color", "blue"), card))
            }
            "forecastBars" -> {
                c.scale(w / 464f, h / 82f); c.translate(-32f, 0f)
                WeatherVisuals.forecastBars(c, card.weatherForecast, firstBaseline = 21f, rowGap = 27f,
                    barColor = color(n.optString("color", "blue"), card))
            }
            "builtin" -> {
                val image = Render.renderNative(card.copy(kind = n.getString("kind")))
                try { c.drawBitmap(image, null, RectF(0f, 0f, w, h), null) } finally { image.recycle() }
            }
            "block" -> {
                val block = Render.CustomBlock(type = n.getString("blockType"), text = bind(n.optString("text"), values),
                    size = n.optDouble("textSize", 24.0).toFloat(), height = h.toInt().coerceIn(80, 360),
                    centered = n.optBoolean("centered"))
                val image = CustomBlocks.block(card, block, color(n.optString("color", "red"), card))
                try {
                    val scale = minOf(w / image.width, h / image.height)
                    c.drawBitmap(image, null, RectF(0f, 0f, image.width * scale, image.height * scale), null)
                } finally { image.recycle() }
            }
        }
    }
    private fun lines(value: String, paint: Paint, width: Float, wrap: Boolean): List<String> = value.lines().flatMap { paragraph ->
        if (!wrap) listOf(paragraph) else {
            val out = mutableListOf<String>()
            var line = ""
            for (ch in paragraph) {
                if (paint.measureText(line + ch) > width && line.isNotEmpty()) { out += line; line = "" }
                line += ch
            }
            out + line
        }
    }
    private fun text(c: Canvas, value: String, n: JSONObject, width: Float, height: Float, card: Render.Card) {
        val p = Paint().apply {
            color = color(n.optString("color", "black"), card)
            textSize = n.optDouble("textSize", 24.0).toFloat() * card.fontScale.coerceIn(.8f, 1.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, if (n.optBoolean("bold")) Typeface.BOLD else Typeface.NORMAL)
            isAntiAlias = false
        }
        val wrap = n.optBoolean("wrap", true)
        val spacing = n.optDouble("lineHeight", 1.4).toFloat() * card.lineSpacing.coerceIn(.8f, 1.6f)
        fun fits(rows: List<String>) = rows.maxOfOrNull { p.measureText(it) }.let { (it ?: 0f) <= width } &&
            (-p.fontMetrics.top + p.fontMetrics.bottom + (rows.size - 1).coerceAtLeast(0) * p.textSize * spacing) <= height
        var rows = lines(value, p, width, wrap)
        while (n.optBoolean("shrink", true) && p.textSize > 10f && !fits(rows)) {
            p.textSize = (p.textSize - 1).coerceAtLeast(10f); rows = lines(value, p, width, wrap)
        }
        val baseline = -p.fontMetrics.top
        val step = maxOf(p.textSize * spacing, p.fontMetrics.bottom - p.fontMetrics.top)
        val count = ((height - baseline - p.fontMetrics.bottom) / step).toInt().plus(1).coerceAtLeast(0)
        rows.take(count).forEachIndexed { i, original ->
            var line = original
            if (p.measureText(line) > width || (i == count - 1 && rows.size > count)) {
                while (line.isNotEmpty() && p.measureText(line + "…") > width) line = line.dropLast(1)
                line += "…"
            }
            val x = when (n.optString("align", "left")) { "center" -> (width - p.measureText(line)) / 2; "right" -> width - p.measureText(line); else -> 0f }
            c.drawText(line, x, baseline + i * step, p)
        }
    }
}
