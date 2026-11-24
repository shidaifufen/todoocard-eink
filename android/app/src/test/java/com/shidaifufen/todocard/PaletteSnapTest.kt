package com.shidaifufen.todocard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaletteSnapTest {
    @Test fun snapsScreenshotEdgesAndKeepsDistantPhotoColors() {
        val colors = intArrayOf(Color.rgb(246, 248, 250), Color.rgb(45, 42, 44),
            Color.rgb(236, 15, 24), Color.rgb(16, 18, 238),
            Color.rgb(20, 235, 25), Color.rgb(240, 240, 16),
            Color.rgb(120, 80, 140), Color.rgb(165, 55, 55))
        val bitmap = Bitmap.createBitmap(colors, colors.size, 1, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, true)
        val off = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        PaletteSnap.apply(off, 0)
        assertTrue("disabled snapping must preserve every pixel", off.sameAs(bitmap))
        PaletteSnap.apply(bitmap, 60)
        val actual = IntArray(colors.size)
        bitmap.getPixels(actual, 0, colors.size, 0, 0, colors.size, 1)
        assertArrayEquals(intArrayOf(Color.WHITE, Color.BLACK, Color.RED, Color.BLUE,
            Color.GREEN, Color.YELLOW, colors[6], colors[7]), actual)
        PaletteSnap.apply(bitmap, 100)
        assertEquals("higher strength snaps more colors", Color.RED, bitmap.getPixel(7, 0))
        assertEquals("distant photo color remains intact", colors[6], bitmap.getPixel(6, 0))
    }

    @Test fun previewAndExportUseSameSnapAndPersistStrength() {
        val context = RuntimeEnvironment.getApplication()
        PlanStore.init(context)
        val source = Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(246, 248, 250))
        canvas.drawText("SCREENSHOT 文字边缘", 30f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 32f; color = Color.rgb(42, 40, 43)
        })
        canvas.drawRect(32f, 150f, 496f, 220f, Paint().apply { color = Color.rgb(236, 15, 24) })
        canvas.drawRect(32f, 260f, 496f, 400f, Paint().apply { color = Color.rgb(120, 80, 170) })
        val file = File(context.cacheDir, "snap-source.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val edit = PhotoEdit.Params(snapThreshold = 60)
        val preview = PhotoEdit.renderBitmap(source, edit)
        val exported = PhotoCard.render(file.absolutePath, PhotoCard.Fields(), edit)
        assertTrue("snapped preview must match exported card", preview.sameAs(exported))
        assertEquals(Color.WHITE, exported.getPixel(0, 0))
        assertEquals(Color.RED, exported.getPixel(100, 180))
        PlanStore.photoEdit = edit.copy(zoom = 1.5f, dx = .4f)
        PlanStore.init(context)
        assertEquals(edit.copy(zoom = 1.5f, dx = .4f), PlanStore.photoEdit)
        val output = File("build/reports/card-previews").apply { mkdirs() }
        listOf("snap-before" to source, "snap-after" to exported).forEach { (name, bitmap) ->
            File(output, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun multiImageShareIsHandledAsOneBatch() {
        val first = Uri.parse("content://photos/one")
        val second = Uri.parse("content://photos/two")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, first, second))
        val received = mutableListOf<List<Uri>>()
        handleSharedIntent(intent, { received.add(it) }, { fail("unexpected text share") })
        assertEquals(listOf(listOf(first, second)), received)
    }
}
