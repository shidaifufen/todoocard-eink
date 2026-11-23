package com.shidaifufen.todocard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** 仅按用户输入的城市获取公开天气资料，不读取位置权限。 */
object WeatherData {
    data class Place(val label: String, val latitude: Double, val longitude: Double)

    private fun json(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
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

    suspend fun load(place: Place, card: Render.Card): Render.Card = withContext(Dispatchers.IO) {
        val coordinate = "latitude=${place.latitude}&longitude=${place.longitude}"
        val data = json("https://api.open-meteo.com/v1/forecast?$coordinate&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4&timezone=auto")
        val current = data.getJSONObject("current")
        val daily = data.getJSONObject("daily")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val codes = daily.getJSONArray("weather_code")
        val dates = daily.getJSONArray("time")
        val aqi = runCatching {
            val value = json("https://air-quality-api.open-meteo.com/v1/air-quality?$coordinate&current=us_aqi")
                .getJSONObject("current").getDouble("us_aqi").roundToInt()
            value.toString()
        }.getOrDefault("")
        card.copy(
            weatherCity = place.label.substringBefore(" · "),
            weatherText = condition(current.getInt("weather_code")),
            weatherTemp = current.getDouble("temperature_2m").roundToInt().toString(),
            weatherHigh = highs.getDouble(0).roundToInt().toString(),
            weatherLow = lows.getDouble(0).roundToInt().toString(),
            weatherAqi = aqi,
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
        val latitude = card.weatherLatitude.toDoubleOrNull() ?: return card
        val longitude = card.weatherLongitude.toDoubleOrNull() ?: return card
        return runCatching { load(Place(card.weatherCity, latitude, longitude), card) }
            .getOrDefault(card)
    }

    private fun condition(code: Int): String = when (code) {
        0 -> "晴"; 1 -> "少云"; 2 -> "多云"; 3 -> "阴"
        45, 48 -> "雾"; 51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67, 80, 81, 82 -> "雨"
        71, 73, 75, 77, 85, 86 -> "雪"; 95, 96, 99 -> "雷雨"
        else -> "天气变化"
    }
}
