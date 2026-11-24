package com.shidaifufen.todocard

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import androidx.exifinterface.media.ExifInterface
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
class CardRenderTest {
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
}
