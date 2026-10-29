package com.zimdarselectric.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.Customer
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val whenFmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)

@Composable
fun SignatureSection(vm: AppViewModel, q: Quote, customer: Customer?, settings: Settings) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linkReady by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun send() {
        if (q.customerName.isBlank()) { error = "Choose a customer first."; return }
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        busy = true; error = null
        vm.sendForSignature(q) { url, err -> busy = false; error = err; if (url != null) linkReady = url }
    }

    when {
        settings.signKey.isBlank() -> {
            Text("To have customers sign on their phone, add your signing key in More → Settings.", color = Z.muted, fontSize = 14.sp)
        }

        q.signedAt != null -> {
            StatusDot("Signed · approved", Z.ok)
            Spacer(Modifier.height(8.dp))
            SignatureImage(q.signaturePath)
            Text(q.signerName, color = Z.text, fontSize = 17.sp)
            Label("Signed ${whenFmt.format(Date(q.signedAt))}")
            if (q.changedSinceSent) {
                Spacer(Modifier.height(10.dp))
                Text("You changed this quote after the customer signed. They approved the earlier version. If the work or price changed, send it again for a new signature.",
                    color = Z.warn, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                GhostButton(if (busy) "Sending…" else "Send updated quote to sign", Modifier.fillMaxWidth(), color = Z.warn) { if (!busy) send() }
            }
        }

        q.awaitingSignature -> {
            StatusDot("Waiting for signature", Z.info)
            Spacer(Modifier.height(4.dp))
            Label("Sent ${q.sentAt?.let { whenFmt.format(Date(it)) } ?: ""}" + (q.viewedAt?.let { " · opened ${whenFmt.format(Date(it))}" } ?: " · not opened yet"))
            if (q.changedSinceSent) {
                Spacer(Modifier.height(10.dp))
                Text("You changed this quote after sending it. The customer still sees the old version.", color = Z.warn, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                NeonButton(if (busy) "Sending…" else "Send updated quote", Modifier.fillMaxWidth(), small = "The old link stops working") { if (!busy) send() }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Send link again", Modifier.weight(1f)) { linkReady = q.signUrl }
                GhostButton(if (busy) "Checking…" else "Check now", Modifier.weight(1f)) {
                    if (!busy) {
                        busy = true; error = null
                        vm.checkSignaturesNow { n, err -> busy = false; error = err; if (err == null && n == 0) Toast.makeText(context, "Not signed yet", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            Label("Cancel link", color = Z.warn, modifier = Modifier.clickable { confirmCancel = true }.padding(vertical = 10.dp))
        }

        else -> {
            NeonButton(if (busy) "Creating link…" else "Send for signature", Modifier.fillMaxWidth(), small = "Customer reviews and signs on their phone") { if (!busy) send() }
            Spacer(Modifier.height(6.dp))
            Text("When they sign, this quote switches to Approved by itself and you get a notification.", color = Z.muted, fontSize = 13.sp)
        }
    }
    error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Z.warn, fontSize = 13.sp) }

    linkReady?.let { url ->
        val msg = buildString {
            append("Hi ${(customer?.name ?: q.customerName).substringBefore(' ')}, here's your quote ${q.number} from ${settings.businessName}")
            if (q.title.isNotBlank()) append(" for ${q.title}")
            append(". You can look it over and sign it right on your phone: $url")
            append("\n\nThanks!")
            if (settings.phone.isNotBlank()) append(" ${settings.phone}")
        }
        ZDialog("Link ready", { linkReady = null }, confirm = "Done" to { linkReady = null }) {
            Text("Send it to ${customer?.name ?: q.customerName}:", color = Z.muted)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val phone = customer?.phone.orEmpty()
                val email = customer?.email.orEmpty()
                if (phone.isNotBlank()) NeonButton("Text $phone", Modifier.fillMaxWidth()) {
                    launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", msg))
                }
                if (email.isNotBlank()) GhostButton("Email $email", Modifier.fillMaxWidth()) {
                    launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                        .putExtra(Intent.EXTRA_SUBJECT, "Quote ${q.number} from ${settings.businessName}").putExtra(Intent.EXTRA_TEXT, msg))
                }
                GhostButton("Share another way", Modifier.fillMaxWidth()) {
                    launch(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Send quote link"))
                }
                GhostButton("Copy link", Modifier.fillMaxWidth()) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Quote link", url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                }
            }
            if (customer?.phone.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Add the customer's cell number in Customers to text it with one tap.", color = Z.muted, fontSize = 12.sp)
            }
        }
    }

    if (confirmCancel) ZDialog("Cancel the link?", { confirmCancel = false },
        confirm = "Cancel link" to {
            confirmCancel = false; busy = true
            vm.cancelSignature(q) { err -> busy = false; error = err }
        }, dismiss = "Keep" to { confirmCancel = false }) {
        Text("The customer won't be able to sign it anymore. You can send a new link later.", color = Z.muted)
    }
}

@Composable
fun SignatureImage(path: String) {
    if (path.isBlank()) return
    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() } }
    bmp?.let {
        Image(it.asImageBitmap(), contentDescription = "Customer signature", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 110.dp).background(androidx.compose.ui.graphics.Color.White).padding(8.dp))
        Spacer(Modifier.height(6.dp))
    }
}

/** Customer PDF and your job copy. */
@Composable
fun QuotePdfButtons(vm: AppViewModel, q: Quote) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf<File?>(null) }
    var making by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(if (making == "c") "Making…" else if (q.signedAt != null) "Signed PDF" else "Customer PDF", Modifier.weight(1f)) {
            making = "c"; vm.quotePdf(q, jobCopy = false) { f -> making = null; ready = f; if (f == null) Toast.makeText(context, "Couldn't make the PDF", Toast.LENGTH_SHORT).show() }
        }
        GhostButton(if (making == "j") "Making…" else "Job copy", Modifier.weight(1f)) {
            making = "j"; vm.quotePdf(q, jobCopy = true) { f -> making = null; ready = f; if (f == null) Toast.makeText(context, "Couldn't make the PDF", Toast.LENGTH_SHORT).show() }
        }
    }
    ready?.let { f ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        ZDialog(f.name, { ready = null },
            confirm = "Send…" to {
                launch(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send PDF"))
                ready = null
            },
            dismiss = "Open" to {
                launch(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                ready = null
            }) {
            Text("Open it to look it over or print, or send it by text or email.", color = Z.muted)
        }
    }
}

private fun launch(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "No app on this phone can do that.", Toast.LENGTH_SHORT).show() }
}
