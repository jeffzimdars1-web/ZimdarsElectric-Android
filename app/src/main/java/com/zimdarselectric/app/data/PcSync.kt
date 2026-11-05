package com.zimdarselectric.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Zimdars Electric for PC: once a PC is linked, the phone keeps a copy of its data (and photo copies) in your private cloud
 * for the PC program to show. Jobs made on the PC come back through the inbox.
 */
object PcSync {
    private const val API = "https://dmaslbfwpsfnnnyushbc.supabase.co/functions/v1/pc"
    private val tables = listOf("Customer", "Material", "Quote", "QuoteLine", "Trip", "StockMove", "Expense", "Attachment", "Settings", "JobPhoto")
    /** Never leaves the phone. */
    private val secret = setOf("signKey")
    private val lock = Mutex()
    private fun prefs(c: Context) = c.getSharedPreferences("pc", Context.MODE_PRIVATE)

    fun linked(c: Context) = prefs(c).getBoolean("linked", false)
    fun lastSent(c: Context) = prefs(c).getLong("last", 0L)
    fun lastError(c: Context): String? = prefs(c).getString("error", null)

    private fun call(key: String, body: JSONObject): JSONObject {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 40000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-app-key", key.trim())
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode == 401) throw IllegalStateException("The signing key in Settings isn't right.")
            if (c.responseCode != 200) throw IllegalStateException("PC link error ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** Gets a one-time 8-letter code to type into the PC program. */
    suspend fun pairCode(context: Context): String = withContext(Dispatchers.IO) {
        val key = AppDb.get(context).dao().settingsNow()?.signKey.orEmpty()
        if (key.isBlank()) throw IllegalStateException("Add your signing key in Settings first.")
        val code = call(key, JSONObject().put("action", "pair_start")).getString("code")
        prefs(context).edit().putBoolean("linked", true).apply()
        code
    }

    fun unlink(context: Context) = prefs(context).edit().putBoolean("linked", false).remove("hash").apply()

    /** Every table as rows of {column: value}. */
    private fun snapshot(context: Context): JSONObject {
        val db = AppDb.get(context).openHelper.readableDatabase
        val out = JSONObject().put("version", 1).put("made", System.currentTimeMillis())
        for (t in tables) {
            val rows = JSONArray()
            runCatching {
                db.query("SELECT * FROM `$t`").use { cur ->
                    while (cur.moveToNext()) {
                        val o = JSONObject()
                        for (i in 0 until cur.columnCount) {
                            val name = cur.getColumnName(i)
                            if (t == "Settings" && name in secret) continue
                            when (cur.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(name, cur.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(name, cur.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> o.put(name, cur.getString(i))
                                else -> {}
                            }
                        }
                        rows.put(o)
                    }
                }
            }
            out.put(t, rows)
        }
        return out
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Sends the latest data (only if something changed) and any new photos. Quiet no-op until a PC is linked. */
    suspend fun push(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!linked(context)) return@withContext
        lock.withLock {
            val key = AppDb.get(context).dao().settingsNow()?.signKey.orEmpty()
            if (key.isBlank()) return@withLock
            try {
                val snap = snapshot(context)
                val body = snap.toString()
                val h = sha(body.replace(Regex("\"made\":\\d+"), ""))
                if (force || h != prefs(context).getString("hash", null)) {
                    call(key, JSONObject().put("action", "put_snapshot").put("data", snap))
                    prefs(context).edit().putString("hash", h).apply()
                }
                uploadPhotos(context, key)
                prefs(context).edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
            } catch (e: Exception) {
                prefs(context).edit().putString("error", e.message ?: "Couldn't reach the PC link").apply()
            }
        }
    }

    /** Photo copies (smaller, 1600 px) so the PC can show job and house photos. A few at a time. */
    private fun uploadPhotos(context: Context, key: String) {
        val done = prefs(context).getStringSet("photos", emptySet()).orEmpty().toMutableSet()
        val db = AppDb.get(context).openHelper.readableDatabase
        val todo = mutableListOf<Pair<Long, String>>()
        db.query("SELECT id, path FROM JobPhoto").use { c -> while (c.moveToNext()) { val id = c.getLong(0); if ("$id" !in done) todo += id to c.getString(1) } }
        for ((id, path) in todo.take(25)) {
            val f = File(path)
            if (!f.exists()) { done += "$id"; continue }
            val bytes = shrink(f) ?: continue
            val url = call(key, JSONObject().put("action", "photo_upload_url").put("name", "$id.jpg")).getString("url")
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("Content-Type", "image/jpeg"); setRequestProperty("x-upsert", "true")
            }
            try {
                c.outputStream.use { it.write(bytes) }
                if (c.responseCode in 200..299) done += "$id"
            } finally { c.disconnect() }
        }
        prefs(context).edit().putStringSet("photos", done).apply()
    }

    private fun shrink(f: File): ByteArray? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }.getOrNull()
}
