package com.zimdarselectric.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Free map services, no account or key:
 *  - Address search: Photon (OpenStreetMap data), centered on the Mohawk Valley.
 *  - Driving time and distance: OSRM public router (no live traffic).
 *  - Your location: the phone's own GPS.
 */
object Geo {
    /** Ilion, NY: searches prefer nearby places. */
    private const val HOME_LAT = 43.015
    private const val HOME_LON = -75.035
    private const val UA = "ZimdarsElectric-Android/1.0 (field app for Zimdars Electric LLC)"

    data class Place(val label: String, val lat: Double, val lon: Double)
    data class Route(val minutes: Int, val miles: Double, val traffic: Boolean = false)

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000; setRequestProperty("User-Agent", UA)
        }
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /** Address suggestions as you type, nearest first. */
    suspend fun suggest(query: String, limit: Int = 5): List<Place> = withContext(Dispatchers.IO) {
        if (query.trim().length < 3) return@withContext emptyList()
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val json = JSONObject(get("https://photon.komoot.io/api/?q=$q&limit=${limit + 3}&lang=en&lat=$HOME_LAT&lon=$HOME_LON"))
            val f = json.optJSONArray("features") ?: return@runCatching emptyList()
            (0 until f.length()).mapNotNull { i ->
                val o = f.getJSONObject(i)
                val p = o.optJSONObject("properties") ?: return@mapNotNull null
                if (p.optString("countrycode").uppercase() !in setOf("US", "")) return@mapNotNull null
                val c = o.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                Place(format(p), c.getDouble(1), c.getDouble(0)).takeIf { it.label.isNotBlank() }
            }.distinctBy { it.label }.take(limit)
        }.getOrDefault(emptyList())
    }

    private val stateAbbr = mapOf("New York" to "NY", "Pennsylvania" to "PA", "New Jersey" to "NJ", "Vermont" to "VT",
        "Massachusetts" to "MA", "Connecticut" to "CT")

    /** "123 Main St, Ilion, NY 13357" from an OpenStreetMap result. */
    fun format(p: JSONObject): String {
        val street = listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
        val first = street.ifBlank { p.optString("name") }
        val town = p.optString("city").ifBlank { p.optString("town").ifBlank { p.optString("village").ifBlank { p.optString("district") } } }
        val state = p.optString("state").let { stateAbbr[it] ?: it }
        val stateZip = listOf(state, p.optString("postcode")).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(shortStreet(first), town, stateZip).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun shortStreet(s: String) = s.replace(Regex("\\bStreet\\b"), "St").replace(Regex("\\bAvenue\\b"), "Ave")
        .replace(Regex("\\bRoad\\b"), "Rd").replace(Regex("\\bDrive\\b"), "Dr").replace(Regex("\\bLane\\b"), "Ln")

    /** Where an address is, for the map and arrival detection. */
    suspend fun locate(address: String): Place? = if (address.isBlank()) null else suggest(address, 1).firstOrNull()

    /** Drive time and miles between two points. Falls back to a straight-line estimate with no signal. */
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get("https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=false"))
            val r = j.getJSONArray("routes").getJSONObject(0)
            Route(Math.round(r.getDouble("duration") / 60.0).toInt().coerceAtLeast(1), r.getDouble("distance") / 1609.344)
        }.getOrElse {
            val miles = metersBetween(fromLat, fromLon, toLat, toLon) / 1609.344 * 1.3
            Route(Math.round(miles / 40.0 * 60).toInt().coerceAtLeast(1), miles)
        }
    }

    fun metersBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    fun canLocate(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Where the phone is right now (waits up to 15 seconds for GPS), or its last known spot. */
    @SuppressLint("MissingPermission")
    suspend fun here(context: Context): Location? {
        if (!canLocate(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        val fresh = if (Build.VERSION.SDK_INT >= 30) withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
            }
        } else null
        return fresh ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** IRS business mileage rate for a trip on [date]: 72.5¢ through June 30, 2026, 76¢ from July 1, 2026. */
    fun irsRate(date: Long): Double {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = date }
        val y = c.get(java.util.Calendar.YEAR); val m = c.get(java.util.Calendar.MONTH)
        return when {
            y < 2026 -> 0.70
            y == 2026 && m < java.util.Calendar.JULY -> 0.725
            else -> 0.76
        }
    }
}
