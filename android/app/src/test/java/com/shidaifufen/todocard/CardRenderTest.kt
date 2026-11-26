package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardRenderTest {
    @Test fun photoShows35mmEquivalentAndReadableExposure() {
        val path = File(RuntimeEnvironment.getApplication().cacheDir, "photo-metadata.jpg")
        path.outputStream().use { Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:26 09:45:00")
            setAttribute(ExifInterface.TAG_MAKE, "Xiaomi")
            setAttribute(ExifInterface.TAG_MODEL, "Xiaomi 14 Ultra")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "541/100")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "23")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.6")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.004")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "50")
            setLatLong(31.8, 117.28)
            saveAttributes()
        }
        assertEquals("23mm 等效  f/1.6  1/250s  ISO 50", PhotoCard.cameraParameters(path.absolutePath))
        assertEquals("31.8000°, 117.2800°", PhotoCard.embeddedLocation(path.absolutePath))
        val card = PhotoCard.render(path.absolutePath, PhotoCard.Fields(), PhotoEdit.Params(gamma = 1.2f, sharpness = .3f))
        val top = Render.H - PhotoCard.bandHeight(path.absolutePath, PhotoCard.Fields())
        fun headerHasText(columns: IntRange) =
            (top + 4..top + 32).any { y -> columns.any { x -> card.getPixel(x, y) == Color.BLACK } }
        assertTrue("date on the left", headerHasText(24..246))
        assertTrue("camera on the right", headerHasText(300..504))
        val encoded = java.io.ByteArrayOutputStream().apply { card.compress(Bitmap.CompressFormat.PNG, 100, this) }
        val png = encoded.toByteArray()
        val restored = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        val controller = Payload.build(restored).controller
        for (y in top until Render.H) for (x in 0 until Render.W) {
            val pixel = card.getPixel(x, y)
            assertTrue(pixel == Color.WHITE || pixel == Color.BLACK)
            val index = (Render.W - 1 - x) * Render.H + y
            val packed = controller[index / 2].toInt() and 255
            val color = if (index % 2 == 0) packed ushr 4 else packed and 15
            assertEquals(if (pixel == Color.WHITE) 1 else 0, color)
            if (y > top + 1 && (x < 8 || x >= Render.W - 8 || y >= Render.H - 8)) {
                assertEquals("blank photo-band margins", Color.WHITE, pixel)
            }
        }
        save("photo-information-band", card)
    }

    @Before fun defaultLayouts() {
        LayoutStore.init(RuntimeEnvironment.getApplication())
        LayoutStore.reset(RuntimeEnvironment.getApplication())
    }
    private val output = File("build/reports/card-previews").apply { mkdirs() }

    private fun save(name: String, bitmap: Bitmap) {
        File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun exportAllCardsAndKeepTextInsidePage() {
        val sample = Render.Card(
            todos = listOf("完成今天最重要的一件事", "读完手边的一章书", "出门散步二十分钟", "整理明天的安排"),
            quote = "长风破浪会有时，直挂云帆济沧海。",
            quoteBy = "李白《行路难》",
            countdowns = listOf("新年" to "2027-01-01", "一场期待已久的旅行" to "2027-03-20"),
            customLines = listOf("留一点时间给自己。", "记录生活里值得记住的小事。", "今天也要好好生活。"),
            weatherCity = "深圳", weatherTemp = "26", weatherText = "多云",
            weatherHigh = "28", weatherLow = "22", weatherAqi = "42",
            weatherForecast = listOf("周一 晴 22~28°", "周二 多云 21~27°", "周三 小雨 20~25°"),
            weatherUpdated = "11-24 08:00", weatherCode = 2,
            weatherHours = (0..5).map { Render.WeatherHour("${it + 9}:00", 20f + it, 2) },
            weatherHumidity = "60", weatherFeels = "27", weatherSunrise = "06:40", weatherSunset = "17:35",
        )
        listOf("summary", "todo", "quote", "progress", "countdown", "poem", "custom", "calendar", "weather", "weather2")
            .forEach { kind ->
                val bitmap = Render.render(sample.copy(kind = kind))
                assertEquals(528, bitmap.width)
                assertEquals(792, bitmap.height)
                save(kind, bitmap)
                // 页脚下方保持空白，长文字不可越过底边。待办的黄色顶栏除外。
                for (x in 0 until bitmap.width) assertEquals("$kind bottom x=$x",
                    Color.WHITE, bitmap.getPixel(x, 791))
            }
        save("custom-composition", Render.render(sample.copy(kind = "custom", customComposition = true,
            customBlocks = listOf(Render.CustomBlock(type = "bigdate", size = 44f),
                Render.CustomBlock(type = "weather"), Render.CustomBlock(type = "rule"),
                Render.CustomBlock(type = "quote", centered = true), Render.CustomBlock(type = "todos")))))
        save("poem-long", Render.render(sample.copy(kind = "poem",
            poemLines = List(6) { "长长的诗句也应该在卡片的边界之内正确排版，不能盖住赏析和页脚。" },
            poemNote = "这是一段较长的赏析文字，用于检查正文与页脚之间的留白。".repeat(6))))
    }

    @Test fun cropPreviewMatchesExportAndHonorsExif() {
        val source = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.RED)
        canvas.drawRect(450f, 0f, 900f, 600f, android.graphics.Paint().apply { color = Color.BLUE })
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "crop-source.jpg")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val decoded = PhotoEdit.decodePreview(file.absolutePath)!!
        assertTrue("EXIF rotation makes source portrait", decoded.height > decoded.width)
        val edit = PhotoEdit.Params(zoom = 1.8f, dx = .4f, dy = -.3f, exposure = .2f)
        val fast = Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888)
        PhotoEdit.drawInto(Canvas(fast), decoded, edit)
        val exported = PhotoEdit.render(file.absolutePath, edit)
        assertTrue("interactive preview and exported crop must match", fast.sameAs(exported))
        save("photo-crop", exported)
    }

    @Test fun hourlyTrendBelongsToWeatherDashboardOnly() {
        val hours = (0..5).map { Render.WeatherHour("${it + 9}:00", 20f + it, 2) }
        val dashboard = Render.render(Render.Card(kind = "weather", weatherHours = hours))
        val blocks = Render.render(Render.Card(kind = "weather2", weatherHours = hours))
        assertTrue("weather dashboard shows a blue temperature trend", (540..610).any { y ->
            (32 until 496).any { x -> dashboard.getPixel(x, y) == Color.BLUE }
        })
        assertEquals("sunrise panel fills the former bottom gap", Color.BLUE, blocks.getPixel(50, 705))
        assertTrue("source footer joins the panels", (735..739).any { blocks.getPixel(100, it) == Color.BLACK })
        assertEquals("color block grid extends to the bottom margin", Color.BLACK, blocks.getPixel(100, 777))
    }

    @Test fun threeDayForecastDisplaysTemperatureBarsAndHandlesNegativeTemperatures() {
        val parsed = WeatherVisuals.parseDayRange("11-25 小雨 -3~6°")!!
        assertEquals("11-25 小雨", parsed.label)
        assertEquals(-3, parsed.low)
        assertEquals(6, parsed.high)
        val forecasts = listOf("11-25 小雨 -3~6°", "11-26 晴 1~9°", "11-27 多云 3~10°")
        val card = Render.render(Render.Card(kind = "weather", weatherForecast = forecasts))
        assertTrue((670..733).any { y -> (210..375).any { x -> card.getPixel(x, y) == Color.BLUE } })
        val blocks = Render.render(Render.Card(kind = "weather2", weatherForecast = forecasts))
        assertTrue("color block forecast uses the same range chart", (425..520).any { y ->
            (210..375).any { x -> blocks.getPixel(x, y) == Color.BLUE }
        })
        assertNull(WeatherVisuals.parseDayRange("手动填写的天气"))
    }

    @Test fun weatherChartsScaleTheirLabelsAndKeepSolidPanelColors() {
        val hours = (0..5).map { Render.WeatherHour("${19 + it}:00", 11f - it, 3) }
        fun chart(scale: Float): Bitmap {
            val bitmap = Bitmap.createBitmap(Render.W, 160, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            WeatherVisuals.chart(canvas, hours, 0f, 160f, fontScale = scale)
            return bitmap
        }
        val small = chart(.8f)
        val large = chart(1.3f)
        for (area in listOf(0..34, 130..159)) {
            fun ink(image: Bitmap) = area.sumOf { y -> (0 until Render.W).count { image.getPixel(it, y) == Color.BLACK } }
            assertTrue(ink(large) > ink(small))
        }
        val blocks = Render.render(Render.Card(kind = "weather2", weatherAqi = "207"))
        assertEquals(Color.WHITE, blocks.getPixel(10, 400))
        assertEquals(Color.BLACK, blocks.getPixel(14, 400))
        val pixels = IntArray(Render.W * Render.H)
        blocks.getPixels(pixels, 0, Render.W, 0, 0, Render.W, Render.H)
        val palette = setOf(Color.WHITE, Color.BLACK, Color.RED, Color.YELLOW, Color.BLUE, Color.GREEN)
        assertTrue(pixels.all { it in palette })
    }

    @Test fun editedContentSurvivesRestart() {
        val context = RuntimeEnvironment.getApplication()
        PlanStore.init(context)
        val card = Render.Card(todos = listOf("工作", "生活"), weatherCity = "深圳",
            weatherTemp = "26", weatherForecast = listOf("明天 晴 22~28°"),
            weatherCode = 2, weatherHours = listOf(Render.WeatherHour("09:00", 24f, 2)),
            customBlocks = listOf(Render.CustomBlock(id = "a", type = "quote"),
                Render.CustomBlock(id = "b", type = "text", enabled = false)), fontScale = 1.2f)
        PlanStore.saveCard(context, card)
        PlanStore.init(context)
        assertEquals(card, PlanStore.card(context))
    }

    @Test fun photoLocationNotesStayWithTheirPhoto() {
        val dir = RuntimeEnvironment.getApplication().cacheDir
        val first = File(dir, "location-first.jpg").absolutePath
        val second = File(dir, "location-second.jpg").absolutePath
        PhotoCard.saveLocationNote(first, "深圳湾")
        assertEquals("深圳湾", PhotoCard.location(first))
        assertEquals("", PhotoCard.location(second))
        PhotoCard.saveLocationNote(first, "")
        assertEquals("", PhotoCard.locationNote(first))
    }

    @Test fun backgroundRefreshPreservesEditedFields() {
        val requested = Render.Card(weatherCity = "深圳", quote = "旧句子")
        val edited = requested.copy(weatherCity = "广州")
        val fetched = requested.copy(weatherCity = "深圳", weatherTemp = "26", quote = "新句子")
        val merged = CardSources.mergeUnchanged(edited, requested, fetched, "summary")
        assertEquals("广州", merged.weatherCity)
        assertEquals("新句子", merged.quote)
    }

    @Test fun scheduledSendRefreshesInsideCacheTtlAndKeepsSavedCardOnFailure() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        PlanStore.saveCard(ctx, Render.Card(quote = "先前内容"))
        val updated = CardSources.scheduledCard(ctx, "quote", fetch = { _, kind, card, force ->
            assertEquals("quote", kind)
            assertTrue("schedule must bypass the UI cache TTL", force)
            card.copy(quote = "发送前的新内容")
        })
        assertEquals("发送前的新内容", updated.quote)
        var notice = ""
        val fallback = CardSources.scheduledCard(ctx, "quote", onNotice = { notice = it },
            fetch = { _, _, _, _ -> error("offline") })
        assertEquals("先前内容", fallback.quote)
        assertTrue(notice.contains("使用已保存内容"))
    }

    @Test fun historyRetainsExactImageAndReplayMetadataAndPrunesExpiredImages() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("plan", 0).edit().remove("hist").commit()
        val bitmap = Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val snapshot = HistoryCards.save(ctx, bitmap)
        assertEquals("identical cards share one file", snapshot, HistoryCards.save(ctx, bitmap))
        PlanStore.addHistory(ctx, PlanStore.Hist(1, "photo", true, 3, "sent", snapshot, true))
        val restored = PlanStore.history(ctx).single()
        assertEquals(snapshot, restored.snapshot)
        assertTrue(restored.replay)
        val decoded = android.graphics.BitmapFactory.decodeFile(HistoryCards.file(ctx, snapshot)!!.absolutePath)
        assertTrue(bitmap.sameAs(decoded))
        assertNull(HistoryCards.file(ctx, "../card.png"))
        repeat(200) { PlanStore.addHistory(ctx, PlanStore.Hist(it + 2L, "quote", true, 1, "sent")) }
        assertEquals(200, PlanStore.history(ctx).size)
        assertNull(HistoryCards.file(ctx, snapshot))
    }
}
