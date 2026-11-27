package com.shidaifufen.todocard

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 计划和手动入口共用数据准备规则，API 失败时保留已保存内容。 */
object CardSources {
    private val gate = kotlinx.coroutines.sync.Mutex()
    private fun resources(kind: String, card: Render.Card, template: LayoutPack.Template?) =
        LayoutStore.sources(kind, card, template)?.toList() ?: when (kind) {
        "summary" -> listOf("weather", "quote")
        "custom" -> card.customBlocks.filter { it.enabled }.map { it.type }.filter { it in listOf("weather", "quote", "poem") }.distinct()
        "weather", "weather2" -> listOf("weather")
        "quote", "poem" -> listOf(kind)
        else -> emptyList()
    }
    private fun key(resource: String, card: Render.Card) = if (resource == "weather")
        "weather:" + card.weatherLatitude + ":" + card.weatherLongitude else resource
    private fun expired(ctx: Context, resource: String, card: Render.Card): Boolean {
        if (resource == "history") return HistoryToday.cached(ctx)?.day != java.time.LocalDate.now()
        val at = ctx.getSharedPreferences("source_cache_v2", 0).getLong(key(resource, card), 0)
        val ttl = if (resource == "weather") 20 * 60_000L else 24 * 60 * 60_000L
        return System.currentTimeMillis() - at !in 0 until ttl
    }
    fun stale(ctx: Context, kind: String, template: LayoutPack.Template? = null): Boolean {
        val card = PlanStore.card(ctx)
        return resources(kind, card, template).any { expired(ctx, it, card) }
    }

    /** Only merge fetched fields; preserve edits made while requests were in flight. */
    fun merge(current: Render.Card, data: Render.Card, kind: String): Render.Card = when (kind) {
        "history" -> current.copy(history = data.history)
        "quote" -> current.copy(quote = data.quote, quoteBy = data.quoteBy)
        "poem" -> current.copy(poemTitle = data.poemTitle, poemBy = data.poemBy,
            poemLines = data.poemLines, poemNote = data.poemNote)
        "summary" -> merge(merge(current, data, "weather"), data, "quote")
        "weather", "weather2" -> current.copy(weatherCity = data.weatherCity,
            weatherText = data.weatherText, weatherTemp = data.weatherTemp,
            weatherHigh = data.weatherHigh, weatherLow = data.weatherLow,
            weatherAqi = data.weatherAqi, weatherForecast = data.weatherForecast,
            weatherLatitude = data.weatherLatitude, weatherLongitude = data.weatherLongitude,
            weatherUpdated = data.weatherUpdated, weatherAuto = data.weatherAuto,
            weatherHumidity = data.weatherHumidity, weatherFeels = data.weatherFeels,
            weatherSunrise = data.weatherSunrise, weatherSunset = data.weatherSunset,
            weatherCode = data.weatherCode, weatherHours = data.weatherHours)
        else -> current
    }

    /** Do not apply a background result to a field the user edited while it was loading. */
    fun mergeUnchanged(current: Render.Card, requested: Render.Card, fetched: Render.Card, kind: String,
                       template: LayoutPack.Template? = null): Render.Card =
        mergeResources(current, requested, fetched, resources(kind, requested, template))

    private fun mergeResources(current: Render.Card, requested: Render.Card, fetched: Render.Card,
                               resources: List<String>): Render.Card {
        fun same(resource: String) = when (resource) {
            "history" -> true
            "quote" -> current.quote == requested.quote && current.quoteBy == requested.quoteBy
            "poem" -> current.poemTitle == requested.poemTitle && current.poemBy == requested.poemBy &&
                current.poemLines == requested.poemLines && current.poemNote == requested.poemNote
            "weather" -> current.weatherCity == requested.weatherCity &&
                current.weatherLatitude == requested.weatherLatitude && current.weatherLongitude == requested.weatherLongitude &&
                current.weatherAuto == requested.weatherAuto
            else -> false
        }
        var result = current
        for (resource in resources) if (same(resource)) result = merge(result, fetched, resource)
        return result
    }

    suspend fun fresh(ctx: Context, kind: String, card: Render.Card, force: Boolean = false,
                      template: LayoutPack.Template? = null,
                      online: () -> Boolean = { ContentOptions.online(ctx, kind) }): Render.Card {
        if (!online()) return card
        val requestedAt = System.currentTimeMillis()
        gate.lock()
        try {
            var result = card
            for (resource in resources(kind, card, template)) {
                if (resource == "history") {
                    result = result.copy(history = HistoryToday.update(ctx, force))
                    continue
                }
                val saved = PlanStore.card(ctx)
                val prefs = ctx.getSharedPreferences("source_cache_v2", 0)
                // Requests already completed after this caller started are shared, including manual refreshes.
                val at = prefs.getLong(key(resource, result), 0)
                if ((!force && !expired(ctx, resource, result)) || at >= requestedAt) {
                    result = merge(result, saved, resource)
                    continue
                }
                result = when (resource) {
                    "quote" -> nextQuote(ctx).let { result.copy(quote = it.text, quoteBy = it.author) }
                    "poem" -> nextPoem(ctx).let { result.copy(poemTitle = it.title,
                        poemBy = listOf(it.dynasty, it.author).filter(String::isNotBlank).joinToString(" · "),
                        poemLines = it.lines, poemNote = it.note) }
                    else -> WeatherData.refresh(result)
                }
                if (!online()) return card
                val latest = PlanStore.card(ctx)
                // Persist only the resource that just finished, even if its own layout has other dependencies.
                PlanStore.saveCard(ctx, mergeResources(latest, card, result, listOf(resource)))
                prefs.edit().putLong(key(resource, result), System.currentTimeMillis()).apply()
            }
            return result
        } finally { gate.unlock() }
    }

