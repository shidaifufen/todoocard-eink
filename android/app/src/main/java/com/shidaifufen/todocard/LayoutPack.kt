package com.shidaifufen.todocard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Data only. Packs cannot execute scripts or alter content, credentials, schedules or BLE. */
object LayoutPack {
    const val MAX_BYTES = 256 * 1024
    val kinds = setOf("summary", "quote", "todo", "progress", "calendar", "weather", "weather2", "countdown", "poem", "custom")
    private val types = setOf("rect", "text", "list", "weatherIcon", "weatherHours", "forecastBars", "block", "builtin")
    private val colors = setOf("white", "black", "red", "blue", "green", "yellow", "aqiColor", "aqiInk")
    private val sources = setOf("weather", "quote", "poem")
    internal val fields = setOf("weatherCity", "weatherText", "weatherTemp", "weatherHigh", "weatherLow", "weatherAqi",
        "weatherHumidity", "weatherFeels", "weatherSunrise", "weatherSunset", "weatherUpdated", "wearAdvice",
        "date", "time", "week", "year", "month", "day", "quote", "quoteBy", "poemTitle", "poemBy", "poemNote",
        "poemText", "customTitle", "customText", "todoCount", "yearPercent")
    private val lists = setOf("todos", "poemLines", "customLines", "weatherForecast", "countdowns")
    private val binding = Regex("\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}")
    data class Node(val type: String, val x: Float, val y: Float, val width: Float, val height: Float, val json: String)
    data class Template(val name: String, val sources: Set<String>, val nodes: List<Node>, val json: String)
    data class Pack(val name: String, val templates: Map<String, Template>, val json: String)

    private fun keys(o: JSONObject, allowed: Set<String>, where: String) {
        val unknown = o.keys().asSequence().toSet() - allowed
        require(unknown.isEmpty()) { "$where 不支持字段：${unknown.joinToString()}" }
    }
    private fun number(o: JSONObject, key: String, default: Double, min: Double, max: Double): Float {
        require(!o.has(key) || o.get(key) is Number) { "$key 必须是数字" }
        val value = o.optDouble(key, default)
        require(value.isFinite() && value in min..max) { "$key 必须在 $min～$max 之间" }
        return value.toFloat()
    }
    private fun text(o: JSONObject, key: String, default: String = ""): String {
        require(!o.has(key) || o.get(key) is String) { "$key 必须是文字" }
        return o.optString(key, default).also { require(it.length <= 4096) { "$key 文字过长" } }
    }
    private fun bool(o: JSONObject, key: String) {
        require(!o.has(key) || o.get(key) is Boolean) { "$key 必须是 true 或 false" }
    }
    fun parse(raw: String): Pack {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "版式包不能超过 256 KB" }
        val root = JSONObject(raw)
        keys(root, setOf("schemaVersion", "name", "templates"), "版式包")
        require(root.get("schemaVersion") is Number && root.getDouble("schemaVersion") == 1.0) { "不支持此版式格式，请使用 schemaVersion 1" }
        val name = text(root, "name", "导入的版式").take(80)
        val templates = root.getJSONObject("templates")
        require(templates.length() in 1..kinds.size) { "版式包需要包含 1～${kinds.size} 张卡片" }
        val result = linkedMapOf<String, Template>()
        templates.keys().forEach { kind ->
            require(kind in kinds) { "不支持的卡片：$kind" }
            val t = templates.getJSONObject(kind)
            keys(t, setOf("name", "sources", "nodes"), kind)
            val deps = linkedSetOf<String>()
            t.optJSONArray("sources")?.let { a ->
                repeat(a.length()) { require(a.get(it) is String && a.getString(it) in sources) { "$kind 数据来源只支持 weather、quote、poem" }; deps += a.getString(it) }
            }
            require(!t.has("sources") || t.get("sources") is JSONArray) { "sources 必须是数组" }
            val nodes = t.getJSONArray("nodes")
            require(nodes.length() in 1..96) { "$kind 需要 1～96 个元素" }
            val parsed = (0 until nodes.length()).map { index ->
                val n = nodes.getJSONObject(index)
                val type = text(n, "type")
                require(type in types) { "$kind 第 ${index + 1} 个元素类型不支持：$type" }
                val common = setOf("type", "x", "y", "width", "height", "showIf")
                val style = setOf("textSize", "color", "bold", "align", "lineHeight")
                val allowed = when (type) {
                    "rect" -> setOf("fill", "border", "borderWidth", "radius")
                    "text" -> style + setOf("text", "wrap", "shrink")
                    "list" -> style + setOf("source", "numbered", "emptyText")
                    "weatherHours" -> setOf("color")
                    "forecastBars" -> setOf("color")
                    "block" -> setOf("blockType", "text", "textSize", "centered", "color")
                    "builtin" -> setOf("kind")
                    else -> emptySet()
                }
                keys(n, common + allowed, "$kind/$index")
                val x = number(n, "x", 0.0, 0.0, 527.0)
                val y = number(n, "y", 0.0, 0.0, 791.0)
                val width = number(n, "width", 528.0 - x, 1.0, 528.0)
                val height = number(n, "height", 792.0 - y, 1.0, 792.0)
                require(x + width <= 528.01f && y + height <= 792.01f) { "$kind/$index 超出 528×792 画布" }
                for (key in listOf("fill", "border", "color")) if (n.has(key)) require(text(n, key) in colors) { "$key 请使用六色名称、aqiColor 或 aqiInk" }
                for (key in listOf("bold", "wrap", "shrink", "numbered", "centered")) bool(n, key)
                number(n, "textSize", 24.0, 10.0, 160.0)
                number(n, "lineHeight", 1.4, 1.0, 2.5)
                number(n, "borderWidth", 2.0, 0.0, 20.0)
                number(n, "radius", 0.0, 0.0, 80.0)
                require(text(n, "align", "left") in setOf("left", "center", "right")) { "align 只支持 left、center、right" }
                text(n, "showIf").takeIf { it.isNotBlank() }?.let { require(it in fields) { "未知条件字段：$it" } }
                for (key in listOf("text", "emptyText")) {
                    val value = text(n, key)
                    binding.findAll(value).forEach { require(it.groupValues[1] in fields) { "未知内容字段：${it.groupValues[1]}" } }
                    require(!binding.replace(value, "").contains("{{")) { "内容绑定格式应为 {{字段名}}" }
                }
                if (type == "list") require(text(n, "source") in lists) { "不支持的列表来源" }
                if (type == "block") require(text(n, "blockType") in CustomBlocks.types.keys - "image") { "不支持此组合元素（图片请使用应用内编辑）" }
                if (type == "builtin") require(text(n, "kind") in kinds) { "内置卡片类型无效" }
                val used = binding.findAll(text(n, "text") + text(n, "emptyText")).map { it.groupValues[1] }.toList() + text(n, "showIf")
                if (type in setOf("weatherIcon", "weatherHours", "forecastBars") || used.any { it.startsWith("weather") || it == "wearAdvice" } ||
                    n.optString("source") == "weatherForecast" || n.optString("blockType") == "weather") deps += "weather"
                if (used.any { it.startsWith("quote") } || n.optString("blockType") == "quote") deps += "quote"
                if (used.any { it.startsWith("poem") } || n.optString("source") == "poemLines" || n.optString("blockType") == "poem") deps += "poem"
                if (type == "builtin") when (n.getString("kind")) {
                    "summary" -> deps += listOf("weather", "quote")
                    "weather", "weather2" -> deps += "weather"
                    "quote", "poem" -> deps += n.getString("kind")
                }
                Node(type, x, y, width, height, n.toString())
            }
            result[kind] = Template(text(t, "name", kind).take(80), deps.toSet(), parsed, t.toString())
        }
        return Pack(name, result.toMap(), root.toString())
    }
}

