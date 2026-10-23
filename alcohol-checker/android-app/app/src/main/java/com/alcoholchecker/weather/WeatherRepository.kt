package com.alcoholchecker.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class WeatherInfo(
    val label: String,     // "晴れ" / "曇り" / "雨" 等
    val tempCelsius: Float?
)

object WeatherRepository {

    // BuildConfig.WEATHER_API_KEY で渡すか、空のままにすると手動入力モードになる
    private const val API_KEY = "61d25d445b9b9fb1522469c4c5b79509"   // ← 実際のキーをここか local.properties で設定

    private val weatherJaMap = mapOf(
        "Clear"        to "晴れ",
        "Clouds"       to "曇り",
        "Rain"         to "雨",
        "Drizzle"      to "小雨",
        "Thunderstorm" to "雷雨",
        "Snow"         to "雪",
        "Mist"         to "霧",
        "Fog"          to "霧",
        "Haze"         to "もや",
        "Dust"         to "砂塵",
        "Sand"         to "砂塵",
        "Ash"          to "灰",
        "Squall"       to "スコール",
        "Tornado"      to "竜巻",
    )

    /**
     * GPS 座標から天候を取得。API キーが空 or 失敗時は null を返す（手動選択に委ねる）。
     */
    suspend fun fetch(lat: Double, lon: Double): WeatherInfo? = withContext(Dispatchers.IO) {
        if (API_KEY.isBlank()) return@withContext null
        runCatching {
            val raw = URL(
                "https://api.openweathermap.org/data/2.5/weather" +
                "?lat=$lat&lon=$lon&appid=$API_KEY&units=metric&lang=ja"
            ).readText()
            val json  = JSONObject(raw)
            val main  = json.getString("weather").let { JSONObject(it.trim('[', ']')) }
                .optString("main", "")
            val temp  = json.getJSONObject("main").optDouble("temp", Double.NaN)
                .let { if (it.isNaN()) null else it.toFloat() }
            WeatherInfo(label = weatherJaMap[main] ?: main, tempCelsius = temp)
        }.getOrNull()
    }
}

/** 手動選択候補（API 失敗・オフライン時用） */
val WEATHER_OPTIONS = listOf("晴れ", "薄曇り", "曇り", "雨", "小雨", "雷雨", "雪", "霧")
