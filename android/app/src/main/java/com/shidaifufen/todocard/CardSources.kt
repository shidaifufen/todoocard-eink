package com.shidaifufen.todocard

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 计划和手动入口共用数据准备规则，API 失败时保留已保存内容。 */
object CardSources {
    suspend fun fresh(ctx: Context, kind: String, card: Render.Card): Render.Card {
        if (!ContentOptions.online(ctx, kind)) return card
        val refreshed = when (kind) {
            "quote" -> nextQuote(ctx).let { card.copy(quote = it.text, quoteBy = it.author) }
            "poem" -> nextPoem(ctx).let {
                val poem = card.copy(poemTitle = it.title,
                    poemBy = listOf(it.dynasty, it.author).filter(String::isNotBlank).joinToString(" · "),
                    poemLines = it.lines, poemNote = it.note)
                if (it.note.isNotBlank()) poem else poem.copy(poemNote =
                    runCatching { ContentData.analysis(ctx, poem) }.getOrDefault(""))
            }
            "weather", "weather2" -> WeatherData.refresh(card)
            "summary" -> {
                val weather = WeatherData.refresh(card)
                val quote = nextQuote(ctx)
                weather.copy(quote = quote.text, quoteBy = quote.author)
            }
            else -> card
        }
        if (kind in listOf("summary", "quote", "poem", "weather", "weather2")) ctx.getSharedPreferences("content_refresh", 0).edit()
            .putString(kind, java.time.LocalDate.now().toString()).apply()
        PlanStore.saveCard(ctx, refreshed)
        return refreshed
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
