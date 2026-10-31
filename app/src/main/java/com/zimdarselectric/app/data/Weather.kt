package com.zimdarselectric.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/** Free forecasts from the National Weather Service (api.weather.gov), cached for 30 minutes. */
object Weather {
    private const val UA = "ZimdarsElectric-Android/1.0 (field app)"
    private const val TTL = 30 * 60_000L

    data class Hour(val temp: Int, val text: String, val rainPct: Int?, val wind: String, val day: Boolean = true) {
        val icon: String get() = icon(text, day)
        /** "🌧 48° Rain Likely · 70% rain · 10 mph" */
        val line: String get() = "$icon $temp° $text" + (rainPct?.takeIf { it >= 20 }?.let { " · $it% rain" } ?: "") +
            wind.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }

    fun icon(text: String, day: Boolean = true): String {
        val t = text.lowercase()
        return when {
            "thunder" in t -> "⛈"
            "snow" in t || "flurr" in t || "sleet" in t -> "❄️"
            "rain" in t || "shower" in t || "drizzle" in t -> "🌧"
            "fog" in t || "haze" in t -> "🌫"
            "partly" in t || "mostly sunny" in t -> "⛅"
            "cloud" in t || "overcast" in t -> "☁️"
            "sunny" in t || "clear" in t -> if (day) "☀️" else "🌙"
            !day && ("partly" in t || "mostly clear" in t) -> "🌙"
            "wind" in t || "breezy" in t -> "💨"
            else -> "🌤"
        }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/geo+json")
        }
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /** Forecast for the hour that contains [atMs] (or the current hour) at a place. Null if offline or out of range. */
    suspend fun at(context: Context, lat: Double, lon: Double, atMs: Long = System.currentTimeMillis()): Hour? = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
            val key = "%.2f,%.2f".format(java.util.Locale.US, lat, lon)
            val now = System.currentTimeMillis()
            var periods = prefs.getString("p_$key", null)?.takeIf { now - prefs.getLong("t_$key", 0) < TTL }?.let { JSONArray(it) }
            if (periods == null) {
                val hourlyUrl = prefs.getString("u_$key", null) ?: JSONObject(get("https://api.weather.gov/points/$key"))
                    .getJSONObject("properties").getString("forecastHourly").also { prefs.edit().putString("u_$key", it).apply() }
                periods = JSONObject(get(hourlyUrl)).getJSONObject("properties").getJSONArray("periods")
                prefs.edit().putString("p_$key", periods.toString()).putLong("t_$key", now).apply()
            }
            var pick: JSONObject? = null
            for (i in 0 until periods.length()) {
                val p = periods.getJSONObject(i)
                val start = OffsetDateTime.parse(p.getString("startTime")).toInstant().toEpochMilli()
                val end = OffsetDateTime.parse(p.getString("endTime")).toInstant().toEpochMilli()
                if (atMs in start until end) { pick = p; break }
            }
            pick ?: return@runCatching null
            val temp = pick.optDouble("temperature").let { t -> if (pick.optString("temperatureUnit") == "C") (t * 9 / 5 + 32) else t }.roundToInt()
            val rain = pick.optJSONObject("probabilityOfPrecipitation")?.let { if (it.isNull("value")) null else it.optInt("value") }
            Hour(temp, pick.optString("shortForecast"), rain, pick.optString("windSpeed").replace(" to ", "–"), pick.optBoolean("isDaytime", true))
        }.getOrNull()
    }
}
