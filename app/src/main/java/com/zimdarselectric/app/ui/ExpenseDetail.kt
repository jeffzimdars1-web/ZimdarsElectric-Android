package com.zimdarselectric.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.Attachment
import com.zimdarselectric.app.data.money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExpenseDetailScreen(vm: AppViewModel, expenseId: Long, onBack: () -> Unit) {
    val expenses by vm.expenses.collectAsState()
    val allAttachments by vm.attachments.collectAsState()
    val context = LocalContext.current
    val e = expenses.firstOrNull { it.id == expenseId }
    if (e == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This expense was deleted.", color = Z.muted) }
        return
    }
    val attachments = allAttachments.filter { it.expenseId == e.id }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Attachment?>(null) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var stockLine by remember { mutableStateOf<com.zimdarselectric.app.data.ReceiptItem?>(null) }
    val materials by vm.materials.collectAsState()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        if (ok && f != null) vm.attachPhoto(e.id, f) else f?.delete()
        pendingPhoto = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachUri(e.id, uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Z.raised, cut()).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("←", color = Z.text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label("Expense")
                Text(e.vendor.uppercase(), fontFamily = Condensed, fontSize = 24.sp, color = Z.text, maxLines = 1)
            }
            Label("Edit", color = Z.neon, modifier = Modifier.clickable { editing = true }.padding(8.dp))
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(16.dp))
            Label("Amount paid")
            Text(money(e.amount), fontFamily = Condensed, fontSize = 48.sp, color = Z.text)
            Spacer(Modifier.height(6.dp))
            MoneyRow("Date", SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(Date(e.date)))
            MoneyRow("Vendor", e.vendor)
            MoneyRow("Tax category", e.category)
            if (e.note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Label("What it was for")
                Text(e.note, color = Z.text, fontSize = 15.sp)
            }

            val lines = com.zimdarselectric.app.data.ReceiptItem.from(e)
            if (lines.isNotEmpty()) {
                SectionHeader("", "What you bought", "${lines.size}")
                lines.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { stockLine = r }.padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = Z.text, fontSize = 14.sp)
                            Label(receiptLineStatus(r, materials), color = if (r.decided) Z.muted else Z.neon)
                            Label(listOf(if (r.qty != 1.0) "${com.zimdarselectric.app.data.qtyText(r.qty)} × ${money(r.each)}" else "", if (r.model.isNotBlank()) "Model ${r.model}" else "").filter { it.isNotBlank() }.joinToString(" · "))
                        }
                        Text(money(r.paidEach * r.qty), color = Z.text, fontSize = 14.sp)
                    }
                }
                Label("Prices include each item's share of the sales tax.")
            }

            SectionHeader("01", "Receipt copies", "${attachments.size}")
            if (attachments.isEmpty()) {
                Row(Modifier.fillMaxWidth().background(Z.raised).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot("No receipt yet", Z.warn)
                }
                Spacer(Modifier.height(6.dp))
                Text("Snap the paper receipt, or attach the PDF or a screenshot of the email receipt.", color = Z.muted, fontSize = 14.sp)
            }
            attachments.forEach { a ->
                Spacer(Modifier.height(12.dp))
                ReceiptPreview(a, onOpen = { openExternally(context, a) }, onRemove = { removing = a })
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("Snap photo", Modifier.weight(1f)) {
                    val f = vm.newPhotoFile()
                    pendingPhoto = f
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                }
                GhostButton("Attach file", Modifier.weight(1f).height(54.dp)) {
                    picker.launch(arrayOf("application/pdf", "image/*"))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Keep receipts at least 3 years after you file. For equipment like the toolbox, keep them as long as you own it plus 3 years.",
                color = Z.muted, fontSize = 12.sp)
            Spacer(Modifier.height(40.dp))
        }
    }

    stockLine?.let { r -> ReceiptLineDialog(vm, r) { stockLine = null } }
    if (editing) ExpenseDialog(e, onDismiss = { editing = false }, onDelete = { vm.deleteExpense(e); editing = false; onBack() }) {
        vm.addExpense(it); editing = false
    }
    removing?.let { a ->
        ZDialog("Remove this receipt?", { removing = null }, confirm = "Remove" to { vm.deleteAttachment(a); removing = null }, dismiss = "Keep" to { removing = null }) {
            Text(a.fileName, color = Z.muted)
        }
    }
}

@Composable
private fun ReceiptPreview(a: Attachment, onOpen: () -> Unit, onRemove: () -> Unit) {
    val isPdf = a.mime.contains("pdf") || a.path.endsWith(".pdf", true)
    val pages by produceState<List<Bitmap>?>(initialValue = null, a.path) {
        value = withContext(Dispatchers.IO) { runCatching { if (isPdf) renderPdf(File(a.path)) else listOfNotNull(decodeImage(File(a.path))) }.getOrNull() ?: emptyList() }
    }
    Column(Modifier.fillMaxWidth().background(Z.raised)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.fileName, color = Z.text, fontSize = 14.sp, maxLines = 1)
                Label(if (isPdf) "PDF" else "Photo")
            }
            Label("Open", color = Z.neon, modifier = Modifier.clickable { onOpen() }.padding(6.dp))
            Spacer(Modifier.width(6.dp))
            Label("Remove", color = Z.warn, modifier = Modifier.clickable { onRemove() }.padding(6.dp))
        }
        val list = pages
        when {
            list == null -> Text("Loading…", color = Z.muted, modifier = Modifier.padding(12.dp))
            list.isEmpty() -> Text("Can't preview this file. Tap Open.", color = Z.muted, modifier = Modifier.padding(12.dp))
            else -> list.forEach { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = "Receipt ${a.fileName}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White).clickable { onOpen() },
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private fun decodeImage(f: File, maxSide: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun renderPdf(f: File, maxPages: Int = 3, width: Int = 1100): List<Bitmap> {
    val out = mutableListOf<Bitmap>()
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { r ->
            for (i in 0 until minOf(r.pageCount, maxPages)) {
                r.openPage(i).use { page ->
                    val h = (width.toFloat() / page.width * page.height).toInt()
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AndroidColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(bmp)
                }
            }
        }
    }
    return out
}

private fun openExternally(context: android.content.Context, a: Attachment) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(a.path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