    suspend fun nextQuote(ctx: Context): ContentData.Quote {
        repeat(6) {
            val quote = ContentData.quote()
            if (ContentOptions.claim(ctx, "quote", quote.text)) {
                return quote
            }
        }
        error("句子服务连续返回近期内容，请稍后再换；没有替换为本地句子")
    }

    suspend fun nextPoem(ctx: Context): ContentData.Poem {
        repeat(6) {
            val poem = ContentData.poem()
            val identity = poem.title + poem.author
            if (ContentOptions.claim(ctx, "poem", identity)) {
                return poem
            }
        }
        error("诗词服务连续返回近期作品，请稍后再换；没有替换为本地诗词")
    }

    suspend fun scheduledBitmap(ctx: Context, kind: String, requireFresh: Boolean = false,
                                onNotice: (String) -> Unit = {}): Bitmap {
        if (kind.startsWith("cloud:")) return CloudBoards.scheduledBitmap(ctx, kind, onNotice)
        if (OnlinePlanCategories.any { it.first == kind })
            return randomOnlineCard(ctx, kind.removePrefix("online_"), requireFresh, onNotice)
        if (kind == "online") return withContext(Dispatchers.IO) {
            checkNotNull(OnlineCard.read(ctx)) { "请先生成在线卡片" }
        }
        if (kind == "bing" || kind == "ai") {
            val path = if (kind == "bing") ImageSources.bing(ctx) else ImageSources.cached(ctx, kind)
            check(path.isNotBlank()) { "请先生成一张 AI 图片" }
            return withContext(Dispatchers.IO) { PhotoCard.render(path, PlanStore.photoFields,
                PlanStore.defaultPhotoTone) }
        }
        if (kind == "photo") return withContext(Dispatchers.IO) {
            val excluded = if (requireFresh) WidgetShuffle.lastItem(ctx, kind) else PlanStore.lastDrawnUri
            val uri = AlbumStore.randomImage(ctx, PlanStore.albums(ctx), excluded,
                allowRepeat = !requireFresh)
            if (requireFresh) checkNotNull(uri) { "轮播图库里没有其他可用照片" }
            if (uri != null) PlanStore.lastDrawnUri = uri.toString()
            val path = uri?.let { PhotoCard.importToFiles(ctx, it) } ?: PlanStore.lastPhoto
            check(path.isNotBlank()) { "轮播相册没有可用照片，请重新授权或添加照片" }
            try {
                val fields = PlanStore.photoFields
                PhotoCard.resolvePlace(ctx, path, fields)
                PhotoCard.render(path, fields,
                    if (uri == null) PlanStore.photoEdit.withTone(PlanStore.defaultPhotoTone) else PlanStore.defaultPhotoTone)
                    .also { if (requireFresh && uri != null) WidgetShuffle.rememberItem(ctx, kind, uri.toString()) }
            }
            finally {
                if (uri != null) {
                    java.io.File(path).delete()
                    java.io.File(path + ".place").delete()
                }
            }
        }
        val card = if (requireFresh) {
            check(ContentOptions.online(ctx, kind)) { "${templateName(kind)}已设为手动内容" }
            fresh(ctx, kind, PlanStore.card(ctx), force = true)
        } else scheduledCard(ctx, kind, onNotice)
        return withContext(Dispatchers.Default) { Render.render(card.copy(kind = kind)) }
    }

    internal suspend fun scheduledCard(ctx: Context, kind: String, onNotice: (String) -> Unit = {},
        template: LayoutPack.Template? = null,
        fetch: suspend (Context, String, Render.Card, Boolean) -> Render.Card =
            { context, key, card, force -> fresh(context, key, card, force, template) }): Render.Card {
        // Every scheduled send refreshes online content, even inside the normal UI cache TTL.
        val saved = PlanStore.card(ctx).copy(history = HistoryToday.cached(ctx))
        return try { fetch(ctx, kind, saved, true) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            onNotice("在线更新失败，使用已保存内容：${error.message.orEmpty().take(100)}")
            PlanStore.card(ctx).copy(history = HistoryToday.cached(ctx))
        }
    }
}

/** 只负责设备发现与选择，不涉及任何传输命令。 */
object DeviceDiscovery {
    fun matches(address: String) = address.startsWith("FF:FF", ignoreCase = true)
    internal fun matchesTarget(address: String, saved: String) =
        if (saved.isNotBlank()) address.equals(saved, true) else matches(address)
    fun filter(found: List<android.bluetooth.le.ScanResult>, all: Boolean) =
        found.filter { all || matches(it.device.address) }
    fun target(found: List<android.bluetooth.le.ScanResult>, saved: String) =
        found.firstOrNull { matchesTarget(it.device.address, saved) }
}
