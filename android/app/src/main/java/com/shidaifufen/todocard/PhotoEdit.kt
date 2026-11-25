package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 照片推送前的可视化编辑：
 *  - 手指拖动平移 / 双指缩放（对着卡片框裁切）
 *  - 对照片区域做调色，信息带在之后叠加
 */
object PhotoEdit {

    data class Params(
        val zoom: Float = 1f,
        val dx: Float = 0f,          // -1..1（相对可移动范围）
        val dy: Float = 0f,
        val hue: Float = 0f,         // -180..180
        val saturation: Float = 1f,  // 0..2
        val exposure: Float = 0f,    // -2..2 (EV)
        val contrast: Float = 1f,    // 0.6..1.6
        val snapThreshold: Int = 0, // 0 关闭；1..100 六色杂色吸附
        val fit: Boolean = false,
        val gamma: Float = 1f,
        val sharpness: Float = 0f,
        val shadows: Float = 0f,
    ) {
        val isDefault get() = zoom == 1f && dx == 0f && dy == 0f && hue == 0f &&
                saturation == 1f && exposure == 0f && contrast == 1f && snapThreshold == 0 &&
                !fit && gamma == 1f && sharpness == 0f && shadows == 0f
    }

    /** 色相旋转矩阵 */
    private fun hueMatrix(deg: Float): ColorMatrix {
        val a = Math.toRadians(deg.toDouble())
        val cos = Math.cos(a).toFloat()
        val sin = Math.sin(a).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return ColorMatrix(floatArrayOf(
            lr + cos * (1 - lr) + sin * (-lr), lg + cos * (-lg) + sin * (-lg), lb + cos * (-lb) + sin * (1 - lb), 0f, 0f,
            lr + cos * (-lr) + sin * 0.143f, lg + cos * (1 - lg) + sin * 0.140f, lb + cos * (-lb) + sin * (-0.283f), 0f, 0f,
            lr + cos * (-lr) + sin * (-(1 - lr)), lg + cos * (-lg) + sin * lg, lb + cos * (1 - lb) + sin * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    fun buildMatrix(p: Params): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(p.saturation)
        cm.postConcat(hueMatrix(p.hue))
        // 曝光：整体增益
        val gain = Math.pow(2.0, p.exposure.toDouble()).toFloat()
        cm.postConcat(ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, 0f,
            0f, gain, 0f, 0f, 0f,
            0f, 0f, gain, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        if (p.contrast != 1f) {
            val t = (1f - p.contrast) * 127.5f
            cm.postConcat(ColorMatrix(floatArrayOf(
                p.contrast, 0f, 0f, 0f, t,
                0f, p.contrast, 0f, 0f, t,
                0f, 0f, p.contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        return cm
    }

    /** 渲染成卡片（band>0 时底部留出信息带） */
    fun render(path: String, p: Params, band: Int = 0): Bitmap {
        val src = decodePreview(path, 2400) ?: error("照片无法解码，请重新选择")
        return try { renderBitmap(src, p, band) } finally { src.recycle() }
    }

    /** 预览与导出共用同一条裁切、调色、吸附路径。 */
    fun renderBitmap(src: Bitmap, p: Params, band: Int = 0): Bitmap {
        val out = Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawInto(c, src, p, band)
        finishArtwork(out, Render.H - band, p)
        PaletteSnap.apply(out, p.snapThreshold)
        return out
    }

    private fun finishArtwork(bitmap: Bitmap, height: Int, p: Params) {
        if (p.gamma == 1f && p.sharpness == 0f && p.shadows == 0f) return
        val width = bitmap.width
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gamma = IntArray(256) { (255 * (it / 255f).pow(1f / p.gamma)).roundToInt() }
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = gamma[color shr 16 and 255]
            val g = gamma[color shr 8 and 255]
            val b = gamma[color and 255]
            val luminance = (.299f * r + .587f * g + .114f * b) / 255f
            val remaining = 1f - luminance
            val lift = (255f * p.shadows * luminance * remaining * remaining * remaining).roundToInt()
            pixels[i] = android.graphics.Color.rgb((r + lift).coerceAtMost(255),
                (g + lift).coerceAtMost(255), (b + lift).coerceAtMost(255))
        }
        if (p.sharpness == 0f) {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return
        }
        val sharpened = pixels.copyOf()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val center = pixels[i]
            fun channel(shift: Int): Int {
                val c = center shr shift and 255
                val sum = (pixels[i - 1] shr shift and 255) + (pixels[i + 1] shr shift and 255) +
                    (pixels[i - width] shr shift and 255) + (pixels[i + width] shr shift and 255)
                return (c + p.sharpness * (c - sum / 4f)).roundToInt().coerceIn(0, 255)
            }
            sharpened[i] = android.graphics.Color.rgb(channel(16), channel(8), channel(0))
        }
        bitmap.setPixels(sharpened, 0, width, 0, 0, width, height)
    }

    internal fun panelPreview(bitmap: Bitmap): Bitmap {
        val ink = intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE,
            android.graphics.Color.YELLOW, android.graphics.Color.RED, 0,
            android.graphics.Color.BLUE, android.graphics.Color.GREEN)
        val packed = Payload.quantize(bitmap)
        val pixels = IntArray(bitmap.width * bitmap.height) { i ->
            val byte = packed[i / 2].toInt() and 0xff
            ink[if (i % 2 == 0) byte ushr 4 else byte and 15]
        }
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** 手势预览复用已解码图片；只重绘画布，不重新解码或分配卡片位图。 */
    fun drawInto(c: Canvas, src: Bitmap, p: Params, band: Int = 0) {
        val width = Render.W.toFloat()
        val height = (Render.H - band).toFloat()
        c.drawRect(0f, 0f, width, Render.H.toFloat(),
            Paint().apply { color = android.graphics.Color.WHITE })
        val scale = baseScale(width, height, src, p.fit) * p.zoom
        val dw = src.width * scale
        val dh = src.height * scale
        val left = (width - dw) / 2 + p.dx.coerceIn(-1f, 1f) * (dw - width).coerceAtLeast(0f) / 2
        val top = (height - dh) / 2 + p.dy.coerceIn(-1f, 1f) * (dh - height).coerceAtLeast(0f) / 2
        val colorAdjusted = p.hue != 0f || p.saturation != 1f ||
            p.exposure != 0f || p.contrast != 1f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (colorAdjusted) colorFilter = ColorMatrixColorFilter(buildMatrix(p))
        }
        c.save()
        c.clipRect(0f, 0f, width, height)
        c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), paint)
        c.restore()
    }

    internal fun baseScale(width: Float, height: Float, src: Bitmap, fit: Boolean): Float =
        if (fit) minOf(width / src.width, height / src.height) else maxOf(width / src.width, height / src.height)

    fun decodePreview(path: String, maxEdge: Int = 1200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}
