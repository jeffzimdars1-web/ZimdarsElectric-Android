package com.zimdarselectric.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Your price book. The real copy lives online (Supabase) so it can be updated without a new app
 * version; the app keeps the last copy it downloaded so it works with no signal.
 */
data class PriceBook(
    val title: String,
    val updated: String,
    val intro: String,
    val rates: List<Rate>,
    val steps: List<String>,
    val sections: List<Section>,
    val callouts: List<Callout>,
    val examples: List<Example>,
    val footer: String,
) {
    data class Rate(val value: String, val label: String)
    data class Item(val name: String, val note: String, val prices: List<String>)
    data class Section(val title: String, val columns: List<String>, val items: List<Item>)
    data class Callout(val kind: String, val title: String, val bullets: List<String>)
    data class Example(val title: String, val lines: List<Pair<String, String>>, val total: Pair<String, String>?, val note: String)

    companion object {
        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.pair(): Pair<String, String>? = if (this == null || length() < 2) null else optString(0) to optString(1)

        /** Reads the price book; unknown or missing parts are skipped so a newer format never breaks the app. */
        fun parse(text: String): PriceBook {
            val o = JSONObject(text)
            return PriceBook(
                title = o.optString("title", "Price Book"),
                updated = o.optString("updated"),
                intro = o.optString("intro"),
                rates = o.optJSONArray("rates").objects().map { Rate(it.optString("value"), it.optString("label")) },
                steps = o.optJSONArray("steps").strings(),
                sections = o.optJSONArray("sections").objects().map { s ->
                    Section(s.optString("title"), s.optJSONArray("columns").strings(),
                        s.optJSONArray("items").objects().map { Item(it.optString("name"), it.optString("note"), it.optJSONArray("prices").strings()) })
                },
                callouts = o.optJSONArray("callouts").objects().map { Callout(it.optString("kind"), it.optString("title"), it.optJSONArray("bullets").strings()) },
                examples = o.optJSONArray("examples").objects().map { e ->
                    val lines = e.optJSONArray("lines")
                    Example(e.optString("title"), if (lines == null) emptyList() else (0 until lines.length()).mapNotNull { lines.optJSONArray(it).pair() },
                        e.optJSONArray("total").pair(), e.optString("note"))
                },
                footer = o.optString("footer"),
            )
        }
    }
}

object PriceBookStore {
    private const val API = "https://dmaslbfwpsfnnnyushbc.supabase.co/functions/v1/content"
    private fun cacheFile(context: Context) = File(context.filesDir, "pricebook.json")

    /** The copy saved on the phone, or null if it has never been downloaded. */
    fun cached(context: Context): PriceBook? = runCatching { PriceBook.parse(cacheFile(context).readText()) }.getOrNull()

    /** Downloads the latest copy and saves it. Throws with a plain-language message on failure. */
    suspend fun refresh(context: Context, key: String): PriceBook = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw IllegalStateException("Add your signing key in Settings to load the price book.")
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-app-key", key.trim())
        }
        try {
            c.outputStream.use { it.write("""{"key":"pricebook"}""".toByteArray()) }
            when (c.responseCode) {
                200 -> {}
                401 -> throw IllegalStateException("The signing key in Settings isn't right.")
                else -> throw IllegalStateException("Couldn't load the price book (${c.responseCode}).")
            }
            val data = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("data").toString()
            val book = PriceBook.parse(data)
            cacheFile(context).writeText(data)
            book
        } finally { c.disconnect() }
    }
}
