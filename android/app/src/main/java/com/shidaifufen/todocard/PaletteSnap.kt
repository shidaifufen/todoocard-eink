package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.Color

/** 与桌面版 snap_palette 一致的图像预处理，不改变量化、压缩或传输协议。 */
object PaletteSnap {
    private val palette = intArrayOf(Color.BLACK, Color.WHITE, Color.YELLOW,
        Color.RED, Color.BLUE, Color.GREEN)

    /** 原地处理可写位图。0 为关闭；强度越大，越多接近原生六色的像素被吸附。 */
    fun apply(bitmap: Bitmap, threshold: Int) {
        if (threshold <= 0) return
        val limit = threshold.coerceIn(1, 100).let { it * it * 3 }
        snap(bitmap, limit)
    }

    /** Text and vector cards are finished with exact panel colors before they are saved or sent. */
    internal fun finishCard(bitmap: Bitmap): Bitmap = bitmap.also {
        snap(it, 3 * 255 * 255)
    }

    private fun snap(bitmap: Bitmap, limit: Int) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var changed = false
        for (index in pixels.indices) {
            val color = pixels[index]
            if (palette.contains(color)) continue
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            if (maxOf(r, g, b) - minOf(r, g, b) < 42) {
                pixels[index] = if ((r * 299 + g * 587 + b * 114) / 1000 < 128)
                    Color.BLACK else Color.WHITE
                changed = true
                continue
            }
            var best = color
            var distance = limit
            for (candidate in palette) {
                val dr = r - Color.red(candidate)
                val dg = g - Color.green(candidate)
                val db = b - Color.blue(candidate)
                val squared = dr * dr + dg * dg + db * db
                if (squared <= distance) {
                    distance = squared
                    best = candidate
                }
            }
            pixels[index] = best
            if (best != color) changed = true
        }
        if (changed) bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
