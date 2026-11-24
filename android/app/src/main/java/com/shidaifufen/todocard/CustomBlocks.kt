package com.shidaifufen.todocard

import android.graphics.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal object CustomBlocks {
    val types = linkedMapOf("title" to "标题", "bigdate" to "大日期", "weather" to "天气",
        "todos" to "待办", "quote" to "每日一句", "poem" to "诗词", "dots" to "年度进度",
        "countdown" to "倒计时", "text" to "文字", "qr" to "二维码", "rule" to "分割线", "image" to "图片")

    fun defaults(card: Render.Card) = listOf(Render.CustomBlock(type = "bigdate"),
        Render.CustomBlock(type = "text", text = card.customLines.joinToString("\n")))

    fun draw(canvas: Canvas, card: Render.Card) {
        val accent = when (card.customAccent) { "blue" -> Color.BLUE; "green" -> Color.GREEN
            "yellow" -> Color.YELLOW; "black" -> Color.BLACK; else -> Color.RED }
        val width = 464f
        val gap = 14f * card.elementSpacing.coerceIn(.7f, 1.4f)
        val blocks = card.customBlocks.filter { it.enabled }.take(20)
        val rendered = blocks.map { block(card, it, accent) }
        val total = rendered.sumOf { it.height } + (rendered.size - 1).coerceAtLeast(0) * gap
        val scale = minOf(1f, 622f / total.coerceAtLeast(1f))
        canvas.drawText(card.customTitle.take(18), 32f, 62f, textPaint(28f, Color.BLACK, true))
        canvas.drawRect(32f, 88f, 496f, 91f, Paint().apply { color = accent })
        canvas.save(); canvas.translate(32f, 111f); canvas.scale(scale, scale)
        // Keep the scaled composition centered when many elements share the page.
        canvas.translate((width / scale - width) / 2, 0f)
        rendered.forEach { b -> canvas.drawBitmap(b, 0f, 0f, null); canvas.translate(0f, b.height + gap); b.recycle() }
        canvas.restore()
        canvas.drawText("土豆片 · 自由组合", 354f, 763f, textPaint(16f))
    }

    private fun textPaint(size: Float, ink: Int = Color.BLACK, bold: Boolean = false) = Paint().apply {
        color = ink; textSize = size; isAntiAlias = false
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun lines(text: String, p: Paint): List<String> = text.lines().flatMap { paragraph ->
        val out = mutableListOf<String>(); var row = ""
        paragraph.take(3000).forEach { ch ->
            if (p.measureText(row + ch) > 464f && row.isNotEmpty()) { out += row; row = "" }; row += ch
        }; out + row
    }
    private fun block(card: Render.Card, b: Render.CustomBlock, accent: Int): Bitmap {
        val now = LocalDate.now()
        val value = when (b.type) {
            "title" -> b.text.ifBlank { card.customTitle }
            "bigdate" -> now.toString() + "  " + listOf("一", "二", "三", "四", "五", "六", "日")[now.dayOfWeek.value - 1]
            "weather" -> listOf(card.weatherCity, card.weatherText,
                card.weatherTemp.takeIf { it.isNotBlank() }?.plus("°") ?: "刷新天气后显示").joinToString("  ")
            "todos" -> card.todos.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n").ifBlank { "今天还没有安排" }
            "quote" -> card.quote + "\n— " + card.quoteBy
            "poem" -> card.poemTitle + " · " + card.poemBy + "\n" + card.poemLines.joinToString("\n")
            "countdown" -> card.countdowns.joinToString("\n") { (name, date) ->
                "$name  " + runCatching { ChronoUnit.DAYS.between(now, LocalDate.parse(date)).toString() + " 天" }.getOrDefault(date)
            }.ifBlank { "在倒计时模板中添加纪念日" }
            else -> b.text
        }
        val p = textPaint(b.size.coerceIn(14f, 64f) * card.fontScale, if (b.type in listOf("title", "bigdate")) accent else Color.BLACK,
            b.type in listOf("title", "bigdate"))
        val rows = lines(value, p).take(30)
        val step = p.textSize * 1.4f * card.lineSpacing.coerceIn(.8f, 1.5f)
        val height = when (b.type) { "rule" -> 8; "dots" -> 160
            "image", "qr" -> b.height.coerceIn(80, 360)
            else -> (rows.size * step + 14).toInt().coerceAtLeast(35) }
        val bitmap = Bitmap.createBitmap(464, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap); c.drawColor(Color.WHITE)
        when (b.type) {
            "rule" -> c.drawRect(0f, 2f, 464f, 5f, Paint().apply { color = accent })
            "dots" -> {
                c.drawText("今年 ${now.dayOfYear * 100 / now.lengthOfYear()}% · 剩余 ${now.lengthOfYear() - now.dayOfYear} 天", 0f, 25f, textPaint(22f))
                repeat(now.lengthOfYear()) { i -> c.drawCircle(5f + i % 40 * 11.6f, 45f + i / 40 * 11.5f, 3.2f,
                    Paint().apply { color = if (i < now.dayOfYear) accent else Color.BLACK }) }
            }
            "image" -> {
                val image = PhotoEdit.decodePreview(b.imagePath)
                if (image != null) {
                    val scale = minOf(464f / image.width, height.toFloat() / image.height)
                    val w = image.width * scale; val h = image.height * scale
                    c.drawBitmap(image, null, RectF((464 - w) / 2, (height - h) / 2, (464 + w) / 2, (height + h) / 2), null)
                    image.recycle()
                } else c.drawText("请在编辑中选择图片", 0f, 30f, textPaint(22f))
            }
            "qr" -> {
                if (b.text.isBlank()) c.drawText("请填写二维码内容", 0f, 30f, textPaint(22f))
                else runCatching {
                    val matrix = QRCodeWriter().encode(b.text.take(1500), BarcodeFormat.QR_CODE, height, height,
                        mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
                    val ink = Paint().apply { color = Color.BLACK }
                    for (y in 0 until height) for (x in 0 until height) if (matrix[x, y])
                        c.drawRect((464 - height) / 2f + x, y.toFloat(), (464 - height) / 2f + x + 1, y + 1f, ink)
                }.onFailure { c.drawText("内容过长，请缩短二维码文字", 0f, 30f, textPaint(20f)) }
            }
            else -> rows.forEachIndexed { i, row -> c.drawText(row,
                if (b.centered) (464 - p.measureText(row)) / 2 else 0f, p.textSize + i * step, p) }
        }
        return bitmap
    }
}
