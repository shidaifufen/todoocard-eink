package com.shidaifufen.todocard

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 计划和手动入口共用数据准备规则，API 失败时保留已保存内容。 */
object CardSources {
    private val gate = kotlinx.coroutines.sync.Mutex()
    private fun resources(kind: String, card: Render.Card = Render.Card()) = when (kind) {
        "summary" -> listOf("weather", "quote")
        "custom" -> card.customBlocks.filter { it.enabled }.map { it.type }.filter { it in listOf("weather", "quote", "poem") }.distinct()
        "weather", "weather2" -> listOf("weather")
        "quote", "poem" -> listOf(kind)
        else -> emptyList()
    }
    private fun key(resource: String, card: Render.Card) = if (resource == "weather")
        "weather:" + card.weatherLatitude + ":" + card.weatherLongitude else resource
    private fun expired(ctx: Context, resource: String, card: Render.Card): Boolean {
        val at = ctx.getSharedPreferences("source_cache_v2", 0).getLong(key(resource, card), 0)
        val ttl = if (resource == "weather") 20 * 60_000L else 24 * 60 * 60_000L
        return System.currentTimeMillis() - at !in 0 until ttl
    }
    fun stale(ctx: Context, kind: String) = resources(kind, PlanStore.card(ctx)).any { expired(ctx, it, PlanStore.card(ctx)) }

    /** Only merge fetched fields; preserve edits made while requests were in flight. */
    fun merge(current: Render.Card, data: Render.Card, kind: String): Render.Card = when (kind) {
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
    fun mergeUnchanged(current: Render.Card, requested: Render.Card, fetched: Render.Card, kind: String): Render.Card {
        fun same(resource: String) = when (resource) {
            "quote" -> current.quote == requested.quote && current.quoteBy == requested.quoteBy
            "poem" -> current.poemTitle == requested.poemTitle && current.poemBy == requested.poemBy &&
                current.poemLines == requested.poemLines && current.poemNote == requested.poemNote
            "weather" -> current.weatherCity == requested.weatherCity &&
                current.weatherLatitude == requested.weatherLatitude && current.weatherLongitude == requested.weatherLongitude
            else -> false
        }
        var result = current
        for (resource in resources(kind, requested)) if (same(resource)) result = merge(result, fetched, resource)
        return result
    }

    suspend fun fresh(ctx: Context, kind: String, card: Render.Card, force: Boolean = false): Render.Card {
        if (!ContentOptions.online(ctx, kind)) return card
        val requestedAt = System.currentTimeMillis()
        gate.lock()
        try {
            var result = card
            for (resource in resources(kind, card)) {
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
                val latest = PlanStore.card(ctx)
                PlanStore.saveCard(ctx, mergeUnchanged(latest, card, result, resource))
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

    suspend fun scheduledBitmap(ctx: Context, kind: String): Bitmap {
        if (kind == "bing" || kind == "ai") {
            val path = if (kind == "bing") ImageSources.bing(ctx) else ImageSources.cached(ctx, kind)
            check(path.isNotBlank()) { "请先生成一张 AI 图片" }
            return withContext(Dispatchers.IO) { PhotoCard.render(path, PlanStore.photoFields,
                PhotoEdit.Params(snapThreshold = PlanStore.photoEdit.snapThreshold)) }
        }
        if (kind == "photo") return withContext(Dispatchers.IO) {
            val uri = AlbumStore.randomImage(ctx, PlanStore.albums(ctx), PlanStore.lastDrawnUri)
            if (uri != null) PlanStore.lastDrawnUri = uri.toString()
            val path = uri?.let { PhotoCard.importToFiles(ctx, it) } ?: PlanStore.lastPhoto
            check(path.isNotBlank()) { "轮播相册没有可用照片，请重新授权或添加照片" }
            try { PhotoCard.render(path, PlanStore.photoFields,
                if (uri == null) PlanStore.photoEdit else PhotoEdit.Params(
                    snapThreshold = PlanStore.photoEdit.snapThreshold)) }
            finally { if (uri != null) java.io.File(path).delete() }
        }
        val card = fresh(ctx, kind, PlanStore.card(ctx))
        return withContext(Dispatchers.Default) { Render.render(card.copy(kind = kind)) }
    }
}

/** 只负责设备发现与选择，不涉及任何传输命令。 */
object DeviceDiscovery {
    fun matches(address: String) = address.startsWith("FF:FF", ignoreCase = true)
    fun filter(found: List<android.bluetooth.le.ScanResult>, all: Boolean) =
        found.filter { all || matches(it.device.address) }
    fun target(found: List<android.bluetooth.le.ScanResult>, saved: String) =
        if (saved.isNotBlank()) found.firstOrNull { it.device.address.equals(saved, true) }
        else found.firstOrNull { matches(it.device.address) }
}
