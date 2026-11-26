package com.shidaifufen.todocard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** 照片卡：cover 裁切 + 底部信息带（与桌面版规则一致） */
object PhotoCard {

    private const val DEFAULT_TEXT_SCALE = .85f

    data class Fields(
        val date: Boolean = true, val time: Boolean = true, val camera: Boolean = true,
        val params: Boolean = true, val weather: Boolean = true, val location: Boolean = true,
        val textScale: Float = DEFAULT_TEXT_SCALE,
        val resolveOnline: Boolean = true,
    )

    private fun paint(size: Float, bold: Boolean = false): Paint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    private data class Band(val dateTime: String, val camera: String, val params: String, val place: String) {
        val hasHeader get() = dateTime.isNotBlank() || camera.isNotBlank()
        val hasDetails get() = params.isNotBlank() || place.isNotBlank()
        val rowCount get() = (if (hasHeader) 1 else 0) + (if (hasDetails) 1 else 0)
    }

    private fun bandContent(path: String, fields: Fields): Band {
        val meta = readExif(path)
        val dateTime = listOf(if (fields.date) meta.taken else "", if (fields.time) meta.time else "")
            .filter(String::isNotBlank).joinToString("  ")
        val camera = if (fields.camera) meta.camera else ""
        val place = if (fields.location) location(path) else ""
        return Band(dateTime, camera, if (fields.params) meta.params else "", place)
    }
    internal fun cameraParameters(path: String): String = readExif(path).params
    fun bandHeight(path: String, fields: Fields): Int {
        val content = bandContent(path, fields)
        if (content.rowCount == 0) return 0
        val scale = fields.textScale.coerceIn(.8f, 1.5f)
        return 14 + (if (content.hasHeader) rowHeight(21f, 19f, scale) else 0) +
            (if (content.hasDetails) rowHeight(19f, 19f, scale) else 0)
    }
    fun location(path: String): String = locationNote(path).ifBlank { embeddedLocation(path) }
    fun embeddedLocation(path: String): String = resolvedPlaces[path]
        ?: File(path + ".place").takeIf { it.isFile }?.let {
            runCatching { it.readText().trim() }.getOrDefault("")
        }?.takeIf(String::isNotBlank) ?: readExif(path).coordinates?.let { (lat, lon) ->
            nearestCity(lat, lon).orEmpty()
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
    private val activePlaceLookups = ConcurrentHashMap.newKeySet<String>()
    private val cityCoordinates = ConcurrentHashMap<String, List<Triple<String, Double, Double>>>()

    fun initialize(ctx: Context) {
        cityCoordinates.getOrPut("builtin") {
            runCatching {
                JSONArray(ctx.assets.open("data/cities.json").bufferedReader().use { it.readText() })
                    .let { cities -> List(cities.length()) { index ->
                        cities.getJSONObject(index).let {
                            Triple(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"))
                        }
                    } }
            }.getOrDefault(emptyList())
        }
    }

    internal fun nearestCity(lat: Double, lon: Double, cities: List<Triple<String, Double, Double>>): String? =
        cities.map { (name, cityLat, cityLon) ->
            val latDelta = Math.toRadians(cityLat - lat)
            val lonDelta = Math.toRadians(cityLon - lon)
            val a = sin(latDelta / 2) * sin(latDelta / 2) +
                cos(Math.toRadians(lat)) * cos(Math.toRadians(cityLat)) *
                sin(lonDelta / 2) * sin(lonDelta / 2)
            name to 2 * 6371 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }.minByOrNull { it.second }?.takeIf { it.second <= 160 }?.first

    private fun nearestCity(lat: Double, lon: Double) =
        nearestCity(lat, lon, cityCoordinates["builtin"].orEmpty())

    private fun reverseGeocode(lat: Double, lon: Double): String? {
        val coordinates = String.format(Locale.ROOT, "lat=%.3f&lon=%.3f", lat, lon)
        val connection = URL("https://photon.komoot.io/reverse?$coordinates&limit=1")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.setRequestProperty("User-Agent", "TodooCard/1.8")
            val properties = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties") ?: return null
            return listOf("city", "municipality", "town", "village", "locality")
                .firstNotNullOfOrNull { properties.optString(it).takeIf(String::isNotBlank) }
        } catch (_: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun resolvePlace(ctx: Context, path: String, fields: Fields): Boolean = withContext(Dispatchers.IO) {
        if (!fields.location || !fields.resolveOnline) return@withContext false
        initialize(ctx)
        val savedPlace = File(path + ".place").takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrDefault("") }
        if (savedPlace?.isNotBlank() == true) resolvedPlaces.putIfAbsent(path, savedPlace)
        if (resolvedPlaces.containsKey(path) || locationNote(path).isNotBlank() || !activePlaceLookups.add(path)) return@withContext false
        try {
            val (lat, lon) = readExif(path).coordinates ?: return@withContext false
            val place = reverseGeocode(lat, lon) ?: return@withContext false
            resolvedPlaces[path] = place
            runCatching { File(path + ".place").writeText(place) }
            true
        } finally {
            activePlaceLookups.remove(path)
        }
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
        val content = bandContent(path, fields)
        val photoH = Render.H - band
        val scale = fields.textScale.coerceIn(.8f, 1.5f)
        val rule = paint(0f)
        c.drawRect(0f, photoH.toFloat(), Render.W.toFloat(), Render.H.toFloat(), Paint().apply { color = Color.WHITE })
        c.drawRect(0f, photoH.toFloat(), Render.W.toFloat(), photoH + 2f, rule)

        val width = Render.W - 2 * Render.M
        val headerSize = 21f * scale
        val detailSize = 19f * scale
        var y = photoH + 8f + detailSize
        if (content.hasHeader) {
            val date = paint(headerSize, bold = true)
            val camera = paint(detailSize, bold = true).apply { textAlign = Paint.Align.RIGHT }
            val leftWidth = date.measureText(content.dateTime).coerceAtMost(width * .55f)
            val gap = if (content.dateTime.isBlank()) 0f else 10f
            c.drawText(fit(content.dateTime, date, leftWidth), Render.M, y, date)
            c.drawText(fit(content.camera, camera, width - leftWidth - gap), Render.W - Render.M, y, camera)
            y += rowHeight(headerSize, detailSize, scale)
        }
        if (content.hasDetails) {
            val detail = paint(detailSize)
            val cityWidth = detail.measureText(content.place).coerceAtMost(if (content.params.isBlank()) width else width * .55f)
            val leftWidth = (width - cityWidth - if (content.place.isBlank()) 0f else 10f).coerceAtLeast(width * .35f)
            c.drawText(fit(content.params, detail, leftWidth), Render.M, y, detail)
            detail.textAlign = Paint.Align.RIGHT
            c.drawText(fit(content.place, detail, cityWidth), Render.W - Render.M, y, detail)
        }
    }

    private fun rowHeight(leftSize: Float, rightSize: Float, scale: Float) =
        (maxOf(leftSize, rightSize) * 1.25f + 3f * scale).toInt()

    private fun fit(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val count = paint.breakText(text, true, width - paint.measureText("…"), null)
        return text.take(count) + "…"
    }
}
