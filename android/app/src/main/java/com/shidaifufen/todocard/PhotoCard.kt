package com.shidaifufen.todocard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

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
            if (fields.camera) meta.camera else "",
            if (fields.params) meta.params else "",
            if (fields.location) location(path) else ""
        ).filter(String::isNotBlank)
    }
    internal fun cameraParameters(path: String): String = readExif(path).params
    fun bandHeight(path: String, fields: Fields): Int = rows(path, fields).size.let { if (it == 0) 0 else 24 + 26 * it }
    fun location(path: String): String = locationNote(path).ifBlank { embeddedLocation(path) }
    fun embeddedLocation(path: String): String = resolvedPlaces[path] ?: readExif(path).coordinates?.let { (lat, lon) ->
        String.format(Locale.ROOT, "%.4f°, %.4f°", lat, lon)
    }.orEmpty()
    fun locationNote(path: String): String = runCatching { File(path + ".location").readText().trim() }.getOrDefault("")
    fun saveLocationNote(path: String, value: String) {
        require(path.isNotBlank())
        val note = File(path + ".location")
        if (value.isBlank()) note.delete() else note.writeText(value.trim().take(120))
    }

    /** 带裁切/色彩参数的渲染 */
    fun render(path: String, fields: Fields, edit: PhotoEdit.Params, sixColors: Boolean = false): Bitmap {
        val band = bandHeight(path, fields)
        val adjusted = PhotoEdit.render(path, edit, band)
        val canvas = if (band > 0 || sixColors) {
            try { PhotoEdit.panelPreview(adjusted) } finally { adjusted.recycle() }
        } else adjusted
        // Overlay after dithering the artwork so its error cannot spill into the white band.
        if (band > 0) drawBand(Canvas(canvas), path, fields, band)
        return canvas
    }

    fun render(path: String, fields: Fields): Bitmap {
        return render(path, fields, PhotoEdit.Params())
    }

    private data class Meta(
        val taken: String = "", val time: String = "",
        val camera: String = "", val params: String = "", val coordinates: Pair<Double, Double>? = null,
    )

    private val metadataCache = ConcurrentHashMap<String, Meta>()
    private val resolvedPlaces = ConcurrentHashMap<String, String>()

    @Suppress("DEPRECATION")
    suspend fun resolvePlace(ctx: Context, path: String): Boolean {
        if (!Geocoder.isPresent() || resolvedPlaces.containsKey(path) || locationNote(path).isNotBlank()) return false
        val (lat, lon) = withContext(Dispatchers.IO) { readExif(path).coordinates } ?: return false
        val geocoder = Geocoder(ctx, Locale.getDefault())
        val address = withTimeoutOrNull(3500) {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine<Address?> { result ->
                try {
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (result.isActive) result.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { if (result.isActive) result.resume(null) }
                    })
                } catch (_: IllegalArgumentException) { result.resume(null) }
            } else withContext(Dispatchers.IO) {
                runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            }
        } ?: return false
        val place = listOfNotNull(address.locality ?: address.adminArea, address.subLocality, address.thoroughfare)
            .filter(String::isNotBlank).distinct().joinToString(" · ")
        if (place.isBlank()) return false
        resolvedPlaces[path] = place
        return true
    }

    private fun ExifInterface.positive(tag: String) = getAttributeDouble(tag, Double.NaN)
        .takeIf { it.isFinite() && it > 0 }

    private fun compact(value: Double, digits: Int) =
        String.format(Locale.ROOT, "%.${digits}f", value).trimEnd('0').trimEnd('.')

    private fun readExif(path: String): Meta {
        return metadataCache[path] ?: try {
            val e = ExifInterface(path)
            val dt = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: e.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: e.getAttribute(ExifInterface.TAG_DATETIME) ?: ""
            val taken = if (dt.length >= 10) dt.substring(0, 10).replace(":", "-") else ""
            val time = if (dt.length >= 16) dt.substring(11, 16) else ""
            val make = e.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
            val model = e.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
            val lens = e.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty().trim()
            val camera = listOf(make.takeUnless { model.startsWith("$it ", ignoreCase = true) }.orEmpty(), model, lens)
                .filter(String::isNotBlank).distinct().joinToString("  ")
            val equivalent = e.positive(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            val focal = e.positive(ExifInterface.TAG_FOCAL_LENGTH)
            val fnum = e.positive(ExifInterface.TAG_F_NUMBER)
            val expo = e.positive(ExifInterface.TAG_EXPOSURE_TIME)
            val iso = e.positive(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            val params = listOfNotNull(
                equivalent?.let { "${it.roundToInt()}mm 等效" } ?: focal?.let { "${compact(it, 1)}mm" },
                fnum?.let { "f/${compact(it, 1)}" },
                expo?.let { if (it in .00001..0.5) "1/${(1 / it).roundToInt()}s" else "${compact(it, 5)}s" },
                iso?.let { "ISO ${it.roundToInt()}" },
            ).joinToString("  ")
            Meta(taken, time, camera, params,
                e.latLong?.takeIf { it[0] in -90.0..90.0 && it[1] in -180.0..180.0 }?.let { it[0] to it[1] })
                .also { metadataCache[path] = it }
        } catch (e: Exception) { Meta().also { metadataCache[path] = it } }
    }

    /** 把相册 Uri 复制到 App 私有目录，便于 Exif 与后续推送 */
    fun importToFiles(ctx: Context, uri: Uri): String? {
        val resolver = ctx.contentResolver
        val original = if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content" &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val mediaUri = if (Build.VERSION.SDK_INT >= 31) MediaStore.getMediaUri(ctx, uri) ?: uri else uri
                resolver.openInputStream(MediaStore.setRequireOriginal(mediaUri))
            }.getOrNull() ?: runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(uri)) }.getOrNull()
        } else null
        val out = File(File(ctx.filesDir, "photos"), "p_${System.nanoTime()}.jpg")
        return try {
            val input = original ?: resolver.openInputStream(uri) ?: return null
            input.use {
                out.parentFile?.mkdirs()
                out.outputStream().use { output -> it.copyTo(output) }
            }
            out.absolutePath
        } catch (e: Exception) { out.delete(); null }
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
            val width = Render.W - Render.M * 2
            if (p.measureText(display) > width) {
                display = display.take(p.breakText(display, true, width - p.measureText("…"), null))
            }
            c.drawText(display + if (display.length < line.length) "…" else "", Render.M, y, p)
            y += 26f
        }
    }
}
