package com.shidaifufen.todocard

import android.content.Intent
import android.content.ClipData
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

    @Test fun renderedPoemUsesSolidWhiteAndInkThroughPayloadPreparation() {
        val ctx = RuntimeEnvironment.getApplication()
        PlanStore.init(ctx)
        val poem = Render.Card(kind = "poem", poemTitle = "秋风辞", poemBy = "西汉 · 刘彻",
            poemLines = listOf("秋风起兮白云飞，草木黄落兮雁南归。", "兰有秀兮菊有芳，怀佳人兮不能忘。",
                "泛楼船兮济汾河，横中流兮扬素波。", "箫鼓鸣兮发棹歌，欢乐极兮哀情多。", "少壮几时兮奈老何！"),
            poemNote = "秋风吹起，白云飞。草木枯黄，雁往南飞。思念的人久久不能忘。")
        val palette = setOf(Color.WHITE, Color.BLACK, Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)
        val image = Render.render(poem)
        try {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            assertTrue(pixels.all { it in palette })
            assertTrue(pixels.count { it == Color.WHITE } > pixels.size * .8)
            assertTrue(pixels.count { it == Color.BLACK } > 1000)
            assertTrue(pixels.count { it == Color.RED } > 100)
            val payloadPixels = Payload.quantize(image)
            for (i in pixels.indices) {
                if (pixels[i] != Color.WHITE) continue
                val packed = payloadPixels[i / 2].toInt() and 0xff
                val ink = if (i % 2 == 0) packed ushr 4 else packed and 0x0f
                if (ink != 1) fail("White pixel at index $i changed during quantization")
            }
            val out = File("build/reports/card-previews").apply { mkdirs() }
            File(out, "autumn-poem-clean.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }

    @Test fun imageSharesMergeClipItemsAndStreamExtras() {
        val first = Uri.parse("content://photos/one")
        val second = Uri.parse("content://photos/two")
        val clip = ClipData("photos", arrayOf("image/png"), ClipData.Item(first)).apply {
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(Uri.parse("https://example.com/not-a-local-image")))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first))
            .apply { clipData = clip }
        handleSharedIntent(intent, { assertEquals(listOf(first, second), it) }, { fail("unexpected text share") })
        assertEquals(listOf(first, second), sharedImageUris(emptyList(), clip))
    }

    @Test fun textCardFinishingSnapsAllOffPalettePixels() {
        val source = intArrayOf(Color.rgb(246, 248, 250), Color.rgb(40, 42, 44),
            Color.rgb(125, 70, 130), Color.rgb(250, 100, 100), Color.BLUE)
        val image = Bitmap.createBitmap(source, source.size, 1, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, true)
        PaletteSnap.finishCard(image)
        val actual = IntArray(source.size)
        image.getPixels(actual, 0, source.size, 0, 0, source.size, 1)
        assertArrayEquals(intArrayOf(Color.WHITE, Color.BLACK, Color.BLUE, Color.RED, Color.BLUE), actual)
    }
}
