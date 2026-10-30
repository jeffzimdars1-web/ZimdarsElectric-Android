package com.zimdarselectric.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Nightly backup of everything in the app (quotes, customers, books, stock, receipt copies, photos, signatures)
 * to a private folder in your Supabase. Restore puts it all back, e.g. on a new phone.
 */
object Backup {
    private const val API = "https://dmaslbfwpsfnnnyushbc.supabase.co/functions/v1/backup"
    private const val DB = "zimdars.db"
    private fun prefs(c: Context) = c.getSharedPreferences("backup", Context.MODE_PRIVATE)

    data class Entry(val name: String, val size: Long) {
        /** "2026-10-30T08-55-00.zip" → readable local time. */
        val label: String get() = runCatching {
            val iso = name.removeSuffix(".zip").replaceRange(13, 14, ":").replaceRange(16, 17, ":")
            val t = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
            t.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, yyyy · h:mm a"))
        }.getOrDefault(name)
    }

    fun lastBackup(c: Context): Long = prefs(c).getLong("last", 0L)
    fun lastError(c: Context): String? = prefs(c).getString("error", null)

    private fun call(key: String, body: JSONObject): JSONObject {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 30000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-app-key", key.trim())
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode == 401) throw IllegalStateException("The signing key in Settings isn't right.")
            if (c.responseCode != 200) throw IllegalStateException("Backup service error ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** Zips the database and the app's files. */
    private fun makeZip(context: Context, out: File) {
        val db = AppDb.get(context)
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
        val dbFile = context.getDatabasePath(DB)
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            fun add(f: File, name: String) {
                zip.putNextEntry(ZipEntry(name)); FileInputStream(f).use { it.copyTo(zip) }; zip.closeEntry()
            }
            add(dbFile, "db/$DB")
            listOf("-wal", "-shm").map { File(dbFile.path + it) }.filter { it.exists() && it.length() > 0 }.forEach { add(it, "db/${it.name}") }
            val root = context.filesDir
            root.walkTopDown().filter { it.isFile }.forEach { f -> add(f, "files/" + f.relativeTo(root).path) }
        }
    }

    /** Backs up now. Returns a short result for the screen. */
    suspend fun backupNow(context: Context): String = withContext(Dispatchers.IO) {
        val key = AppDb.get(context).dao().settingsNow()?.signKey.orEmpty()
        if (key.isBlank()) return@withContext "Add your signing key in Settings first."
        val zip = File(context.cacheDir, "backup.zip")
        try {
            makeZip(context, zip)
            val up = call(key, JSONObject().put("action", "upload_url"))
            val c = (URL(up.getString("url")).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/zip"); setFixedLengthStreamingMode(zip.length())
            }
            try {
                FileInputStream(zip).use { input -> c.outputStream.use { input.copyTo(it) } }
                if (c.responseCode !in 200..299) throw IllegalStateException("Upload failed (${c.responseCode})")
            } finally { c.disconnect() }
            runCatching { call(key, JSONObject().put("action", "prune").put("keep", 14)) }
            prefs(context).edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
            "Backed up (${"%.1f".format(zip.length() / 1_048_576.0)} MB)."
        } catch (e: Exception) {
            val msg = e.message ?: "Backup failed."
            prefs(context).edit().putString("error", msg).apply()
            msg
        } finally { zip.delete() }
    }

    suspend fun list(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        val key = AppDb.get(context).dao().settingsNow()?.signKey.orEmpty()
        val arr = call(key, JSONObject().put("action", "list")).getJSONArray("items")
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Entry(o.getString("name"), o.optLong("size")) } }
    }

    /**
     * Replaces everything on this phone with the backup. The app must close and reopen afterwards.
     * The download is checked before anything on the phone is touched.
     */
    suspend fun restore(context: Context, name: String) = withContext(Dispatchers.IO) {
        val key = AppDb.get(context).dao().settingsNow()?.signKey.orEmpty()
        val url = call(key, JSONObject().put("action", "download_url").put("name", name)).getString("url")
        val zip = File(context.cacheDir, "restore.zip")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (c.responseCode != 200) throw IllegalStateException("Download failed (${c.responseCode})")
            c.inputStream.use { input -> FileOutputStream(zip).use { input.copyTo(it) } }
        } finally { c.disconnect() }
        // Check it has a database before replacing anything.
        val names = ZipInputStream(FileInputStream(zip)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        if ("db/$DB" !in names) { zip.delete(); throw IllegalStateException("That backup looks damaged. Nothing was changed.") }
        AppDb.close()
        val dbFile = context.getDatabasePath(DB)
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        val filesRoot = context.filesDir.canonicalFile
        ZipInputStream(FileInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                if (e.isDirectory) return@forEach
                val target = when {
                    e.name.startsWith("db/") -> File(dbFile.parentFile, e.name.removePrefix("db/"))
                    e.name.startsWith("files/") -> File(filesRoot, e.name.removePrefix("files/"))
                    else -> null
                }?.canonicalFile ?: return@forEach
                // Only write inside the app's own folders.
                if (!target.path.startsWith(filesRoot.path) && target.parentFile?.canonicalPath != dbFile.parentFile?.canonicalPath) return@forEach
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { z.copyTo(it) }
            }
        }
        zip.delete()
    }

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("nightly-backup", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Skip if one already ran in the last 20 hours (e.g. "Back up now").
        if (System.currentTimeMillis() - Backup.lastBackup(applicationContext) < 20 * 3_600_000L) return Result.success()
        val r = Backup.backupNow(applicationContext)
        return if (r.startsWith("Backed up")) Result.success() else Result.retry()
    }
}
