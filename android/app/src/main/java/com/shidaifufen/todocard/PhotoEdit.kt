package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/** 照片推送前的裁切/位移/色彩调整 */
object PhotoEdit {

    data class Params(
        val zoom: Float = 1f,
        val dx: Float = 0f,      // -1..1
        val dy: Float = 0f,
        val brightness: Int = 0, // -100..100
        val contrast: Float = 1f,// 0.5..1.6
        val saturation: Float = 1f, // 0..1.6
    )

    /** 输出 528×792 的卡片图（照片区含信息带下方，或全幅） */
    fun render(path: String, p: Params, band: Int = 0): Bitmap {
        val src = BitmapFactory.decodeFile(path) ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val W = Render.W
        val H = Render.H - band
        val out = Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)

        val cover = maxOf(W.toFloat() / src.width, H.toFloat() / src.height) * p.zoom
        val dw = src.width * cover
        val dh = src.height * cover
        val left = (W - dw) / 2 + p.dx * (dw - W) / 2
        val top = (H - dh) / 2 + p.dy * (dh - H) / 2

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix()
        cm.setSaturation(p.saturation)
        val b = p.brightness * 2.55f
        cm.postConcat(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, b,
            0f, 1f, 0f, 0f, b,
            0f, 0f, 1f, 0f, b,
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
        paint.colorFilter = ColorMatrixColorFilter(cm)
        c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), paint)
        return out
    }
}
