package com.shidaifufen.todocard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** 仅按用户输入的城市获取公开天气资料，不读取位置权限。 */
object WeatherData {
    data class Place(val label: String, val latitude: Double, val longitude: Double)

    private fun json(url: String, timeout: Int = 7_000): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        return try {
            check(connection.responseCode in 200..299) { "天气服务暂时不可用" }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    suspend fun search(city: String): List<Place> = withContext(Dispatchers.IO) {
        val name = URLEncoder.encode(city.trim(), "UTF-8")
        val results = json("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=5&language=zh&format=json")
            .optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).map { index ->
            val value = results.getJSONObject(index)
            Place(listOf(value.optString("name"), value.optString("admin1"),
                value.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                value.getDouble("latitude"), value.getDouble("longitude"))
        }
    }

    suspend fun load(place: Place, card: Render.Card): Render.Card = withContext(Dispatchers.IO) { supervisorScope {
        val coordinate = "latitude=${place.latitude}&longitude=${place.longitude}"
        val aqiRequest = async {
            runCatching { json("https://air-quality-api.open-meteo.com/v1/air-quality?$coordinate&current=us_aqi", 3_000)
                .getJSONObject("current").getDouble("us_aqi").roundToInt().toString() }.getOrDefault("")
        }
        val data = json("https://api.open-meteo.com/v1/forecast?$coordinate&current=temperature_2m,weather_code,relative_humidity_2m,apparent_temperature&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&forecast_days=4&timezone=auto")
        parse(data, aqiRequest.await(), place, card)
    } }

    internal fun parse(data: JSONObject, aqi: String, place: Place, card: Render.Card): Render.Card {
        val current = data.getJSONObject("current")
        val daily = data.getJSONObject("daily")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val codes = daily.getJSONArray("weather_code")
        val dates = daily.getJSONArray("time")
        val hourly = data.optJSONObject("hourly")
        val times = hourly?.optJSONArray("time")
        val temperatures = hourly?.optJSONArray("temperature_2m")
        val hourCodes = hourly?.optJSONArray("weather_code")
        val now = current.optString("time")
        val hours = if (times == null || temperatures == null) emptyList() else
            (0 until minOf(times.length(), temperatures.length())).filter {
                times.optString(it) >= now && !temperatures.isNull(it)
            }.take(6).map { Render.WeatherHour(times.getString(it).substringAfter("T"),
                temperatures.getDouble(it).toFloat(), hourCodes?.optInt(it, -1) ?: -1) }
        return card.copy(
            weatherCity = place.label.substringBefore(" · "),
            weatherAuto = false,
            weatherText = condition(current.getInt("weather_code")),
            weatherCode = current.getInt("weather_code"),
            weatherHours = hours,
            weatherTemp = current.getDouble("temperature_2m").roundToInt().toString(),
            weatherHigh = highs.getDouble(0).roundToInt().toString(),
            weatherLow = lows.getDouble(0).roundToInt().toString(),
            weatherAqi = aqi.ifBlank { if (card.weatherCity == place.label.substringBefore(" · ")) card.weatherAqi else "" },
            weatherHumidity = if (current.isNull("relative_humidity_2m")) "" else current.optInt("relative_humidity_2m").toString(),
            weatherFeels = if (current.isNull("apparent_temperature")) "" else current.optDouble("apparent_temperature").roundToInt().toString(),
            weatherSunrise = daily.optJSONArray("sunrise")?.optString(0)?.substringAfter("T").orEmpty(),
            weatherSunset = daily.optJSONArray("sunset")?.optString(0)?.substringAfter("T").orEmpty(),
            weatherLatitude = place.latitude.toString(),
            weatherLongitude = place.longitude.toString(),
            weatherUpdated = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date()),
            weatherForecast = (1..3).map { index ->
                "${dates.getString(index).takeLast(5)} ${condition(codes.getInt(index))} " +
                    "${lows.getDouble(index).roundToInt()}~${highs.getDouble(index).roundToInt()}°"
            },
        )
    }

    suspend fun refresh(card: Render.Card): Render.Card {
        if (card.weatherAuto || card.weatherLatitude.isBlank() || card.weatherLongitude.isBlank())
            return automatic(card)
        return load(Place(card.weatherCity, card.weatherLatitude.toDouble(), card.weatherLongitude.toDouble()), card)
    }

    /** 按当前网络出口估算城市，无需填写城市和申请定位权限。用户可随时改为指定城市。 */
    suspend fun automatic(card: Render.Card): Render.Card = withContext(Dispatchers.IO) {
        // 已解析城市继续用其坐标，避免每次刷新重复串行定位和城市搜索。
        if (card.weatherLatitude.toDoubleOrNull() != null && card.weatherLongitude.toDoubleOrNull() != null)
            return@withContext load(Place(card.weatherCity, card.weatherLatitude.toDouble(), card.weatherLongitude.toDouble()), card).copy(weatherAuto = true)
        val location = runCatching {
            json("https://ipwho.is/?lang=zh").also {
                check(it.optBoolean("success", false)) { "网络城市接口暂不可用" }
            }
        }.getOrElse { json("https://ipapi.co/json/") }
        check(location.has("latitude") && location.has("longitude")) { "无法获取网络所在城市，请手动选择城市" }
        val city = location.optString("city").ifBlank { "网络所在城市" }
        load(Place(city, location.getDouble("latitude"), location.getDouble("longitude")), card)
            .copy(weatherAuto = true)
    }

    internal fun condition(code: Int): String = when (code) {
        0 -> "晴"; 1 -> "少云"; 2 -> "多云"; 3 -> "阴"
        45, 48 -> "雾"; 51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67, 80, 81, 82 -> "雨"
        71, 73, 75, 77, 85, 86 -> "雪"; 95, 96, 99 -> "雷雨"
        else -> "天气变化"
    }
}
