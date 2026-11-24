package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 照片卡：cover 裁切 + 底部信息带（与桌面版规则一致） */
object PhotoCard {

    data class Fields(
        val date: Boolean = true, val time: Boolean = true, val camera: Boolean = true,
        val params: Boolean = true, val weather: Boolean = true, val location: Boolean = true,
    )

    private fun paint(size: Float, bold: Boolean = false): Paint = Paint().apply {
        isAntiAlias = false
        color = android.graphics.Color.BLACK
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    private fun rows(path: String, fields: Fields): List<String> {
        val meta = readExif(path)
        return listOf(
            listOf(if (fields.date) meta.taken else "", if (fields.time) meta.time else "").filter(String::isNotBlank).joinToString("  "),
            listOf(if (fields.camera) meta.camera else "", if (fields.params) meta.params else "").filter(String::isNotBlank).joinToString("  "),
            if (fields.location) meta.location else ""
        ).filter(String::isNotBlank)
    }
    fun bandHeight(path: String, fields: Fields): Int = rows(path, fields).size.let { if (it == 0) 0 else 24 + 26 * it }
    fun location(path: String): String = readExif(path).location

    /** 带裁切/色彩参数的渲染 */
    fun render(path: String, fields: Fields, edit: PhotoEdit.Params): Bitmap {
        val band = bandHeight(path, fields)
        val canvas = PhotoEdit.render(path, edit, band)
        if (band > 0) drawBand(Canvas(canvas), path, fields, band)
        return canvas
    }

    fun render(path: String, fields: Fields): Bitmap {
        return render(path, fields, PhotoEdit.Params())
    }

    private data class Meta(
        val taken: String = "", val time: String = "",
        val camera: String = "", val params: String = "", val location: String = "",
    )

    private val metadataCache = ConcurrentHashMap<String, Meta>()

    private fun readExif(path: String): Meta {
        return metadataCache[path] ?: try {
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
            Meta(taken, time, listOf(make, model).filter { it.isNotBlank() }.joinToString(" "), params,
                e.latLong?.let { "%.4f°, %.4f°".format(java.util.Locale.ROOT, it[0], it[1]) }.orEmpty())
                .also { metadataCache[path] = it }
        } catch (e: Exception) { Meta().also { metadataCache[path] = it } }
    }

    /** 把相册 Uri 复制到 App 私有目录，便于 Exif 与后续推送 */
    fun importToFiles(ctx: android.content.Context, uri: android.net.Uri): String? {
        return try {
            val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
            val out = File(dir, "p_${System.nanoTime()}.jpg")
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use {
                out.outputStream().use { output -> it.copyTo(output) }
            }
            out.absolutePath
        } catch (e: Exception) { null }
    }

    internal fun drawBand(c: Canvas, path: String, fields: Fields, band: Int) {
        val lines = rows(path, fields)
        val photoH = Render.H - band
        c.drawRect(0f, photoH.toFloat(), Render.W.toFloat(), Render.H.toFloat(), Paint().apply {
            color = android.graphics.Color.WHITE
        })
        c.drawRect(0f, photoH.toFloat(), Render.W.toFloat(), photoH + 2f, Paint().apply {
            color = android.graphics.Color.BLACK
        })
        var y = photoH + 26f
        lines.forEachIndexed { index, line ->
            val p = paint(if (index == 0) 21f else 16f, index == 0)
            var display = line
            while (display.length > 1 && p.measureText(display) > Render.W - Render.M * 2) display = display.dropLast(1)
            c.drawText(display + if (display.length < line.length) "…" else "", Render.M, y, p)
            y += 26f
        }
    }
}
