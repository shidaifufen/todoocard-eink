package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextSnapTest {
    @Test
    fun cleansWhiteBackgroundAtSelectedStrengthAndKeepsConnectedStrokes() {
        val sample = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        sample.eraseColor(Color.WHITE)
        sample.setPixel(2, 2, Color.BLACK)
        sample.setPixel(4, 3, Color.BLUE)
        sample.setPixel(3, 7, Color.rgb(115, 115, 115))
        for (y in 5..8) sample.setPixel(7, y, Color.BLACK)

        val baseline = PaletteSnap.finishCard(sample.copy(Bitmap.Config.ARGB_8888, true), 0)
        assertEquals(Color.BLACK, baseline.getPixel(2, 2))
        assertEquals(Color.BLUE, baseline.getPixel(4, 3))
        assertEquals(Color.BLACK, baseline.getPixel(3, 7))

        val clean = PaletteSnap.finishCard(sample, Render.DEFAULT_TEXT_SNAP)
        assertEquals(Color.WHITE, clean.getPixel(2, 2))
        assertEquals(Color.WHITE, clean.getPixel(4, 3))
        assertEquals(Color.WHITE, clean.getPixel(3, 7))
        assertEquals(Color.BLACK, clean.getPixel(7, 5))
        assertEquals(Color.BLACK, clean.getPixel(7, 8))
    }
}
