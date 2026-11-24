package com.shidaifufen.todocard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 每日计划 / 间隔推送 / 历史 —— 全部存 SharedPreferences（JSON） */
object PlanStore {

    data class Fixed(val time: String, val template: String, val enabled: Boolean = true)

    data class Interval(
        val everyMin: Int,
        val start: String,
        val end: String,
        val days: String,          // all / workday / weekend
        val template: String,
        val enabled: Boolean = true,
    )

    data class Hist(val ts: Long, val template: String, val ok: Boolean, val seconds: Int, val msg: String)

    private fun sp(c: Context) = c.getSharedPreferences("plan", Context.MODE_PRIVATE)

    fun fixed(c: Context): List<Fixed> {
        val raw = sp(c).getString("fixed", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Fixed(o.optString("time", "08:00"), o.optString("template", "summary"), o.optBoolean("enabled", true))
        }
    }

    fun saveFixed(c: Context, list: List<Fixed>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("time", it.time).put("template", it.template).put("enabled", it.enabled)) }
        sp(c).edit().putString("fixed", arr.toString()).apply()
    }

    fun intervals(c: Context): List<Interval> {
        val raw = sp(c).getString("intervals", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Interval(o.optInt("every", 60), o.optString("start", "09:00"), o.optString("end", "22:00"),
                o.optString("days", "all"), o.optString("template", "photo"), o.optBoolean("enabled", false))
        }
    }

    fun saveIntervals(c: Context, list: List<Interval>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("every", it.everyMin).put("start", it.start).put("end", it.end)
                .put("days", it.days).put("template", it.template).put("enabled", it.enabled))
        }
        sp(c).edit().putString("intervals", arr.toString()).apply()
    }

    fun history(c: Context): List<Hist> {
        val raw = sp(c).getString("hist", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Hist(o.optLong("ts"), o.optString("template"), o.optBoolean("ok"),
                o.optInt("seconds"), o.optString("msg"))
        }
    }

    fun addHistory(c: Context, h: Hist) {
        val list = (history(c) + h).takeLast(200)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("ts", it.ts).put("template", it.template)
                .put("ok", it.ok).put("seconds", it.seconds).put("msg", it.msg))
        }
        sp(c).edit().putString("hist", arr.toString()).apply()
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
        sp(c).edit().putString("card", data.toString()).apply()
    }


    var lastPhoto: String
        get() = sp_global.getString("lastPhoto", "") ?: ""
        set(v) { sp_global.edit().putString("lastPhoto", v).apply() }

    var photoEdit: PhotoEdit.Params
        get() = runCatching {
            val data = JSONObject(sp_global.getString("photoEdit", "{}") ?: "{}")
            PhotoEdit.Params(
                zoom = data.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 4f),
                dx = data.optDouble("dx", 0.0).toFloat().coerceIn(-1f, 1f),
                dy = data.optDouble("dy", 0.0).toFloat().coerceIn(-1f, 1f),
                hue = data.optDouble("hue", 0.0).toFloat().coerceIn(-180f, 180f),
                saturation = data.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2f),
                exposure = data.optDouble("exposure", 0.0).toFloat().coerceIn(-2f, 2f),
                contrast = data.optDouble("contrast", 1.0).toFloat().coerceIn(.6f, 1.6f),
                snapThreshold = data.optInt("snapThreshold", 0).coerceIn(0, 100),
            )
        }.getOrDefault(PhotoEdit.Params())
        set(value) {
            val data = JSONObject().put("zoom", value.zoom).put("dx", value.dx).put("dy", value.dy)
                .put("hue", value.hue).put("saturation", value.saturation)
                .put("exposure", value.exposure).put("contrast", value.contrast)
                .put("snapThreshold", value.snapThreshold)
            sp_global.edit().putString("photoEdit", data.toString()).apply()
        }

    private fun fieldsKey() = "photoFields"

    var photoFields: PhotoCard.Fields
        get() {
            val raw = sp_global.getString(fieldsKey(), "") ?: ""
            if (raw.isEmpty()) return PhotoCard.Fields()
            val p = raw.split(",")
            fun has(i: Int) = p.getOrNull(i) == "1"
            return PhotoCard.Fields(has(0), has(1), has(2), has(3), has(4))
        }
        set(v) {
            val raw = listOf(v.date, v.time, v.camera, v.params, v.weather)
                .joinToString(",") { if (it) "1" else "0" }
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
    }
}
