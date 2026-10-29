package com.zimdarselectric.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.CatalogItem
import com.zimdarselectric.app.data.LabelGuess
import com.zimdarselectric.app.data.LabelReader
import com.zimdarselectric.app.data.money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Reads the printed text and any barcodes in a photo, on the phone. Never throws; returns an empty guess on failure. */
suspend fun readLabel(photo: File): LabelGuess {
    val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(photo.path) } ?: return LabelGuess()
    val image = InputImage.fromBitmap(bmp, 0)
    val text = suspendCancellableCoroutine { cont ->
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resume("") }
    }
    val codes = suspendCancellableCoroutine { cont ->
        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { list -> cont.resume(list.mapNotNull { it.rawValue?.trim() }.filter { it.length >= 6 }) }
            .addOnFailureListener { cont.resume(emptyList()) }
    }
    return withContext(Dispatchers.Default) { LabelReader.read(text, codes) }
}

class LabelScanner(val launch: () -> Unit, val busy: Boolean)

/**
 * Opens the camera; after the photo is taken it is shrunk, read, and handed back with what was found.
 * The photo file is yours to keep (as the item photo) or discard.
 */
@Composable
fun rememberLabelScanner(vm: AppViewModel, onResult: (photo: File, guess: LabelGuess) -> Unit): LabelScanner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onResult)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPath?.let { File(it) }
        pendingPath = null
        if (ok && f != null) {
            busy = true
            vm.shrinkItemPhoto(f) { good ->
                if (!good) { busy = false; return@shrinkItemPhoto }
                scope.launch {
                    val guess = runCatching { readLabel(f) }.getOrDefault(LabelGuess())
                    busy = false
                    callback(f, guess)
                }
            }
        } else f?.delete()
    }
    return LabelScanner(launch = {
        val f = vm.newItemPhotoFile()
        pendingPath = f.path
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
    }, busy = busy)
}

/** Shows what was read from the label, with the catalog matches to tap. */
@Composable
fun LabelResult(guess: LabelGuess, onPick: (CatalogItem) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Z.bg).padding(10.dp)) {
        if (!guess.found) {
            Label("Couldn't read this label", color = Z.warn)
            Text("Try closer, flat-on, with good light. Or type it in.", color = Z.muted, fontSize = 12.sp)
            return@Column
        }
        Label("Read from the label", color = Z.neon)
        val facts = listOfNotNull(
            guess.brand, guess.model,
            guess.rollFeet?.let { "$it ft" }, guess.packCount?.let { "pack of $it" },
            guess.price?.let { money(it) }, guess.barcodes.firstOrNull()?.let { "barcode $it" },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Z.text, fontSize = 13.sp)
        if (guess.matches.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label(if (guess.matches.size > 1) "Looks like · tap the right one" else "Looks like")
            guess.matches.forEach { c ->
                Text("› " + c.name + (if (c.spec.isNotBlank()) " · ${c.spec}" else ""), color = Z.text, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 6.dp))
            }
        }
    }
}
