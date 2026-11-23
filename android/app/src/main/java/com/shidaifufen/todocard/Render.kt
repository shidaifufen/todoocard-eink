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
    const val M = 34f

    private val BLACK = Color.rgb(0, 0, 0)
    private val WHITE = Color.rgb(255, 255, 255)
    private val RED = Color.rgb(255, 0, 0)
    private val BLUE = Color.rgb(0, 0, 255)
    private val GREEN = Color.rgb(0, 255, 0)
    private val YELLOW = Color.rgb(255, 255, 0)

    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    data class Card(
        val kind: String = "summary",
        val quote: String = "把今天过好，就是对明天最好的准备。",
        val quoteBy: String = "土豆片",
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
    )

    private fun paint(size: Float, color: Int = BLACK, bold: Boolean = false, mono: Boolean = false): Paint =
        Paint().apply {
            isAntiAlias = false
            this.color = color
            textSize = size
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.SANS_SERIF
            }
        }

    private fun Canvas.head(title: String, right: String) {
        drawText(fitLine(title, paint(40f, BLACK, true), 290f), M, 74f,
            paint(40f, BLACK, bold = true))
        val p = paint(24f, BLACK)
        val label = fitLine(right, p, 165f)
        drawText(label, W - M - p.measureText(label), 66f, p)
        fill(M, 96f, W - M, 99f, RED)
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
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(WHITE)
        when (card.kind) {
            "quote" -> c.drawQuote(card)
            "todo" -> c.drawTodo(card)
            "progress" -> c.drawProgress(card)
            "calendar" -> c.drawCalendar()
            "weather" -> c.drawWeather(card)
            "countdown" -> c.drawCountdown(card)
            "poem" -> c.drawPoem(card)
            "custom" -> c.drawCustom(card)
            else -> c.drawSummary(card)
        }
        return bmp
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
        fill(M, 294f, W - M, 302f, YELLOW)
        drawText("今日待办", M, 345f, paint(28f, BLACK, true))
        val todos = card.todos.take(4)
        drawText("${todos.size} 项", W - M - paint(18f).measureText("${todos.size} 项"), 345f, paint(18f))
        var y = 388f
        if (todos.isEmpty()) {
            drawText("今天还没有安排", M, y, paint(23f))
            y += 54f
        } else todos.forEachIndexed { index, item ->
            fill(M, y - 20f, M + 24f, y + 5f, listOf(RED, BLUE, GREEN, YELLOW)[index])
            drawText("${index + 1}", M + 5f, y, paint(18f, WHITE, true))
            val line = fitLine(item, paint(23f), W - M * 2 - 46f)
            drawText(line, M + 40f, y, paint(23f))
            y += 52f
        }
        val monthDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthPct = cal.get(Calendar.DAY_OF_MONTH) * 100 / monthDays
        val progressY = maxOf(y + 20f, 615f).coerceAtMost(637f)
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
        q.forEachIndexed { index, line -> drawText(line, M, 717f + index * 25f, paint(20f)) }
        val by = fitLine(card.quoteBy, paint(16f), 125f)
        drawText(by, W - M - paint(16f).measureText(by), 775f, paint(16f))
    }

    private fun Canvas.drawQuote(card: Card) {
        val cal = Calendar.getInstance()
        head("每日一句", String.format(Locale.CHINA, "%d/%d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)))
        drawText("“", M - 4f, 250f, paint(110f, RED))
        var p = paint(40f, BLACK, bold = true)
        while (p.textSize > 27f && wrap(this, card.quote, p, W - 2 * M - 40f).size > 7)
            p = paint(p.textSize - 2f, BLACK, true)
        val lines = wrap(this, card.quote, p, W - 2 * M - 40f)
        var y = 280f
        val lineHeight = p.textSize * 1.45f
        val shown = lines.take(7)
        fill(M + 6f, y - p.textSize, M + 12f, y + lineHeight * (shown.size - 1) + 8f, RED)
        shown.forEachIndexed { index, value ->
            val text = if (index == 6 && lines.size > 7) fitLine(value + "…", p, W - M * 2 - 40f) else value
            drawText(text, M + 40f, y, p); y += lineHeight
        }
        drawText(fitLine("— ${card.quoteBy}", paint(22f), W - M * 2 - 40f),
            M + 40f, minOf(y + 14f, 715f), paint(22f))
        foot()
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
            y += 74f
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
        val pct = doy * 100 / total
        drawText("$pct", M, 280f, paint(140f, RED, bold = true))
        val pp = paint(44f, BLACK, bold = true)
        drawText("%", M + paint(140f, RED, true).measureText("$pct") + 8f, 250f, pp)
        drawText("已过 $doy / $total 天", M, 330f, paint(24f))
        rule(360f)

        val rows = listOf("今天" to (cal.get(Calendar.HOUR_OF_DAY) * 100 / 24),
                          "本周" to ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 * 100 / 7),
                          "本月" to (cal.get(Calendar.DAY_OF_MONTH) * 100 / cal.getActualMaximum(Calendar.DAY_OF_MONTH)),
                          "今年" to pct)
        var y = 410f
        rows.forEach { (label, v) ->
            drawText(label, M, y, paint(22f, BLACK, bold = true))
            fill(M + 90f, y - 16f, W - M, y - 4f, BLACK)
            fill(M + 92f, y - 14f,
                M + 92f + (W - 2 * M - 94f) * (v.coerceIn(0, 100)) / 100f, y - 6f,
                if (label == "今年") RED else GREEN)
            val vp = paint(20f)
            drawText("$v%", W - M - vp.measureText("$v%"), y, vp)
            y += 62f
        }
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
                val target = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date)!!
                ((target.time - System.currentTimeMillis()) / 86400000L).toInt()
            }.getOrDefault(0)
            val dp = paint(40f, RED, bold = true)
            drawText("${days}天", W - M - dp.measureText("${days}天"), y, dp)
            rule(y + 20f)
            y += 96f
        }
        foot()
    }

    private fun Canvas.drawPoem(card: Card) {
        head(card.poemTitle, card.poemBy)
        var y = 198f
        val p = paint(35f, BLACK, bold = true)
        card.poemLines.take(6).forEach { line ->
            wrap(this, line, p, W - 2 * M).forEach { fragment ->
                if (y < 545f) drawText(fragment, M, y, p)
                y += 52f
            }
        }
        if (card.poemNote.isNotEmpty()) {
            val noteTop = maxOf(567f, y + 14f).coerceAtMost(603f)
            rule(noteTop)
            drawText("赏析", M, noteTop + 39f, paint(22f, RED, bold = true))
            var ny = noteTop + 69f
            wrap(this, card.poemNote, paint(20f), W - 2 * M).take(3).forEach {
                if (ny < H - 50f) drawText(it, M, ny, paint(20f))
                ny += 27f
            }
        }
        foot()
    }

    private fun Canvas.drawCustom(card: Card) {
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
        drawText("$temp°", M, 250f, paint(116f, RED, true))
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
        rule(505f)
        drawText("未来三天", M, 548f, paint(25f, BLACK, true))
        val forecast = card.weatherForecast.take(3)
        if (forecast.isEmpty()) drawText("在编辑中添加预报", M, 605f, paint(22f))
        else forecast.forEachIndexed { index, item ->
            val text = fitLine(item, paint(22f), W - M * 2 - 25f)
            drawText("${index + 1}", M, 606f + index * 47f, paint(22f, RED, true))
            drawText(text, M + 28f, 606f + index * 47f, paint(22f))
        }
        drawText("Open-Meteo · ${card.weatherUpdated.ifBlank { "手动资料" }}", M, 765f, paint(16f))
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
