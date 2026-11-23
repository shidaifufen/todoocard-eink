package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF

/**
 * 照片推送前的可视化编辑：
 *  - 手指拖动平移 / 双指缩放（对着卡片框裁切）
 *  - 色彩偏好：色相(hue) + 饱和度 + 曝光，和官方 App 的调色面板一致
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
    ) {
        val isDefault get() = zoom == 1f && dx == 0f && dy == 0f && hue == 0f &&
                saturation == 1f && exposure == 0f && contrast == 1f
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
        paint.colorFilter = ColorMatrixColorFilter(buildMatrix(p))
        c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), paint)
        return out
    }
}
