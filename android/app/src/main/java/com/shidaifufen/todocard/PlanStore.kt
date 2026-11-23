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


    var lastPhoto: String
        get() = sp_global.getString("lastPhoto", "") ?: ""
        set(v) { sp_global.edit().putString("lastPhoto", v).apply() }

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

    fun addAlbum(c: Context, uri: String) {
        val list = albums(c).toMutableList()
        if (!list.contains(uri)) list.add(uri)
        sp_global.edit().putString("albums", list.joinToString("\n")).apply()
    }

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
