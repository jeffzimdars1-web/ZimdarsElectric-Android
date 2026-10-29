package com.zimdarselectric.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small in-memory cache so scrolling the stock list doesn't re-read photos. */
private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

private fun loadScaled(path: String, maxSide: Int): Bitmap? {
    val key = "$path@$maxSide"
    thumbCache.get(key)?.let { return it }
    val f = File(path)
    if (!f.exists()) return null
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, b)
    var sample = 1
    while (b.outWidth / (sample * 2) >= maxSide && b.outHeight / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    thumbCache.put(key, bmp)
    return bmp
}

/**
 * Square photo of a stock item. With no photo, shows an empty outlined box
 * (or nothing, when [hideIfNone]) — never a stand-in picture.
 */
@Composable
fun ItemThumb(path: String, size: Dp, modifier: Modifier = Modifier, hideIfNone: Boolean = false, onClick: (() -> Unit)? = null) {
    if (path.isBlank() && hideIfNone) return
    val px = (size.value * 3).toInt().coerceAtLeast(96)
    val bmp by produceState<Bitmap?>(initialValue = thumbCache.get("$path@$px"), path, px) {
        if (value == null && path.isNotBlank()) value = withContext(Dispatchers.IO) { runCatching { loadScaled(path, px) }.getOrNull() }
    }
    val base = modifier.size(size).let { if (onClick != null) it.clickable { onClick() } else it }
    val img = bmp
    if (img != null) {
        Image(img.asImageBitmap(), contentDescription = "Item photo", contentScale = ContentScale.Crop, modifier = base.background(Z.raised))
    } else {
        Box(base.border(1.dp, Z.line).background(Z.raised), contentAlignment = Alignment.Center) {
            if (path.isBlank() && size >= 40.dp) Text("NO\nPHOTO", fontFamily = Mono, fontSize = 8.sp, color = Z.muted, lineHeight = 9.sp)
        }
    }
}

/** Full-screen view of an item photo. Tap anywhere to close. */
@Composable
fun PhotoViewer(path: String, title: String, onClose: () -> Unit) {
    val bmp by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { runCatching { loadScaled(path, 1600) }.getOrNull() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { onClose() }) {
            Text(title, color = Z.text, fontFamily = Condensed, fontSize = 22.sp, modifier = Modifier.padding(20.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    ?: Text("Loading…", color = Z.muted)
            }
            Label("Tap to close", modifier = Modifier.padding(20.dp))
        }
    }
}
