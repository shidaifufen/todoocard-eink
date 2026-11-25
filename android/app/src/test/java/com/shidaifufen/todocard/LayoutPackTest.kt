package com.shidaifufen.todocard

import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutPackTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun pack(kind: String = "weather2", fill: String = "red") = LayoutPack.parse("""{
        "schemaVersion":1,"name":"测试版式","templates":{"$kind":{"nodes":[
        {"type":"rect","x":32,"y":32,"width":464,"height":728,"fill":"$fill"}]}}} """)

    @Before fun setup() { LayoutStore.init(ctx); LayoutStore.reset(ctx) }
    @After fun cleanup() { LayoutStore.reset(ctx) }

    @Test fun importingChangesOnlyLayoutAndBothPushPathsUseIt() = runBlocking {
        PlanStore.init(ctx)
        val card = Render.Card(weatherCity = "深圳", quote = "用户保存的内容")
        PlanStore.saveCard(ctx, card)
        PlanStore.deviceAddress = "FF:FF:00:01:02:03"
        PlanStore.saveFixed(ctx, listOf(PlanStore.Fixed("08:00", "weather2")))
        ContentOptions.setOnline(ctx, "weather2", false)
        val oldQuote = LayoutStore.template("quote")
        LayoutStore.apply(ctx, pack())
        val manual = Render.render(card.copy(kind = "weather2"))
        val scheduled = CardSources.scheduledBitmap(ctx, "weather2")
        assertEquals(Color.RED, manual.getPixel(100, 700))
        assertTrue("scheduled and manual render the imported layout", manual.sameAs(scheduled))
        assertEquals(card, PlanStore.card(ctx))
        assertEquals("FF:FF:00:01:02:03", PlanStore.deviceAddress)
        assertEquals("08:00", PlanStore.fixed(ctx).single().time)
        assertEquals(oldQuote, LayoutStore.template("quote"))
        val exported = LayoutPack.parse(LayoutStore.export(ctx))
        assertEquals(LayoutPack.kinds, exported.templates.keys)
        assertFalse(exported.json.contains("FF:FF"))
        assertFalse(exported.json.contains("用户保存的内容"))
        LayoutStore.reload(ctx)
        assertEquals(Color.RED, Render.render(card.copy(kind = "weather2")).getPixel(100, 700))
    }

    @Test fun partialPacksMergeAndUndoRestoresPreviousPack() {
        LayoutStore.apply(ctx, pack(fill = "blue"))
        LayoutStore.apply(ctx, pack(kind = "quote", fill = "green"))
        assertEquals(setOf("weather2", "quote"), LayoutStore.changedKinds())
        assertTrue(LayoutStore.canUndo(ctx))
        LayoutStore.undo(ctx)
        assertEquals(setOf("weather2"), LayoutStore.changedKinds())
        assertEquals(Color.BLUE, Render.render(Render.Card(kind = "weather2")).getPixel(100, 700))
        LayoutStore.reset(ctx)
        assertTrue(LayoutStore.changedKinds().isEmpty())
        LayoutStore.undo(ctx)
        assertEquals(setOf("weather2"), LayoutStore.changedKinds())
    }

    @Test fun invalidAndFuturePacksNeverReplaceActiveLayout() {
        LayoutStore.apply(ctx, pack())
        val before = LayoutStore.export(ctx)
        val valid = pack().json
        val invalids = listOf(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":9"),
            valid.replace("\"rect\"", "\"script\""), valid.replace("\"width\":464", "\"width\":600"),
            valid.replace("\"fill\":\"red\"", "\"fill\":\"gray\""),
            valid.replace("\"fill\":\"red\"", "\"fill\":\"red\",\"url\":\"https://example.com\""),
            " ".repeat(LayoutPack.MAX_BYTES + 1))
        invalids.forEach { raw -> assertTrue(runCatching { LayoutPack.parse(raw).also { LayoutStore.apply(ctx, it) } }.isFailure) }
        assertEquals(before, LayoutStore.export(ctx))
    }

    @Test fun dependenciesFollowBindingsAndImportedComposition() {
        val value = LayoutPack.parse("""{"schemaVersion":1,"templates":{"quote":{"nodes":[
            {"type":"text","text":"{{quote}} · {{weatherTemp}}°","x":32,"y":32,"width":464,"height":500},
            {"type":"block","blockType":"poem","x":32,"y":550,"width":464,"height":200}]}}}""")
        LayoutStore.apply(ctx, value)
        assertEquals(setOf("quote", "weather", "poem"), LayoutStore.sources("quote"))
        val requested = Render.Card(quote = "旧句", weatherCity = "深圳")
        val fetched = requested.copy(quote = "新句", weatherTemp = "26", poemTitle = "新诗")
        val merged = CardSources.mergeUnchanged(requested, requested, fetched, "quote")
        assertEquals("新句", merged.quote)
        assertEquals("26", merged.weatherTemp)
        assertEquals("新诗", merged.poemTitle)
        assertTrue(runCatching { LayoutPack.parse(value.json.replace("{{quote}}", "{{invalidField}}")) }.isFailure)
    }

    @Test fun textIsClippedToItsBoxAndDefaultWeatherIsDataDriven() {
        val raw = """{"schemaVersion":1,"templates":{"quote":{"nodes":[{"type":"text",
            "x":100,"y":100,"width":100,"height":50,"text":"{{quote}}","shrink":false,"textSize":60}]}}}"""
        val image = LayoutRender.render(Render.Card(quote = "很长的内容".repeat(100)), LayoutPack.parse(raw).templates.getValue("quote"))
        assertEquals(Color.WHITE, image.getPixel(99, 120))
        assertEquals(Color.WHITE, image.getPixel(201, 120))
        assertEquals(Color.WHITE, image.getPixel(120, 151))
        val defaults = LayoutPack.parse(LayoutStore.export(ctx, defaultsOnly = true))
        assertFalse(defaults.templates.getValue("weather").nodes.any { it.type == "builtin" })
        assertFalse(defaults.templates.getValue("weather2").nodes.any { it.type == "builtin" })
        val roundTrip = LayoutPack.parse(defaults.json)
        assertEquals(defaults, roundTrip)
    }

    @Test fun summaryAndWeatherSpacingSlidersChangeTheRenderedCard() {
        for (kind in listOf("summary", "weather", "weather2")) {
            val card = Render.Card(kind = kind, weatherCity = "深圳", weatherTemp = "26",
                weatherForecast = listOf("今天 晴 18~26°", "明天 多云 20~28°", "后天 阴 21~29°"),
                todos = listOf("整理计划", "收拾书桌", "看一章书"))
            val standard = Render.render(card)
            val tightLines = Render.render(card.copy(lineSpacing = .8f))
            val wideElements = Render.render(card.copy(elementSpacing = 1.4f))
            assertFalse("$kind 行间距", standard.sameAs(tightLines))
            assertFalse("$kind 元素间距", standard.sameAs(wideElements))
            listOf(standard, tightLines, wideElements).forEach { it.recycle() }
        }
    }

    @Test fun publishedExamplesAndBundledDefaultsStayCompatible() {
        val dir = java.io.File("../../layouts")
        val defaults = dir.resolve("default.json").readText()
        assertEquals(LayoutPack.parse(ctx.assets.open("layouts/default.json").bufferedReader().use { it.readText() }), LayoutPack.parse(defaults))
        for (name in listOf("weather-blue.json", "quote-weather.json", "daily-brief.json", "focus-list.json",
            "verse-almanac.json", "weather-field.json", "weather-tiles.json")) {
            val pack = LayoutPack.parse(dir.resolve(name).readText())
            pack.templates.forEach { (kind, template) ->
                val image = LayoutRender.render(Render.Card(kind = kind, weatherCity = "深圳", weatherTemp = "26"), template)
                val file = java.io.File("build/reports/card-previews", "layout-${name.removeSuffix(".json")}-$kind.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
        }
    }
}
