package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 卡片渲染：直接画到 528×792 Bitmap（与桌面版同一套版式规则）。
 * 只用六色，关闭抗锯齿，避免抖动后出现灰边。
 */
object Render {
    const val W = 528
    const val H = 792
    const val M = 32f

    private val BLACK = Color.rgb(0, 0, 0)
    private val WHITE = Color.rgb(255, 255, 255)
    private val RED = Color.rgb(255, 0, 0)
    private val BLUE = Color.rgb(0, 0, 255)
    private val GREEN = Color.rgb(0, 255, 0)
    private val YELLOW = Color.rgb(255, 255, 0)

    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    data class Card(
        val kind: String = "summary",
        val quote: String = "长风破浪会有时，直挂云帆济沧海。",
        val quoteBy: String = "李白《行路难》",
        val todos: List<String> = emptyList(),
        val countdowns: List<Pair<String, String>> = emptyList(),
        val poemTitle: String = "静夜思",
        val poemBy: String = "唐 · 李白",
        val poemLines: List<String> = listOf("床前明月光，疑是地上霜。", "举头望明月，低头思故乡。"),
        val poemNote: String = "诗人由床前月色想到故乡，以平实的语言写出夜深人静时的思乡之情。",
        val customTitle: String = "我的卡片",
        val customLines: List<String> = listOf("今天也要好好生活"),
        val yearProgress: Int = -1,
        val weatherCity: String = "",
        val weatherText: String = "",
        val weatherTemp: String = "",
        val weatherHigh: String = "",
        val weatherLow: String = "",
        val weatherAqi: String = "",
        val weatherForecast: List<String> = emptyList(),
        val weatherLatitude: String = "",
        val weatherLongitude: String = "",
        val weatherUpdated: String = "",
        val weatherAuto: Boolean = true,
        val weatherHumidity: String = "",
        val weatherFeels: String = "",
        val weatherSunrise: String = "",
        val weatherSunset: String = "",
        val weatherCode: Int = -1,
        val weatherHours: List<WeatherHour> = emptyList(),
        val fontScale: Float = 1f,
        val lineSpacing: Float = 1f,
        val elementSpacing: Float = 1f,
        val customBlocks: List<CustomBlock> = emptyList(),
        val customAccent: String = "red",
        val customComposition: Boolean = false,
    )

    data class WeatherHour(val time: String, val temperature: Float, val code: Int)
    data class CustomBlock(val id: String = java.util.UUID.randomUUID().toString(),
        val type: String = "text", val text: String = "", val enabled: Boolean = true,
        val size: Float = 24f, val height: Int = 180, val centered: Boolean = false,
        val imagePath: String = "")

    private val layout = ThreadLocal<Card>()
    private fun elementStep(base: Float) = base * (layout.get()?.elementSpacing ?: 1f).coerceIn(.7f, 1.4f)
    private fun lineStep(base: Float) = base * (layout.get()?.lineSpacing ?: 1f).coerceIn(.8f, 1.6f)

