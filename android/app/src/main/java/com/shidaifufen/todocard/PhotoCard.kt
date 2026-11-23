package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** 照片卡：cover 裁切 + 底部信息带（与桌面版规则一致） */
object PhotoCard {

    data class Fields(
        val date: Boolean = true, val time: Boolean = true, val camera: Boolean = true,
        val params: Boolean = true, val weather: Boolean = true,
    )

    private fun paint(size: Float, bold: Boolean = false): Paint = Paint().apply {
        isAntiAlias = false
        color = android.graphics.Color.BLACK
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    fun render(path: String, fields: Fields): Bitmap {
        val src0 = BitmapFactory.decodeFile(path)
        val canvas = Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(android.graphics.Color.WHITE)

        val meta = readExif(path)
        val hasAny = meta.any { it.isNotBlank() }
        val showDate = fields.date && meta.taken.isNotBlank()
        val showTime = fields.time && meta.time.isNotBlank()
        val line1 = listOf(if (showDate) meta.taken else "", if (showTime) meta.time else "")
            .filter { it.isNotBlank() }.joinToString("  ")
        val line2 = listOf(if (fields.camera) meta.camera else "",
                           if (fields.params) meta.params else "")
            .filter { it.isNotBlank() }.joinToString("  ")

        val rows = (if (line1.isNotBlank()) 1 else 0) + (if (line2.isNotBlank()) 1 else 0)
        val band = if (!hasAny || rows == 0) 0 else 24 + 26 * rows

        val photoH = Render.H - band
        if (src0 != null) {
            val scale = maxOf(Render.W.toFloat() / src0.width, photoH.toFloat() / src0.height)
            val w = (src0.width * scale).toInt(); val h = (src0.height * scale).toInt()
            val left = (Render.W - w) / 2; val top = (photoH - h) / 2
            c.drawBitmap(src0, null, android.graphics.Rect(left, top, left + w, top + h), null)
        }

        if (band > 0) {
            c.drawRect(0f, photoH.toFloat(), Render.W.toFloat(), Render.H.toFloat(), Paint().apply {
                color = android.graphics.Color.WHITE
            })
            c.drawRect(0f, photoH.toFloat(), Render.W.toFloat(), photoH + 2f, Paint().apply {
                color = android.graphics.Color.BLACK
            })
            var y = photoH + 26f
            if (line1.isNotBlank()) {
                c.drawText(line1, Render.M, y, paint(21f, bold = true)); y += 26f
            }
            if (line2.isNotBlank()) {
                c.drawText(line2, Render.M, y, paint(16f)); y += 24f
            }
        }
        return canvas
    }

    private data class Meta(
        val taken: String = "", val time: String = "",
        val camera: String = "", val params: String = "",
    )

    private fun readExif(path: String): Meta {
        return try {
            val e = ExifInterface(path)
            val dt = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: e.getAttribute(ExifInterface.TAG_DATETIME) ?: ""
            val taken = if (dt.length >= 10) dt.substring(0, 10).replace(":", "-") else ""
            val time = if (dt.length >= 16) dt.substring(11, 16) else ""
            val make = e.getAttribute(ExifInterface.TAG_MAKE) ?: ""
            val model = e.getAttribute(ExifInterface.TAG_MODEL) ?: ""
            val focal = e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
            val fnum = e.getAttribute(ExifInterface.TAG_F_NUMBER)
            val expo = e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val iso = e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            val params = listOfNotNull(
                focal?.let { "%.0fmm".format(it.toDoubleOrNull() ?: 0.0) },
                fnum?.let { "f/$it" },
                expo?.let { "${it}s" },
                iso?.let { "ISO$it" },
            ).joinToString("  ")
            Meta(taken, time, listOf(make, model).filter { it.isNotBlank() }.joinToString(" "), params)
        } catch (e: Exception) { Meta() }
    }

    /** 把相册 Uri 复制到 App 私有目录，便于 Exif 与后续推送 */
    fun importToFiles(ctx: android.content.Context, uri: android.net.Uri): String? {
        return try {
            val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
            val out = File(dir, "p_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.absolutePath
        } catch (e: Exception) { null }
    }
}