/** Atomically replace the complete snapshot; renders already in flight retain their own template. */
object LayoutStore {
    private data class State(val defaults: LayoutPack.Pack, val overrides: Map<String, LayoutPack.Template>, val name: String)
    @Volatile private var state: State? = null
    private val change = MutableStateFlow(0L)
    val revision = change.asStateFlow()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("layout_packs_v1", Context.MODE_PRIVATE)
    @Synchronized fun init(ctx: Context) {
        if (state != null) return
        val defaults = LayoutPack.parse(ctx.assets.open("layouts/default.json").bufferedReader().use { it.readText() })
        val imported = prefs(ctx).getString("active", null)?.let { runCatching { LayoutPack.parse(it) }.getOrNull() }
        state = State(defaults, imported?.templates.orEmpty(), imported?.name ?: "内置版式")
    }
    fun template(kind: String) = state?.let { it.overrides[kind] ?: it.defaults.templates[kind] }
    fun sources(kind: String, card: Render.Card? = null): Set<String>? = template(kind)?.let { t ->
        val custom = if (t.nodes.any { it.type == "builtin" && JSONObject(it.json).optString("kind") == "custom" })
            card?.customBlocks.orEmpty().filter { it.enabled && it.type in setOf("weather", "quote", "poem") }.map { it.type }
            else emptyList()
        t.sources + custom
    }
    fun name() = state?.name ?: "内置版式"
    fun changedKinds() = state?.overrides?.keys.orEmpty()
    private fun encode(name: String, templates: Map<String, LayoutPack.Template>) = JSONObject()
        .put("schemaVersion", 1).put("name", name).put("templates", JSONObject().apply {
            templates.forEach { (kind, t) -> put(kind, JSONObject(t.json)) }
        }).toString(2)
    fun export(ctx: Context, defaultsOnly: Boolean = false): String {
        init(ctx)
        val s = state!!
        return encode(if (defaultsOnly) "土豆片默认版式" else s.name, s.defaults.templates + if (defaultsOnly) emptyMap() else s.overrides)
    }
    @Synchronized fun apply(ctx: Context, pack: LayoutPack.Pack) {
        init(ctx)
        val merged = state!!.overrides + pack.templates
        val raw = encode(pack.name, merged)
        LayoutPack.parse(raw)
        val p = prefs(ctx)
        check(p.edit().putString("previous", p.getString("active", "")).putString("active", raw).commit()) { "版式保存失败" }
        state = state!!.copy(overrides = merged, name = pack.name)
        change.value++
    }
    fun canUndo(ctx: Context) = prefs(ctx).contains("previous")
    @Synchronized fun undo(ctx: Context) {
        init(ctx)
        val p = prefs(ctx)
        check(p.contains("previous")) { "没有可恢复的上一版" }
        val old = p.getString("previous", "").orEmpty()
        val parsed = old.takeIf(String::isNotBlank)?.let(LayoutPack::parse)
        check(p.edit().putString("active", old).remove("previous").commit()) { "恢复失败" }
        state = state!!.copy(overrides = parsed?.templates.orEmpty(), name = parsed?.name ?: "内置版式")
        change.value++
    }
    @Synchronized fun reset(ctx: Context) {
        init(ctx)
        val p = prefs(ctx)
        check(p.edit().putString("previous", p.getString("active", "")).remove("active").commit()) { "恢复失败" }
        state = state!!.copy(overrides = emptyMap(), name = "内置版式")
        change.value++
    }
    @Synchronized internal fun reload(ctx: Context) { state = null; init(ctx); change.value++ }
}
