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

    internal fun applySceneTone(bitmap: Bitmap, strength: Int, height: Int) {
        if (strength <= 0) return
        val width = bitmap.width
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val radius = strength * 1.1f
        val limit = 3f * radius * radius
        for (index in pixels.indices) {
            val color = pixels[index]
            if (color in palette) continue
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val luma = .299f * r + .587f * g + .114f * b
            pixels[index] = when {
                maxOf(r, g, b) - minOf(r, g, b) < strength * .45f && luma <= strength * .8f -> Color.BLACK
                maxOf(r, g, b) - minOf(r, g, b) < strength * .45f && luma >= 255 - strength * .8f -> Color.WHITE
                else -> {
                    var best = color
                    var distance = limit
                    for (ink in palette) {
                        val dr = r - Color.red(ink)
                        val dg = g - Color.green(ink)
                        val db = b - Color.blue(ink)
                        val squared = dr * dr + dg * dg + db * db
                        if (squared < distance) {
                            best = ink
                            distance = squared.toFloat()
                        }
                    }
                    best
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** Text and vector cards are finished with exact panel colors before they are saved or sent. */
    internal fun finishCard(bitmap: Bitmap, strength: Int): Bitmap = bitmap.also {
        snap(it, 3 * 255 * 255, neutralCutoff = 128 - strength.coerceIn(0, 100) / 3)
        if (strength > 0) cleanWhiteBackground(it, strength.coerceIn(1, 100))
    }

    private fun cleanWhiteBackground(bitmap: Bitmap, strength: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val offsets = intArrayOf(-width - 1, -width, -width + 1, -1, 1, width - 1, width, width + 1)
        // Start with isolated colored pixels, then black pixels. At high strength include tiny pairs.
        val whiteNeighbors = if (strength >= 80) 7 else 8
        val marked = mutableListOf<Int>()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val index = y * width + x
            val color = pixels[index]
            if (color == Color.WHITE || color == Color.BLACK && strength < 35) continue
            if (offsets.count { pixels[index + it] == Color.WHITE } >= whiteNeighbors) marked.add(index)
        }
        if (marked.isNotEmpty()) {
            marked.forEach { pixels[it] = Color.WHITE }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun snap(bitmap: Bitmap, limit: Int, neutralCutoff: Int = 128) {
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
                pixels[index] = if ((r * 299 + g * 587 + b * 114) / 1000 < neutralCutoff)
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
