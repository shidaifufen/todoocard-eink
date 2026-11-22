package com.shidaifufen.todocard

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PNG/Bitmap → 设备六色 payload（对应桌面版 payload.py）
 *
 * 1. cover 裁切到 528×792
 * 2. Floyd–Steinberg 抖动量化到 6 色，4bit/像素
 * 3. 面板标定：整体旋转 180° + 水平翻转
 * 4. 控制器视角：顺时针旋转 + 镜像
 * 5. 老固件兼容包：每 64 字节加 3 字节头 74 43 40
 */
object Payload {
    const val W = 528
    const val H = 792

    private val PALETTE = arrayOf(
        intArrayOf(0, 0, 0, 0),
        intArrayOf(255, 255, 255, 1),
        intArrayOf(255, 255, 0, 2),
        intArrayOf(255, 0, 0, 3),
        intArrayOf(0, 0, 255, 5),
        intArrayOf(0, 255, 0, 6),
    )

    fun cover(src: Bitmap): Bitmap {
        val scale = max(W.toFloat() / src.width, H.toFloat() / src.height)
        val nw = (src.width * scale).roundToInt().coerceAtLeast(1)
        val nh = (src.height * scale).roundToInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(resized, ((W - nw) / 2f), ((H - nh) / 2f), null)
        return out
    }

    /** Floyd–Steinberg → 4bit/像素 */
    fun quantize(img: Bitmap): ByteArray {
        val w = img.width; val h = img.height
        val pix = IntArray(w * h)
        img.getPixels(pix, 0, w, 0, 0, w, h)
        val buf = FloatArray(w * h * 3)
        for (i in pix.indices) {
            buf[i * 3] = (pix[i] shr 16 and 0xFF).toFloat()
            buf[i * 3 + 1] = (pix[i] shr 8 and 0xFF).toFloat()
            buf[i * 3 + 2] = (pix[i] and 0xFF).toFloat()
        }
        val codes = ByteArray(w * h)

        fun add(x: Int, y: Int, er: Float, eg: Float, eb: Float, k: Float) {
            if (x in 0 until w && y in 0 until h) {
                val o = (y * w + x) * 3
                buf[o] += er * k; buf[o + 1] += eg * k; buf[o + 2] += eb * k
            }
        }

        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            val r = buf[i].coerceIn(0f, 255f)
            val g = buf[i + 1].coerceIn(0f, 255f)
            val b = buf[i + 2].coerceIn(0f, 255f)
            var best = PALETTE[0]; var bestD = Float.MAX_VALUE
            for (p in PALETTE) {
                val dr = r - p[0]; val dg = g - p[1]; val db = b - p[2]
                val d = dr * dr + dg * dg + db * db
                if (d < bestD) { bestD = d; best = p }
            }
            codes[y * w + x] = best[3].toByte()
            val er = r - best[0]; val eg = g - best[1]; val eb = b - best[2]
            add(x + 1, y, er, eg, eb, 7f / 16)
            add(x - 1, y + 1, er, eg, eb, 3f / 16)
            add(x, y + 1, er, eg, eb, 5f / 16)
            add(x + 1, y + 1, er, eg, eb, 1f / 16)
        }

        val out = ByteArray((w * h + 1) / 2)
        for (i in codes.indices) {
            if (i % 2 == 0) out[i / 2] = ((codes[i].toInt() and 0x0F) shl 4).toByte()
            else out[i / 2] = (out[i / 2].toInt() or (codes[i].toInt() and 0x0F)).toByte()
        }
        return out
    }

    private fun get(buf: ByteArray, i: Int): Int {
        val b = buf[i / 2].toInt() and 0xFF
        return if (i % 2 == 0) b ushr 4 else b and 0x0F
    }

    private fun set(buf: ByteArray, i: Int, code: Int) {
        if (i % 2 == 0) buf[i / 2] = ((buf[i / 2].toInt() and 0x0F) or ((code and 0x0F) shl 4)).toByte()
        else buf[i / 2] = ((buf[i / 2].toInt() and 0xF0) or (code and 0x0F)).toByte()
    }

    private fun flipH(buf: ByteArray): ByteArray {
        val out = ByteArray(buf.size)
        for (y in 0 until H) for (x in 0 until W) set(out, y * W + (W - 1 - x), get(buf, y * W + x))
        return out
    }

    private fun rot180(buf: ByteArray): ByteArray {
        val n = W * H
        val out = ByteArray(buf.size)
        for (i in 0 until n) set(out, n - 1 - i, get(buf, i))
        return out
    }

    private fun rotateCwMirrored(buf: ByteArray): ByteArray {
        val out = ByteArray(buf.size)
        for (di in 0 until W * H) {
            val dx = di % H; val dy = di / H
            val sx = W - 1 - dy; val sy = H - 1 - dx
            set(out, di, get(buf, sy * W + sx))
        }
        return out
    }

    fun legacyQlz(raw: ByteArray, block: Int = 64): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0))
        var off = 0
        while (off < raw.size) {
            out.write(byteArrayOf(0x74, 0x43, 0x40))
            val end = minOf(off + block, raw.size)
            out.write(raw, off, end - off)
            off = end
        }
        return out.toByteArray()
    }

    data class Built(val panel: ByteArray, val controller: ByteArray, val legacy: ByteArray)

    fun build(src: Bitmap): Built {
        val bitmap = quantize(cover(src))
        val panel = flipH(rot180(bitmap))
        val controller = rotateCwMirrored(panel)
        return Built(panel, controller, legacyQlz(controller))
    }
}