    private fun paint(size: Float, color: Int = BLACK, bold: Boolean = false, mono: Boolean = false): Paint =
        Paint().apply {
            isAntiAlias = false
            this.color = color
            textSize = size * (layout.get()?.fontScale ?: 1f).coerceIn(.8f, 1.5f)
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.SANS_SERIF
            }
        }

    private fun Canvas.head(title: String, right: String) {
        val p = paint(16f, BLACK)
        val label = fitLine(right, p, 165f)
        val titleWidth = W - M * 2 - 23f - p.measureText(label) - 18f
        fill(M, 37f, M + 13f, 50f, RED)
        drawText(fitLine(title, paint(30f, BLACK, true), titleWidth), M + 23f, 62f,
            paint(30f, BLACK, bold = true))
        drawText(label, W - M - p.measureText(label), 55f, p)
        fill(M, 88f, W - M, 91f, BLACK)
        fill(M, 88f, M + (W - M * 2) / 5, 92f, RED)
    }

    private fun Canvas.foot(tag: String = "土豆片") {
        val p = paint(20f, BLACK)
        drawText(tag, W - M - p.measureText(tag), H - 40f, p)
    }

    private fun Canvas.fill(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        drawRect(x1, y1, x2, y2, Paint().apply { this.color = color; isAntiAlias = false; style = Paint.Style.FILL })
    }

    private fun Canvas.rule(y: Float) = fill(M, y, W - M, y + 2f, BLACK)

    fun render(card: Card): Bitmap {
        LayoutStore.template(card.kind)?.let { return LayoutRender.render(card, it) }
        val bitmap = renderNative(card)
        return if (card.kind == "custom" && hasCustomPhotos(card)) bitmap else PaletteSnap.finishCard(bitmap)
    }

    internal fun hasCustomPhotos(card: Card) =
        card.customBlocks.any { it.enabled && it.type == "image" && it.imagePath.isNotBlank() }

    internal fun renderNative(card: Card): Bitmap {
        layout.set(card)
        try {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(WHITE)
        when (card.kind) {
            "quote" -> c.drawQuote(card)
            "todo" -> c.drawTodo(card)
            "progress" -> c.drawProgress(card)
            "calendar" -> c.drawCalendar()
            "weather" -> c.drawWeather(card)
            "weather2" -> c.drawWeatherBlocks(card)
            "countdown" -> c.drawCountdown(card)
            "poem" -> c.drawPoem(card)
            "custom" -> c.drawCustom(card)
            else -> c.drawSummary(card)
        }
        return bmp
        } finally { layout.remove() }
    }

    private fun Canvas.drawSummary(card: Card) {
        val cal = Calendar.getInstance()
        head("每日总结", String.format(Locale.CHINA, "%d/%d %s",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), WEEK[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7]))
        drawText(String.format(Locale.CHINA, "%02d", cal.get(Calendar.DAY_OF_MONTH)),
            M, 232f, paint(132f, RED, bold = true))
        drawText("${cal.get(Calendar.MONTH) + 1}月", M + 4f, 268f, paint(24f, BLACK, true))
        if (card.weatherTemp.isNotBlank()) {
            val temp = "${card.weatherTemp}°"
            val tp = paint(54f, BLACK, true)
            drawText(temp, W - M - tp.measureText(temp), 190f, tp)
            val place = listOf(card.weatherText, card.weatherCity).filter { it.isNotBlank() }
                .joinToString(" · ")
            drawText(place.take(15), W - M - paint(18f).measureText(place.take(15)), 236f, paint(18f))
        } else {
            drawText("今天也值得记录", W - M - paint(20f).measureText("今天也值得记录"),
                218f, paint(20f))
        }
        val sectionShift = elementStep(46f) - 46f
        val todoTitle = 345f + sectionShift
        fill(M, 294f, W - M, 302f, YELLOW)
        drawText("今日待办", M, todoTitle, paint(28f, BLACK, true))
        val todos = card.todos.take(4)
        drawText("${todos.size} 项", W - M - paint(18f).measureText("${todos.size} 项"), todoTitle, paint(18f))
        val progressY = (615f + sectionShift).coerceIn(597f, 637f)
        val firstTodo = todoTitle + elementStep(43f)
        val todoStep = lineStep(49f).coerceIn(39f, (progressY - 35f - firstTodo) / 3f)
        var y = firstTodo
        if (todos.isEmpty()) {
            drawText("今天还没有安排", M, y, paint(23f))
            y += 54f
        } else todos.forEachIndexed { index, item ->
            fill(M, y - 20f, M + 24f, y + 5f, listOf(RED, BLUE, GREEN, YELLOW)[index])
            drawText("${index + 1}", M + 5f, y, paint(18f, WHITE, true))
            val line = fitLine(item, paint(23f), W - M * 2 - 46f)
            drawText(line, M + 40f, y, paint(23f))
            y += todoStep
        }
        val monthDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthPct = cal.get(Calendar.DAY_OF_MONTH) * 100 / monthDays
        drawText("本月", M, progressY, paint(20f, BLACK, true))
        val pctText = "$monthPct%"
        drawText(pctText, W - M - paint(20f).measureText(pctText), progressY, paint(20f))
        fill(M, progressY + 12f, W - M, progressY + 25f, BLACK)
        fill(M + 2f, progressY + 14f, M + 2f + (W - M * 2 - 4f) * monthPct / 100f,
            progressY + 23f, GREEN)
        drawText("全年", M, 675f, paint(16f, BLACK, true))
        val passed = cal.get(Calendar.DAY_OF_YEAR)
        val total = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        repeat(52) { index ->
            val x = M + 53f + index * (W - M * 2 - 56f) / 51f
            drawCircle(x, 670f, 2.8f, Paint().apply {
                color = if (index < passed * 52 / total) RED else BLACK
            })
        }
        rule(691f)
        val q = wrap(this, "“${card.quote}”", paint(20f), W - M * 2).take(2)
        q.forEachIndexed { index, line -> drawText(line, M, 715f + index * lineStep(25f).coerceIn(20f, 29f), paint(20f)) }
        val by = fitLine(card.quoteBy, paint(16f), W - M * 2)
        drawText(by, W - M - paint(16f).measureText(by), 766f, paint(16f))
    }

    private fun Canvas.drawQuote(card: Card) {
        val cal = Calendar.getInstance()
        head("每日一句", String.format(Locale.CHINA, "%d/%d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)))
        drawText("“", M - 4f, 250f, paint(110f, RED))
        var p = paint(44f, BLACK, bold = true)
        while (p.textSize > 22f && wrap(this, card.quote, p, W - 2 * M - 40f).size * p.textSize * 1.45f > 350f)
            p = Paint(p).apply { textSize -= 2f }
        val lines = wrap(this, card.quote, p, W - 2 * M - 40f)
        var y = 280f
        val lineHeight = lineStep(p.textSize * 1.45f)
        val maxLines = (350f / lineHeight).toInt().coerceAtLeast(1)
        val shown = lines.take(maxLines)
        fill(M + 6f, y - p.textSize, M + 12f, y + lineHeight * (shown.size - 1) + 8f, RED)
        shown.forEachIndexed { index, value ->
            val text = if (index == maxLines - 1 && lines.size > maxLines) fitLine(value + "…", p, W - M * 2 - 40f) else value
            drawText(text, M + 40f, y, p); y += lineHeight
        }
        wrap(this, "— ${card.quoteBy.ifBlank { "佚名" }}", paint(20f), W - M * 2 - 40f)
            .take(2).forEachIndexed { index, line -> drawText(line, M + 40f,
                minOf(y + 25f, 667f) + index * 27f, paint(20f)) }
        listOf(RED, BLUE, GREEN).forEachIndexed { i, color ->
            drawCircle(M + i * 19f + 5f, 747f, 5f, Paint().apply { this.color = color })
        }
        val date = String.format(Locale.CHINA, "%d.%02d.%02d", cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        drawText(date, W - M - paint(16f).measureText(date), 753f, paint(16f))
    }

    private fun Canvas.drawTodo(card: Card) {
        fill(0f, 0f, W.toFloat(), 150f, YELLOW)
        drawText("今日待办", M, 74f, paint(40f, BLACK, bold = true))
        drawText(String.format(Locale.CHINA, "%d/%d",
            Calendar.getInstance().get(Calendar.MONTH) + 1,
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH)), M, 106f, paint(20f))
        drawText("${card.todos.size} 件", W - M - 60f, 96f, paint(30f, BLACK, bold = true))

        var y = 210f
        val line = paint(24f)
        repeat(6) { i ->
            val text = card.todos.getOrNull(i) ?: ""
            fill(M, y - 18f, M + 18f, y, BLACK)
            fill(M + 30f, y - 3f, W - M, y - 1f, BLACK)
            if (text.isNotEmpty()) drawText(fitLine(text, line, W - M * 2 - 36f), M + 32f, y - 6f, line)
            y += elementStep(lineStep(74f)).coerceIn(55f, 84f)
        }
        drawText("勾掉一件算一件", M, H - 60f, paint(20f))
        val t = paint(20f)
        val time = String.format(Locale.CHINA, "%02d:%02d",
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY), Calendar.getInstance().get(Calendar.MINUTE))
        drawText(time, W - M - t.measureText(time), H - 60f, t)
    }

    private fun Canvas.drawProgress(card: Card) {
        val cal = Calendar.getInstance()
        head("时间进度", cal.get(Calendar.YEAR).toString())
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val total = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        val pct = kotlin.math.round(doy * 100f / total).toInt()
        val number = paint(96f, RED)
        drawText("$pct", M, 205f, number)
        drawText("%", M + number.measureText("$pct") + 8f, 203f, paint(40f, RED))
        drawText("已过 $doy / $total 天", M + 2f, 244f, paint(21f))
        val gap = (W - M * 2 - 12f) / 31f
        repeat(total) { i ->
            val circle = Paint().apply {
                color = if (i < doy) RED else BLACK
                style = if (i < doy) Paint.Style.FILL else Paint.Style.STROKE
                strokeWidth = 1.6f
            }
            drawCircle(M + 6f + i % 32 * gap, 280f + i / 32 * gap, 5.2f, circle)
        }
        rule(468f)
        val today = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f
        val week = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + today) / 7f
        val month = (cal.get(Calendar.DAY_OF_MONTH) - 1 + today) / cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        listOf(Triple("今天", today, RED), Triple("本周", week, BLUE),
            Triple("本月", month, GREEN)).forEachIndexed { index, (label, value, color) ->
            val y = 508f + index * 58f
            drawText(label, M, y, paint(20f, BLACK, true))
            drawText("${kotlin.math.round(value * 100).toInt()}%", M + 58f, y, paint(20f))
            val x = M + 136f
            fill(x, y - 20f, W - M, y + 3f, BLACK)
            fill(x + 3f, y - 17f, W - M - 3f, y, WHITE)
            fill(x + 3f, y - 17f, x + 3f + (W - M - x - 6f) * value, y, color)
        }
        drawText("今年还剩", M, 704f, paint(20f, BLACK, true))
        fill(M, 726f, M + 9f, 758f, RED)
        drawText("${total - doy} 天", M + 22f, 753f, paint(30f))
        foot()
    }

    private fun Canvas.drawCountdown(card: Card) {
        val cal = Calendar.getInstance()
        head("倒计时", String.format(Locale.CHINA, "%d/%d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)))
        var y = 190f
        if (card.countdowns.isEmpty()) {
            drawText("还没有纪念日", M, y, paint(30f, BLACK, bold = true))
            drawText("在编辑里加一条：名称,2027-01-01", M, y + 44f, paint(20f))
        }
        card.countdowns.take(5).forEach { (name, date) ->
            drawText(fitLine(name, paint(28f, BLACK, true), 260f), M, y, paint(28f, BLACK, true))
            val days = runCatching {
                val target = java.time.LocalDate.parse(date)
                java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), target).toInt()
            }.getOrDefault(0)
            val dp = paint(40f, RED, bold = true)
            drawText("${days}天", W - M - dp.measureText("${days}天"), y, dp)
            rule(y + 20f)
            y += elementStep(lineStep(96f)).coerceIn(72f, 110f)
        }
        foot()
    }

    private fun Canvas.drawPoem(card: Card) {
        fill(M, 37f, M + 13f, 50f, RED)
        var titlePaint = paint(30f, BLACK, true)
        while (titlePaint.textSize > 22f && titlePaint.measureText(card.poemTitle) > W - M * 2 - 23f)
            titlePaint = Paint(titlePaint).apply { textSize -= 2f }
        drawText(fitLine(card.poemTitle, titlePaint, W - M * 2 - 23f), M + 23f, 58f, titlePaint)
        drawText(fitLine(card.poemBy, paint(18f), W - M * 2 - 23f), M + 23f, 88f, paint(18f))
        rule(110f)
        fill(M, 110f, M + 90f, 114f, RED)
        val hasNote = card.poemNote.isNotBlank()
        val startY = 190f
        val maxPoemHeight = if (hasNote) 360f else 505f
        var chosenPaint = paint(35f, BLACK, bold = true)
        var wrapped = emptyList<String>()
        var lineHeight = 0f
        for (fontSize in 35 downTo 19 step 2) {
            val candidate = paint(fontSize.toFloat(), BLACK, bold = true)
            val lines = card.poemLines.take(80).flatMap { wrap(this, it, candidate, W - M * 2) }
            val height = lineStep(candidate.textSize * 1.48f)
            chosenPaint = candidate
            wrapped = lines
            lineHeight = height
            if (lines.size * height <= maxPoemHeight) break
        }
        val maxLines = (maxPoemHeight / lineHeight).toInt().coerceAtLeast(1)
        val displayLines = wrapped.take(maxLines).toMutableList()
        if (wrapped.size > maxLines && displayLines.isNotEmpty()) {
            displayLines[displayLines.lastIndex] = fitLine(displayLines.last() + "…", chosenPaint, W - M * 2)
        }
        displayLines.forEachIndexed { index, line ->
            drawText(line, M, startY + index * lineHeight, chosenPaint)
        }
        if (hasNote) {
            val lastPoemBaseline = startY + (displayLines.size - 1).coerceAtLeast(0) * lineHeight
            val noteTop = (lastPoemBaseline + elementStep(52f)).coerceIn(360f, 560f)
            rule(noteTop)
            drawText("译注 · 赏析", M, noteTop + 34f, paint(20f, RED, bold = true))
            wrap(this, card.poemNote, paint(18f), W - M * 2).take(5).forEachIndexed { index, line ->
                drawText(line, M, noteTop + 62f + index * lineStep(23f).coerceAtMost(27f), paint(18f))
            }
        }
        foot()
    }

    private fun Canvas.drawCustom(card: Card) {
        if (card.customComposition || card.customBlocks.isNotEmpty()) { CustomBlocks.draw(this, card); return }
        val cal = Calendar.getInstance()
        head(card.customTitle, String.format(Locale.CHINA, "%d/%d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)))
        drawText(String.format(Locale.CHINA, "%02d", cal.get(Calendar.DAY_OF_MONTH)),
            M, 250f, paint(120f, RED, bold = true))
        var y = 330f
        card.customLines.take(8).forEach { line ->
            wrap(this, line, paint(26f), W - 2 * M).forEach {
                if (y < H - 75f) drawText(it, M, y, paint(26f))
                y += 40f
            }
        }
        foot()
    }

    private fun Canvas.drawCalendar() {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val today = now.get(Calendar.DAY_OF_MONTH)
        head("月历", "$year / ${"%02d".format(month)}")
        drawText("${month}月", M, 204f, paint(92f, RED, true))
        drawText("今天是 ${WEEK[(now.get(Calendar.DAY_OF_WEEK) + 5) % 7]}", M, 247f,
            paint(24f, BLACK, true))
        fill(M, 275f, W - M, 282f, YELLOW)
        val cols = listOf("一", "二", "三", "四", "五", "六", "日")
        val cell = (W - M * 2) / 7f
        cols.forEachIndexed { index, label ->
            drawText(label, M + index * cell + cell / 2 - 11f, 326f, paint(22f, BLACK, true))
        }
        rule(342f)
        val first = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val lastDay = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (day in 1..lastDay) {
            val index = offset + day - 1
            val col = index % 7
            val row = index / 7
            val cx = M + col * cell + cell / 2
            val cy = 392f + row * 55f
            if (day == today) {
                drawCircle(cx, cy - 7f, 23f, Paint().apply { color = RED })
            }
            val p = paint(23f, if (day == today) WHITE else BLACK, day == today)
            val text = day.toString()
            drawText(text, cx - p.measureText(text) / 2, cy, p)
        }
        rule(715f)
        drawText("把日子过成喜欢的样子", M, 752f, paint(21f))
    }

    private fun Canvas.drawWeather(card: Card) {
        head("天气看板", card.weatherCity.ifBlank { "今日" })
        val temp = card.weatherTemp.ifBlank { "--" }
        val tempPaint = paint(116f, RED, true).apply { textSize = minOf(textSize, 310f / measureText("$temp°").coerceAtLeast(1f) * textSize) }
        drawText("$temp°", M, 250f, tempPaint)
        drawText(card.weatherText.ifBlank { "在编辑中填写天气" }, M, 305f,
            paint(30f, BLACK, true))
        fill(M, 335f, W - M, 343f, YELLOW)
        val cells = listOf("最高" to card.weatherHigh, "最低" to card.weatherLow,
            "AQI·美标" to card.weatherAqi)
        val cellWidth = (W - 2 * M) / 3f
        cells.forEachIndexed { index, (label, value) ->
            val x = M + index * cellWidth
            drawText(label, x, 398f, paint(20f))
            val valuePaint = paint(37f, if (index == 2) GREEN else BLACK, true)
            val label = value.ifBlank { "--" } + if (index < 2 && value.isNotBlank()) "°" else ""
            drawText(fitLine(label, valuePaint, cellWidth - 10f), x, 445f, valuePaint)
            fill(x, 465f, x + 32f, 472f, listOf(RED, BLUE, GREEN)[index])
        }
        WeatherVisuals.icon(this, card.weatherCode, 371f, 165f, 116f)
        rule(495f)
        WeatherVisuals.chart(this, card.weatherHours, 505f, 122f)
        drawText("未来三天 · 气温范围", M, 657f, paint(21f, BLACK, true))
        WeatherVisuals.forecastBars(this, card.weatherForecast)
        drawText("Open-Meteo · ${card.weatherUpdated.ifBlank { "手动资料" }}", M, 765f, paint(16f))
    }

    private fun Canvas.drawWeatherBlocks(card: Card) {
        fun cell(x: Float, y: Float, right: Float, bottom: Float, color: Int = WHITE) {
            fill(x, y, right, bottom, BLACK)
            fill(x + 2, y + 2, right - 2, bottom - 2, color)
        }
        val right = W - M
        val mid = M + 278f
        cell(M, 32f, right, 116f)
        drawText(fitLine(card.weatherCity.ifBlank { "先选择城市" }, paint(29f, BLACK, true), 248f),
            M + 14f, 75f, paint(29f, BLACK, true))
        drawText("色块天气", M + 14f, 101f, paint(17f))
        cell(mid, 32f, right, 116f, YELLOW)
        val now = Calendar.getInstance()
        drawText(WEEK[(now.get(Calendar.DAY_OF_WEEK) + 5) % 7], mid + 20f, 68f, paint(25f, BLACK, true))
        drawText("%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)),
            mid + 20f, 99f, paint(26f))
        cell(M, 116f, mid, 366f)
        val tp = paint(88f, BLACK, true)
        drawText(fitLine(card.weatherTemp.ifBlank { "--" } + "°", tp, 248f), M + 16f, 231f, tp)
        drawText(fitLine(card.weatherText.ifBlank { "等待天气资料" }, paint(30f, BLACK, true), 248f),
            M + 16f, 292f, paint(30f, BLACK, true))
        drawText("今日 ${card.weatherLow.ifBlank { "--" }}～${card.weatherHigh.ifBlank { "--" }}°",
            M + 16f, 340f, paint(21f))
        WeatherVisuals.icon(this, card.weatherCode, M + 210f, 124f, 54f)
        val aqi = card.weatherAqi.toIntOrNull()
        val color = when { aqi == null -> WHITE; aqi <= 50 -> GREEN; aqi <= 100 -> YELLOW; else -> RED }
        val ink = if (color == RED) WHITE else BLACK
        cell(mid, 116f, right, 248f, color)
        drawText("空气 AQI·美标", mid + 12f, 147f, paint(17f, ink, true))
        drawText(card.weatherAqi.ifBlank { "--" }, mid + 12f, 209f, paint(48f, ink))
        cell(mid, 248f, right, 366f)
        drawText("湿度 ${card.weatherHumidity.ifBlank { "--" }}%", mid + 12f, 294f, paint(20f))
        drawText("体感 ${card.weatherFeels.ifBlank { "--" }}°", mid + 12f, 337f, paint(20f))
        cell(M, 366f, right, 533f)
        drawText("未来三天", M + 14f, 400f, paint(23f, BLUE, true))
        WeatherVisuals.forecastBars(this, card.weatherForecast, firstBaseline = 438f,
            rowGap = 35f, leftInset = M + 14f)
        val half = M + (W - 2 * M) / 2
        cell(M, 533f, half, 720f, BLUE)
        drawText("日出 / 日落", M + 14f, 568f, paint(22f, WHITE, true))
        drawText("日出", M + 14f, 608f, paint(17f, WHITE))
        drawText(card.weatherSunrise.ifBlank { "--:--" }, M + 68f, 608f, paint(27f, WHITE))
        drawText("日落", M + 14f, 673f, paint(17f, WHITE))
        drawText(card.weatherSunset.ifBlank { "--:--" }, M + 68f, 673f, paint(27f, WHITE))
        cell(half, 533f, right, 720f)
        drawText("穿衣参考", half + 14f, 568f, paint(22f, BLACK, true))
        val t = card.weatherTemp.toIntOrNull()
        val advice = when {
            t == null -> "选择城市后显示"
            t < 10 -> "厚外套，注意保暖"
            t < 18 -> "外套加长袖"
            t < 26 -> "长袖或薄外套"
            else -> "轻薄短袖，注意补水"
        }
        wrap(this, advice, paint(20f), right - half - 28f).take(2).forEachIndexed { i, line ->
            drawText(line, half + 14f, 610f + i * 29f, paint(20f))
        }
        drawText("更新 ${card.weatherUpdated.ifBlank { "手动资料" }}", half + 14f, 690f,
            paint(14f))
        cell(M, 720f, right, H - M)
        drawText("Open-Meteo · 城市天气与空气质量", M + 14f, 746f, paint(16f))
    }

    private fun fitLine(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var end = text.length
        while (end > 0 && p.measureText(text.take(end) + "…") > width) end--
        return text.take(end) + "…"
    }

    private fun wrap(c: Canvas, text: String, p: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (ch in text) {
            val test = line.toString() + ch
            if (p.measureText(test) > maxWidth && line.isNotEmpty()) {
                out.add(line.toString()); line = StringBuilder().append(ch)
            } else line.append(ch)
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }
}
