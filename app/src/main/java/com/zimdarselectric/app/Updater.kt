package com.zimdarselectric.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub releases page for a newer build, downloads the APK and hands it to Android's installer.
 * Release tags look like "v0.1.<build>"; the build number is the app's versionCode.
 */
object Updater {
    data class Release(val build: Int, val name: String, val notes: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val percent: Int) : State
        data class Ready(val file: File) : State
        data class Error(val message: String) : State
    }

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): State = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.github.com/repos/${BuildConfig.RELEASES_REPO}/releases/latest")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val code = conn.responseCode
            if (code == 404) return@withContext State.Error("No updates published yet.")
            if (code != 200) return@withContext State.Error("Update server answered $code.")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            val build = tag.substringAfterLast('.').filter { it.isDigit() }.toIntOrNull() ?: 0
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
                }
            }
            if (build > currentBuild && apkUrl.isNotEmpty()) {
                State.Available(Release(build, json.optString("name", tag), json.optString("body"), apkUrl))
            } else State.UpToDate
        } catch (e: Exception) {
            State.Error("Couldn't check for updates. Check your connection.")
        }
    }

    suspend fun download(context: Context, release: Release, progress: (Int) -> Unit): State = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "zimdars-electric-${release.build}.apk")
            var url = URL(release.apkUrl)
            var conn: HttpURLConnection
            // Follow GitHub's redirects to the file host by hand.
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    url = URL(url, conn.getHeaderField("Location")); hops++; continue
                }
                if (code != 200) return@withContext State.Error("Download failed ($code).")
                break
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) withContext(Dispatchers.Main) { progress((done * 100 / total).toInt()) }
                    }
                }
            }
            State.Ready(out)
        } catch (e: Exception) {
            State.Error("Download failed. Try again.")
        }
    }

    /** Opens Android's installer. If the app isn't allowed to install updates yet, opens that setting first. */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
