package com.shidaifufen.todocard

import android.content.Context
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object HistoryToday {
    private val requests = Mutex()
    data class Event(val year: Int, val title: String, val description: String, val source: String, val day: LocalDate) {
        fun date(): String {
            val displayYear = if (year <= 0) "前${1L - year}" else year.toString()
            return "$displayYear.${day.toString().takeLast(5).replace('-', '.')}"
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("history_today", Context.MODE_PRIVATE)
    private fun decode(raw: String?, date: String?) = runCatching {
        val day = LocalDate.parse(date)
        val list = JSONArray(raw)
        (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            Event(item.getInt("year"), item.getString("title"), item.getString("description"), item.getString("source"), day)
        }
    }.getOrDefault(emptyList())

    fun cached(ctx: Context): Event? {
        val p = prefs(ctx)
        return decode(p.getString("events", null), p.getString("date", null)).getOrNull(p.getInt("index", 0))
    }

    suspend fun update(ctx: Context, force: Boolean, today: LocalDate = LocalDate.now()): Event =
        withContext(Dispatchers.IO) { requests.withLock {
            val p = prefs(ctx)
            val sameDay = p.getString("date", null) == today.toString()
            val stored = if (sameDay) decode(p.getString("events", null), today.toString()) else emptyList()
            val events = if (stored.isNotEmpty()) stored else {
                val month = "%02d".format(java.util.Locale.ROOT, today.monthValue)
                val url = "https://baike.baidu.com/cms/home/eventsOnHistory/$month.json"
                val chinese = runCatching { parseEvents(ContentData.getJson(url, 2 * 1024 * 1024), today) }
                chinese.getOrNull().orEmpty().ifEmpty {
                    val fallback = "https://byabbe.se/on-this-day/${today.monthValue}/${today.dayOfMonth}/events.json"
                    parseWikipedia(ContentData.getJson(fallback), today)
                }
            }
            check(events.isNotEmpty()) { "历史上的今天暂无事件" }
            if (stored.isNotEmpty() && !force) {
                return@withLock stored[p.getInt("index", 0).coerceIn(stored.indices)]
            }
            val previous = if (stored.isNotEmpty()) p.getInt("index", 0).coerceIn(events.indices) else -1
            val index = when {
                previous < 0 -> 0
                force -> (previous + 1) % events.size
                else -> previous
            }
            val json = JSONArray()
            events.forEach {
                json.put(JSONObject().put("year", it.year).put("title", it.title)
                    .put("description", it.description).put("source", it.source))
            }
            p.edit().putString("date", today.toString()).putString("events", json.toString())
                .putInt("index", index).apply()
            events[index]
        } }

    internal fun parseEvents(data: JSONObject, today: LocalDate): List<Event> {
        val month = "%02d".format(java.util.Locale.ROOT, today.monthValue)
        val day = "%02d".format(java.util.Locale.ROOT, today.dayOfMonth)
        val rows = data.optJSONObject(month)?.optJSONArray(month + day) ?: return emptyList()
        fun plain(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ").trim()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val year = row.optString("year").toIntOrNull()?.takeIf { it in -5000..today.year }
                ?: return@mapNotNull null
            val title = plain(row.optString("title")).take(90)
            if (title.isBlank()) return@mapNotNull null
            val description = plain(row.optString("desc")).take(320).ifBlank { title }
            row.optString("type") to Event(year, title, description, "百度百科 · 历史上的今天", today)
        }.sortedBy { if (it.first == "event") 0 else 1 }.map { it.second }
            .distinctBy { it.year to it.title }.take(32)
    }

    private fun parseWikipedia(data: JSONObject, today: LocalDate): List<Event> {
        val rows = data.getJSONArray("events")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val raw = row.optString("year").trim()
            val match = Regex("(?:AD )?(\\d{1,4})( BC)?", RegexOption.IGNORE_CASE).matchEntire(raw)
                ?: return@mapNotNull null
            val year = match.groupValues[1].toInt()
            val description = row.optString("description").trim().take(320)
            if (description.isBlank() || year !in 1..today.year) return@mapNotNull null
            val title = row.optJSONArray("wikipedia")?.optJSONObject(0)?.optString("title")
                .orEmpty().ifBlank { "历史上的今天" }.take(90)
            Event(if (match.groupValues[2].isBlank()) year else 1 - year,
                title, description, "维基百科 · 英文", today)
        }.distinctBy { it.year to it.description }.take(32)
    }

    // Upgrade copies saved by older releases while retaining positions, colors and any edited text.
    internal fun migrate(template: JSONObject) {
        val nodes = template.optJSONArray("nodes") ?: return
        val entries = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
        if (entries.none { it.optString("text") == "1969.07.20" } ||
            entries.none { it.optString("text") == "阿波罗 11 号登月" }) return
        val bindings = mapOf(
            "空天探索" to "历史上的今天",
            "1969.07.20" to "{{historyDate}}",
            "阿波罗 11 号登月" to "{{historyTitle}}",
            "登月舱“鹰”号在月球静海着陆。阿姆斯特朗与奥尔德林随后走上月面，科林斯留在绕月运行的指令舱。" to "{{historyText}}",
            "延伸阅读" to "资料来源",
            "为什么返回地球前，需要在月球轨道交会？" to "{{historySource}}",
        )
        entries.forEach { node ->
            if (node.optString("type") == "text") bindings[node.optString("text")]?.let { node.put("text", it) }
        }
    }
}
