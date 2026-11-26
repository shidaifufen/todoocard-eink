package com.shidaifufen.todocard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 每日计划 / 间隔推送 / 历史 —— 全部存 SharedPreferences（JSON） */
object PlanStore {

    data class Fixed(val time: String, val template: String, val enabled: Boolean = true)

    internal fun byExecutionTime(list: List<Fixed>) = list.sortedBy { it.time.padStart(5, '0') }

    data class Interval(
        val everyMin: Int,
        val start: String,
        val end: String,
        val days: String,          // all / workday / weekend
        val templates: List<String>,
        val enabled: Boolean = true,
    ) {
        constructor(everyMin: Int, start: String, end: String, days: String, template: String, enabled: Boolean = true) :
            this(everyMin, start, end, days, listOf(template), enabled)
    }

    data class Hist(val ts: Long, val template: String, val ok: Boolean, val seconds: Int, val msg: String,
                    val snapshot: String = "", val replay: Boolean = false)

    private fun sp(c: Context) = c.getSharedPreferences("plan", Context.MODE_PRIVATE)

    fun fixed(c: Context): List<Fixed> {
        val raw = sp(c).getString("fixed", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return byExecutionTime((0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Fixed(o.optString("time", "08:00"), o.optString("template", "summary"), o.optBoolean("enabled", true))
        })
    }

    fun saveFixed(c: Context, list: List<Fixed>) {
        val arr = JSONArray()
        byExecutionTime(list).forEach { arr.put(JSONObject().put("time", it.time).put("template", it.template).put("enabled", it.enabled)) }
        sp(c).edit().putString("fixed", arr.toString()).apply()
    }

    fun intervals(c: Context): List<Interval> {
        val raw = sp(c).getString("intervals", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val templates = o.optJSONArray("templates")?.let { items ->
                List(items.length()) { items.optString(it) }.filter(String::isNotBlank).distinct()
            }.orEmpty().ifEmpty { listOf(o.optString("template", "photo")) }
            Interval(o.optInt("every", 60), o.optString("start", "09:00"), o.optString("end", "22:00"),
                o.optString("days", "all"), templates, o.optBoolean("enabled", false))
        }
    }

    fun saveIntervals(c: Context, list: List<Interval>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("every", it.everyMin).put("start", it.start).put("end", it.end)
                .put("days", it.days).put("templates", JSONArray(it.templates))
                .put("template", it.templates.first()).put("enabled", it.enabled))
        }
        sp(c).edit().putString("intervals", arr.toString()).apply()
    }

    fun history(c: Context): List<Hist> {
        val raw = sp(c).getString("hist", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Hist(o.optLong("ts"), o.optString("template"), o.optBoolean("ok"),
                o.optInt("seconds"), o.optString("msg"), o.optString("snapshot"), o.optBoolean("replay"))
        }
    }

    @Synchronized fun addHistory(c: Context, h: Hist) {
        val list = (history(c) + h).takeLast(200)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("ts", it.ts).put("template", it.template)
                .put("ok", it.ok).put("seconds", it.seconds).put("msg", it.msg)
                .put("snapshot", it.snapshot).put("replay", it.replay))
        }
        sp(c).edit().putString("hist", arr.toString()).apply()
        HistoryCards.prune(c, list.map { it.snapshot }.toSet())
    }

    fun card(c: Context): Render.Card {
        val raw = sp(c).getString("card", null) ?: return Render.Card()
        return runCatching {
            val data = JSONObject(raw)
            fun lines(key: String): List<String> {
                val values = data.optJSONArray(key) ?: return emptyList()
                return (0 until values.length()).map { values.optString(it) }
            }
            val dates = data.optJSONArray("countdowns")
            Render.Card(
                quote = data.optString("quote", Render.Card().quote),
                quoteBy = data.optString("quoteBy", Render.Card().quoteBy),
                todos = lines("todos"),
                countdowns = if (dates == null) emptyList() else (0 until dates.length()).mapNotNull {
                    val item = dates.optJSONArray(it)
                    if (item == null || item.length() < 2) null
                    else item.optString(0) to item.optString(1)
                },
                poemTitle = data.optString("poemTitle", Render.Card().poemTitle),
                poemBy = data.optString("poemBy", Render.Card().poemBy),
                poemLines = if (data.has("poemLines")) lines("poemLines") else Render.Card().poemLines,
                poemNote = data.optString("poemNote"),
                customTitle = data.optString("customTitle", Render.Card().customTitle),
                customLines = if (data.has("customLines")) lines("customLines") else Render.Card().customLines,
                weatherCity = data.optString("weatherCity"),
                weatherText = data.optString("weatherText"),
                weatherTemp = data.optString("weatherTemp"),
                weatherHigh = data.optString("weatherHigh"),
                weatherLow = data.optString("weatherLow"),
                weatherAqi = data.optString("weatherAqi"),
                weatherForecast = lines("weatherForecast"),
                weatherLatitude = data.optString("weatherLatitude"),
                weatherLongitude = data.optString("weatherLongitude"),
                weatherUpdated = data.optString("weatherUpdated"),
                weatherAuto = data.optBoolean("weatherAuto", data.optString("weatherLatitude").isBlank()),
                weatherHumidity = data.optString("weatherHumidity"),
                weatherFeels = data.optString("weatherFeels"),
                weatherSunrise = data.optString("weatherSunrise"),
                weatherSunset = data.optString("weatherSunset"),
                weatherCode = data.optInt("weatherCode", -1),
                weatherHours = data.optJSONArray("weatherHours")?.let { hours ->
                    (0 until hours.length()).map { i -> hours.getJSONObject(i).let {
                        Render.WeatherHour(it.getString("time"), it.getDouble("temperature").toFloat(), it.optInt("code", -1))
                    } }
                }.orEmpty(),
                fontScale = data.optDouble("fontScale", 1.0).toFloat().coerceIn(.8f, 1.5f),
                lineSpacing = data.optDouble("lineSpacing", 1.0).toFloat().coerceIn(.8f, 1.6f),
                elementSpacing = data.optDouble("elementSpacing", 1.0).toFloat().coerceIn(.6f, 1.6f),
                customBlocks = data.optJSONArray("customBlocks")?.let { blocks ->
                    (0 until blocks.length()).map { i -> blocks.getJSONObject(i).let {
                        Render.CustomBlock(it.optString("id", "block_$i"), it.optString("type", "text"),
                            it.optString("text"), it.optBoolean("enabled", true),
                            it.optDouble("size", 24.0).toFloat(), it.optInt("height", 180),
                            it.optBoolean("centered"), it.optString("imagePath"))
                    } }
                }.orEmpty(),
                customAccent = data.optString("customAccent", "red"),
                customComposition = data.optBoolean("customComposition", false),
            )
        }.getOrDefault(Render.Card())
    }

    fun saveCard(c: Context, card: Render.Card) {
        val data = JSONObject()
            .put("quote", card.quote).put("quoteBy", card.quoteBy)
            .put("todos", JSONArray(card.todos))
            .put("countdowns", JSONArray().apply {
                card.countdowns.forEach { (name, date) -> put(JSONArray().put(name).put(date)) }
            })
            .put("poemTitle", card.poemTitle).put("poemBy", card.poemBy)
            .put("poemLines", JSONArray(card.poemLines)).put("poemNote", card.poemNote)
            .put("customTitle", card.customTitle).put("customLines", JSONArray(card.customLines))
            .put("weatherCity", card.weatherCity).put("weatherText", card.weatherText)
            .put("weatherTemp", card.weatherTemp).put("weatherHigh", card.weatherHigh)
            .put("weatherLow", card.weatherLow).put("weatherAqi", card.weatherAqi)
            .put("weatherForecast", JSONArray(card.weatherForecast))
            .put("weatherLatitude", card.weatherLatitude).put("weatherLongitude", card.weatherLongitude)
            .put("weatherUpdated", card.weatherUpdated)
            .put("weatherAuto", card.weatherAuto)
            .put("weatherHumidity", card.weatherHumidity).put("weatherFeels", card.weatherFeels)
            .put("weatherSunrise", card.weatherSunrise).put("weatherSunset", card.weatherSunset)
            .put("weatherCode", card.weatherCode)
            .put("weatherHours", JSONArray().apply { card.weatherHours.forEach {
                put(JSONObject().put("time", it.time).put("temperature", it.temperature).put("code", it.code))
            } })
            .put("fontScale", card.fontScale).put("lineSpacing", card.lineSpacing).put("elementSpacing", card.elementSpacing)
            .put("customAccent", card.customAccent).put("customComposition", card.customComposition)
            .put("customBlocks", JSONArray().apply { card.customBlocks.forEach {
                put(JSONObject().put("id", it.id).put("type", it.type).put("text", it.text)
                    .put("enabled", it.enabled).put("size", it.size).put("height", it.height)
                    .put("centered", it.centered).put("imagePath", it.imagePath))
            } })
        sp(c).edit().putString("card", data.toString()).apply()
    }


    var lastPhoto: String
        get() = sp_global.getString("lastPhoto", "") ?: ""
        set(v) { sp_global.edit().putString("lastPhoto", v).apply() }

    private fun readPhotoEdit(key: String, fallback: PhotoEdit.Params): PhotoEdit.Params {
        val raw = sp_global.getString(key, null) ?: return fallback
        return runCatching {
            val data = JSONObject(raw)
            PhotoEdit.Params(
                zoom = data.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 4f),
                dx = data.optDouble("dx", 0.0).toFloat().coerceIn(-1f, 1f),
                dy = data.optDouble("dy", 0.0).toFloat().coerceIn(-1f, 1f),
                hue = data.optDouble("hue", 0.0).toFloat().coerceIn(-180f, 180f),
                saturation = data.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2f),
                exposure = data.optDouble("exposure", 0.0).toFloat().coerceIn(-2f, 2f),
                contrast = data.optDouble("contrast", 1.0).toFloat().coerceIn(.6f, 1.6f),
                snapThreshold = data.optInt("snapThreshold", 0).coerceIn(0, 100),
                fit = data.optBoolean("fit"),
                gamma = data.optDouble("gamma", 1.0).toFloat().coerceIn(.5f, 2f),
                sharpness = data.optDouble("sharpness", 0.0).toFloat().coerceIn(0f, 1f),
                shadows = data.optDouble("shadows", 0.0).toFloat().coerceIn(0f, 1f),
                brightness = data.optDouble("brightness").takeIf { it.isFinite() }?.toFloat()?.coerceIn(.65f, 1.45f),
            )
        }.getOrDefault(fallback)
    }

    private fun savePhotoEdit(key: String, value: PhotoEdit.Params) {
            val data = JSONObject().put("zoom", value.zoom).put("dx", value.dx).put("dy", value.dy)
                .put("hue", value.hue).put("saturation", value.saturation)
                .put("exposure", value.exposure).put("contrast", value.contrast)
                .put("snapThreshold", value.snapThreshold)
                .put("fit", value.fit).put("gamma", value.gamma).put("sharpness", value.sharpness)
                .put("shadows", value.shadows)
            value.brightness?.let { data.put("brightness", it) }
        sp_global.edit().putString(key, data.toString()).apply()
    }

    var photoEdit: PhotoEdit.Params
        get() = readPhotoEdit("photoEdit", PhotoEdit.Params())
        set(value) = savePhotoEdit("photoEdit", value)

    var defaultPhotoPreset: PhotoEdit.Preset
        get() = PhotoEdit.Preset.entries.firstOrNull { it.name == sp_global.getString("defaultPhotoPreset", null) }
            ?: PhotoEdit.Preset.NATURAL
        set(value) { sp_global.edit().putString("defaultPhotoPreset", value.name).apply() }

    val defaultPhotoTone: PhotoEdit.Params get() = photoPreset(defaultPhotoPreset)

    var defaultOnlinePhotoPreset: PhotoEdit.Preset
        get() = PhotoEdit.Preset.entries.firstOrNull { it.name == sp_global.getString("defaultOnlinePhotoPreset", null) }
            ?: PhotoEdit.Preset.VIVID
        set(value) { sp_global.edit().putString("defaultOnlinePhotoPreset", value.name).apply() }

    fun photoPreset(preset: PhotoEdit.Preset) = readPhotoEdit("photoPreset_${preset.name}", preset.defaults)
    fun savePhotoPreset(preset: PhotoEdit.Preset, value: PhotoEdit.Params) =
        savePhotoEdit("photoPreset_${preset.name}", value)

    fun defaultLayout(ctx: Context, content: Render.Card): Render.Card {
        val saved = card(ctx)
        return content.copy(fontScale = saved.fontScale, lineSpacing = saved.lineSpacing,
            elementSpacing = saved.elementSpacing)
    }

    private fun fieldsKey() = "photoFields"

    var photoFields: PhotoCard.Fields
        get() {
            val raw = sp_global.getString(fieldsKey(), "") ?: ""
            if (raw.isEmpty()) return PhotoCard.Fields()
            val p = raw.split(",")
            fun has(i: Int) = p.getOrNull(i) == "1"
            return PhotoCard.Fields(has(0), has(1), has(2), has(3), has(4),
                p.getOrNull(5) != "0", p.getOrNull(6)?.toFloatOrNull()?.takeIf(Float::isFinite)
                    ?.coerceIn(.8f, 1.5f) ?: .85f, p.getOrNull(7) != "0")
        }
        set(v) {
            val raw = listOf(v.date, v.time, v.camera, v.params, v.weather, v.location)
                .joinToString(",") { if (it) "1" else "0" } +
                ",${v.textScale.coerceIn(.8f, 1.5f)},${if (v.resolveOnline) 1 else 0}"
            sp_global.edit().putString(fieldsKey(), raw).apply()
        }


    fun albums(c: Context): List<String> =
        (sp_global.getString("albums", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun clearAlbums(c: Context) { sp_global.edit().putString("albums", "").apply() }

    fun addAlbum(c: Context, uri: String) {
        val list = albums(c).toMutableList()
        if (!list.contains(uri)) list.add(uri)
        sp_global.edit().putString("albums", list.joinToString("\n"))
            .putStringSet("excludedPhotos", excludedPhotos(c) - uri).apply()
    }

    fun excludedPhotos(c: Context): Set<String> = sp(c).getStringSet("excludedPhotos", emptySet())
        ?.toSet() ?: emptySet()

    fun removeAlbum(uri: String) {
        val list = (sp_global.getString("albums", "") ?: "").split("\n")
            .filter { it.isNotBlank() && it != uri }
        val hidden = (sp_global.getStringSet("excludedPhotos", emptySet()) ?: emptySet()) + uri
        sp_global.edit().putString("albums", list.joinToString("\n"))
            .putStringSet("excludedPhotos", hidden).apply()
    }

    var lastDrawnUri: String
        get() = sp_global.getString("lastDrawnUri", "") ?: ""
        set(value) { sp_global.edit().putString("lastDrawnUri", value).apply() }

    var lastChecked: Long
        get() = sp_global.getLong("lastChecked", 0L)
        set(v) { sp_global.edit().putLong("lastChecked", v).apply() }

    var deviceAddress: String
        get() = sp_global.getString("device", "") ?: ""
        set(v) { sp_global.edit().putString("device", v).apply() }

    var deviceName: String
        get() = sp_global.getString("deviceName", "") ?: ""
        set(v) { sp_global.edit().putString("deviceName", v).apply() }

    private lateinit var sp_global: android.content.SharedPreferences

    fun init(c: Context) {
        sp_global = c.applicationContext.getSharedPreferences("plan", Context.MODE_PRIVATE)
        LayoutStore.init(c.applicationContext)
    }
}
