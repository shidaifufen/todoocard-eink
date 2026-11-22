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
        val poemNote: String = "",
        val customTitle: String = "我的卡片",
        val customLines: List<String> = listOf("今天也要好好生活"),
        val yearProgress: Int = -1,
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
        drawText(title, M, 74f, paint(40f, BLACK, bold = true))
        val p = paint(24f, BLACK)
        drawText(right, W - M - p.measureText(right), 66f, p)
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
            "countdown" -> c.drawCountdown(card)
            "poem" -> c.drawPoem(card)
            "custom" -> c.drawCustom(card)
            else -> c.drawSummary(card)
        }
        return bmp
    }

    private fun Canvas.drawSummary(card: Card) {
        val now = Date()
        val cal = Calendar.getInstance()
        head("每日总结", String.format(Locale.CHINA, "%d/%d %s",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), WEEK[cal.get(Calendar.DAY_OF_WEEK) - 1]))

        drawText(String.format(Locale.CHINA, "%02d", cal.get(Calendar.DAY_OF_MONTH)),
            M, 260f, paint(150f, RED, bold = true))
        drawText(String.format(Locale.CHINA, "%d月", cal.get(Calendar.MONTH) + 1),
            M + 4f, 300f, paint(26f, BLACK, bold = true))
        fill(M, 320f, W - M, 328f, YELLOW)

        drawText("今日待办", M, 390f, paint(30f, BLACK, bold = true))
        val first = card.todos.firstOrNull() ?: "今天还没有安排"
        drawText(first, M, 434f, paint(26f))
        rule(470f)

        val pct = if (card.yearProgress >= 0) card.yearProgress else {
            val doy = cal.get(Calendar.DAY_OF_YEAR)
            val total = if (cal.get(Calendar.YEAR) % 4 == 0) 366 else 365
            doy * 100 / total
        }
        drawText("本月", M, 528f, paint(26f, BLACK, bold = true))
        val p = paint(26f, BLACK)
        drawText("$pct%", W - M - p.measureText("$pct%"), 528f, p)
        fill(M, 540f, W - M, 556f, BLACK)
        fill(M + 3f, 543f, M + 3f + (W - 2 * M - 6f) * pct / 100f, 553f, GREEN)

        rule(600f)
        val qp = paint(24f)
        val lines = wrap(this, "“${card.quote}”", qp, W - 2 * M)
        var y = 640f
        lines.take(2).forEach { drawText(it, M, y, qp); y += 34f }
        foot(card.quoteBy)
    }

    private fun Canvas.drawQuote(card: Card) {
        val cal = Calendar.getInstance()
        head("每日一句", String.format(Locale.CHINA, "%d/%d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)))
        drawText("“", M - 4f, 250f, paint(110f, RED))
        val p = paint(40f, BLACK, bold = true)
        val lines = wrap(this, card.quote, p, W - 2 * M - 40f)
        var y = 280f
        fill(M + 6f, y - 46f, M + 12f, y + 34f * lines.size - 10f, RED)
        lines.take(6).forEach { drawText(it, M + 40f, y, p); y += 58f }
        drawText("— ${card.quoteBy}", M + 40f, y + 10f, paint(22f))
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
            if (text.isNotEmpty()) drawText(text, M + 32f, y - 6f, line)
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
        val total = if (cal.get(Calendar.YEAR) % 4 == 0) 366 else 365
        val pct = doy * 100 / total
        drawText("$pct", M, 280f, paint(140f, RED, bold = true))
        val pp = paint(44f, BLACK, bold = true)
        drawText("%", M + pp.measureText("$pct") * 1.0f + 120f, 250f, pp)
        drawText("已过 $doy / $total 天", M, 330f, paint(24f))
        rule(360f)

        val rows = listOf("今天" to (cal.get(Calendar.HOUR_OF_DAY) * 100 / 24),
                          "本周" to ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 * 100 / 7),
                          "本月" to (cal.get(Calendar.DAY_OF_MONTH) * 100 / 30),
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
            drawText(name, M, y, paint(28f, BLACK, bold = true))
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
        var y = 210f
        val p = paint(38f, BLACK, bold = true)
        card.poemLines.take(6).forEach { line ->
            wrap(this, line, p, W - 2 * M).forEach { drawText(it, M, y, p); y += 58f }
        }
        if (card.poemNote.isNotEmpty()) {
            rule(y + 6f)
            drawText("赏析", M, y + 46f, paint(22f, RED, bold = true))
            var ny = y + 84f
            wrap(this, card.poemNote, paint(20f), W - 2 * M).take(6).forEach {
                drawText(it, M, ny, paint(20f)); ny += 34f
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
            wrap(this, line, paint(26f), W - 2 * M).forEach { drawText(it, M, y, paint(26f)); y += 40f }
        }
        foot()
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
