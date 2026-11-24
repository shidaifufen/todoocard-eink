package com.shidaifufen.todocard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Web 版每日一句与今日诗词的轻量 Android 接入。失败时交给界面保留现有内容。 */
object ContentData {
    data class Quote(val text: String, val author: String)
    data class Poem(
        val title: String,
        val author: String,
        val dynasty: String,
        val lines: List<String>,
        val note: String,
    )

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "TodooCard Android")
        return try {
            check(connection.responseCode in 200..299) { "内容服务暂时不可用" }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    suspend fun quote(): Quote = withContext(Dispatchers.IO) {
        parseQuote(getJson("https://v1.hitokoto.cn/?encode=json&max_length=60&t=${System.currentTimeMillis()}"))
    }

    internal fun parseQuote(data: JSONObject): Quote {
        val text = data.optString("hitokoto").trim()
        check(text.isNotBlank()) { "没有拿到句子" }
        val author = listOf(data.optString("from_who"), data.optString("from"))
            .firstOrNull { it.isNotBlank() && it != "null" } ?: "佚名"
        return Quote(text, author)
    }

    suspend fun poem(): Poem = withContext(Dispatchers.IO) {
        parsePoem(getJson("https://v2.jinrishici.com/one.json?t=${System.currentTimeMillis()}"))
    }

    internal fun parsePoem(data: JSONObject): Poem {
        val origin = data.optJSONObject("data")?.optJSONObject("origin")
            ?: error("没有拿到诗词")
        val lines = mutableListOf<String>()
        val content = origin.optJSONArray("content")
        if (content != null) for (i in 0 until content.length()) {
            content.optString(i).trim().takeIf { it.isNotBlank() }?.let(lines::add)
        }
        if (lines.isEmpty()) {
            origin.optString("content").split("。", "！", "？")
                .map(String::trim).filter(String::isNotBlank).forEach(lines::add)
        }
        check(lines.isNotEmpty()) { "没有拿到诗句" }
        val translation = origin.optJSONArray("translate")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }
        }.orEmpty()
        val author = origin.optString("author").trim()
        val dynasty = origin.optString("dynasty").trim()
        val note = translation.joinToString(" ").takeIf { it.isNotBlank() }
            ?: ""
        return Poem(origin.optString("title").ifBlank { "今日诗词" }, author, dynasty, lines.take(80), note)
    }

    suspend fun analysis(ctx: android.content.Context, card: Render.Card): String = withContext(Dispatchers.IO) {
        val prompt = "为《${card.poemTitle}》${card.poemBy}写70到110字赏析，说明景物、手法、情感，不编造背景，不加标题。原文：${card.poemLines.joinToString(" / ")}"
        val config = AiConfig.load(ctx)
        val result = if (config.key.isNotBlank()) {
            val body = JSONObject().put("model", config.model).put("max_tokens", 350)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            AiRequests.post(config, "chat/completions", body).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
        } else {
            val url = "https://text.pollinations.ai/" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") + "?seed=${System.nanoTime()}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            try {
                check(c.responseCode in 200..299) { "免费赏析服务暂不可用，可手动编辑或在设置中配置文本接口" }
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }
        check(result.trim().length >= 20 && !result.trim().startsWith("<")) { "赏析内容无效，已保留原内容" }
        result.replace("**", "").trim().take(400)
    }
}
