package com.zimdarselectric.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zimdarselectric.app.data.AppDb
import com.zimdarselectric.app.data.Attachment
import com.zimdarselectric.app.data.Customer
import com.zimdarselectric.app.data.Expense
import com.zimdarselectric.app.data.Material
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.QuoteLine
import com.zimdarselectric.app.data.QuotePdf
import com.zimdarselectric.app.data.SignService
import com.zimdarselectric.app.data.Settings
import com.zimdarselectric.app.data.Status
import com.zimdarselectric.app.data.StockMove
import com.zimdarselectric.app.data.holdsByMaterial
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()

    private fun <T> hot(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        flow.stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val customers = hot(dao.customers(), emptyList())
    val materials = hot(dao.materials(), emptyList())
    val quotes = hot(dao.quotes(), emptyList())
    val lines = hot(dao.allLines(), emptyList())
    val expenses = hot(dao.expenses(), emptyList())
    val attachments = hot(dao.attachments(), emptyList())
    /** Every item line from every receipt in Books, newest first. */
    val receiptItems: StateFlow<List<com.zimdarselectric.app.data.ReceiptItem>> =
        hot(dao.expenses().map { list -> list.flatMap { com.zimdarselectric.app.data.ReceiptItem.from(it) } }, emptyList())
    val settings: StateFlow<Settings> = hot(dao.settings().map { it ?: Settings() }, Settings())

    /** Material id -> quantity on hold across all open quotes. */
    val holds: StateFlow<Map<Long, Double>> = hot(
        combine(dao.quotes(), dao.allLines()) { q, l -> holdsByMaterial(q, l) }, emptyMap()
    )

    init {
        viewModelScope.launch { if (dao.settingsNow() == null) dao.saveSettings(Settings()) }
        // While the app is open, check for customer signatures every 20 seconds (only if a quote is waiting).
        viewModelScope.launch {
            while (true) {
                if (quotes.value.any { it.awaitingSignature }) {
                    runCatching { SignService.notifySigned(app, SignService.sync(app)) }
                    kotlinx.coroutines.delay(20_000)
                } else kotlinx.coroutines.delay(5_000)
            }
        }
        runCatching { SignService.scheduleBackgroundCheck(app) }
    }

    // ---------- Customer signatures ----------
    private fun friendly(e: Exception) = (e as? SignService.SignException)?.message
        ?: "Couldn't reach the signing service. Check your signal and try again."

    /** Creates (or replaces) the signing link for a quote. Draft quotes become Sent. */
    fun sendForSignature(q: Quote, onDone: (url: String?, error: String?) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        if (s.signKey.isBlank()) { onDone(null, "Add your signing key in Settings first."); return@launch }
        val current = dao.quote(q.id) ?: return@launch
        val lines = dao.linesFor(q.id)
        val customer = current.customerId?.let { dao.customer(it) }
        try {
            val link = SignService.create(s.signKey.trim(), SignService.payload(current, lines, customer, s), current.number,
                current.signToken.takeIf { current.signedAt == null })
            val now = System.currentTimeMillis()
            val latest = dao.quote(q.id) ?: return@launch
            // Sending again after a signature starts a fresh approval.
            if (latest.signaturePath.isNotBlank()) java.io.File(latest.signaturePath).delete()
            val resign = latest.signedAt != null
            dao.updateQuote(latest.copy(signToken = link.token, signUrl = link.url, sentAt = now, updatedAt = now, viewedAt = null,
                signedAt = null, signerName = "", signerIp = "", signaturePath = "",
                status = if (latest.status == Status.DRAFT || (resign && latest.status == Status.APPROVED)) Status.SENT else latest.status))
            SignService.scheduleBackgroundCheck(app)
            onDone(link.url, null)
        } catch (e: Exception) { onDone(null, friendly(e)) }
    }

    /** Turns off the link so it can't be signed anymore. */
    fun cancelSignature(q: Quote, onDone: (String?) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        try {
            if (q.signToken.isNotBlank() && s.signKey.isNotBlank()) SignService.cancel(s.signKey.trim(), q.signToken)
            dao.quote(q.id)?.let { dao.updateQuote(it.copy(signToken = "", signUrl = "", sentAt = null, viewedAt = null)) }
            onDone(null)
        } catch (e: Exception) { onDone(friendly(e)) }
    }

    fun checkSignaturesNow(onDone: (signed: Int, error: String?) -> Unit) = viewModelScope.launch {
        try {
            val signed = SignService.sync(app)
            SignService.notifySigned(app, signed)
            onDone(signed.size, null)
        } catch (e: Exception) { onDone(0, friendly(e)) }
    }

    /** Builds the customer PDF or your job copy and hands back the file. */
    fun quotePdf(q: Quote, jobCopy: Boolean, onDone: (java.io.File?) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        val lines = dao.linesFor(q.id)
        val customer = q.customerId?.let { dao.customer(it) }
        val mats = materials.value
        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { if (jobCopy) QuotePdf.jobCopy(app, q, lines, mats, customer, s) else QuotePdf.customerCopy(app, q, lines, customer, s) }.getOrNull()
        }
        onDone(f)
    }

    // ---------- Settings ----------
    fun saveSettings(s: Settings) = viewModelScope.launch { dao.saveSettings(s) }

    // ---------- Customers ----------
    fun saveCustomer(c: Customer, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        if (c.id == 0L) onSaved(dao.insertCustomer(c)) else { dao.updateCustomer(c); onSaved(c.id) }
    }
    fun deleteCustomer(c: Customer) = viewModelScope.launch { dao.deleteCustomer(c) }

    // ---------- Materials / stock ----------
    fun saveMaterial(m: Material, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        if (m.id == 0L) onSaved(dao.insertMaterial(m)) else { dao.updateMaterial(m); onSaved(m.id) }
    }
    fun deleteMaterial(m: Material) = viewModelScope.launch {
        if (m.photoPath.isNotBlank()) java.io.File(m.photoPath).delete()
        dao.deleteMaterial(m)
    }

    // ---------- Item photos ----------
    private fun itemDir() = java.io.File(app.filesDir, "items").apply { mkdirs() }

    /** A new file the camera writes an item photo into. */
    fun newItemPhotoFile(): java.io.File = java.io.File(itemDir(), "item_${System.currentTimeMillis()}.jpg")

    /** Shrinks a fresh camera photo (often 4+ MB) to about 1600 px so hundreds of items fit easily. */
    fun shrinkItemPhoto(f: java.io.File, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (!f.exists() || f.length() == 0L) return@runCatching false
                val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, b)
                var sample = 1
                while (b.outWidth / (sample * 2) >= 1600 || b.outHeight / (sample * 2) >= 1600) sample *= 2
                var bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching false
                val rot = when (androidx.exifinterface.media.ExifInterface(f.path).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (rot != 0f) bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot) }, true)
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                true
            }.getOrDefault(false)
        }
        if (!ok) f.delete()
        onDone(ok)
    }

    /** Removes a photo file that is no longer used. */
    fun discardItemPhoto(path: String) {
        if (path.isNotBlank()) viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { java.io.File(path).delete() }
    }

    /** Save edits and any received/used amounts in one step, so nothing overwrites anything else. */
    fun saveMaterialWithMoves(m: Material, moves: List<Pair<Double, String>>) = viewModelScope.launch {
        val current = dao.material(m.id) ?: return@launch
        val newOnHand = maxOf(0.0, current.onHand + moves.sumOf { it.first })
        dao.updateMaterial(m.copy(onHand = newOnHand))
        moves.forEach { (delta, reason) -> dao.insertMove(StockMove(materialId = m.id, delta = delta, reason = reason)) }
    }

    /** Add (receive) or remove (used) stock and log it. */
    fun adjustStock(materialId: Long, delta: Double, reason: String, quoteId: Long? = null) = viewModelScope.launch {
        val m = dao.material(materialId) ?: return@launch
        dao.updateMaterial(m.copy(onHand = maxOf(0.0, m.onHand + delta)))
        dao.insertMove(StockMove(materialId = materialId, delta = delta, reason = reason, quoteId = quoteId))
    }

    // ---------- Quotes ----------
    fun newQuote(onCreated: (Long) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        val number = "Q-" + s.nextQuoteNo.toString().padStart(4, '0')
        val id = dao.insertQuote(
            Quote(number = number, laborRate = s.laborRate, markupPct = s.markupPct, taxRatePct = s.repairTaxPct)
        )
        dao.saveSettings(s.copy(nextQuoteNo = s.nextQuoteNo + 1))
        onCreated(id)
    }

    fun saveQuote(q: Quote) = viewModelScope.launch { dao.updateQuote(q.copy(updatedAt = System.currentTimeMillis())) }

    fun deleteQuote(q: Quote) = viewModelScope.launch {
        dao.deleteLinesFor(q.id)
        dao.deleteQuote(q)
    }

    fun addLine(l: QuoteLine) = viewModelScope.launch { dao.insertLine(l); touch(l.quoteId) }
    fun saveLine(l: QuoteLine) = viewModelScope.launch { dao.updateLine(l); touch(l.quoteId) }
    fun deleteLine(l: QuoteLine) = viewModelScope.launch { dao.deleteLine(l); touch(l.quoteId) }

    private suspend fun touch(quoteId: Long) {
        dao.quote(quoteId)?.let { dao.updateQuote(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /** Job done: take the quote's materials out of stock (with any amounts you corrected), stop holding them. */
    fun completeJob(q: Quote, used: Map<Long, Double>) = viewModelScope.launch {
        for ((materialId, qty) in used) {
            if (qty <= 0.0) continue
            val m = dao.material(materialId) ?: continue
            dao.updateMaterial(m.copy(onHand = maxOf(0.0, m.onHand - qty)))
            dao.insertMove(StockMove(materialId = materialId, delta = -qty, reason = "Used on ${q.number}", quoteId = q.id))
        }
        dao.updateQuote(q.copy(status = Status.DONE, completedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    fun markPaid(q: Quote, amount: Double) = viewModelScope.launch {
        dao.updateQuote(q.copy(paidAmount = amount, paidAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    // ---------- Books ----------
    fun addExpense(e: Expense) = viewModelScope.launch { if (e.id == 0L) dao.insertExpense(e) else dao.updateExpense(e) }

    /** Adds receipts from an import file, skipping any already in Books (same day, vendor and amount). */
    fun importExpenses(items: List<Pair<Expense, List<ImportFile>>>, onDone: (added: Int, skipped: Int) -> Unit) = viewModelScope.launch {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val existing = dao.expensesNow().associateBy { Triple(day.format(java.util.Date(it.date)), it.vendor.lowercase(), Math.round(it.amount * 100)) }.toMutableMap()
        var added = 0
        for ((e, files) in items) {
            val k = Triple(day.format(java.util.Date(e.date)), e.vendor.lowercase(), Math.round(e.amount * 100))
            val match = existing[k]
            val id: Long
            if (match != null) {
                // Already in Books: add the item lines if missing, and receipt copies only if it has none yet.
                if (e.items.isNotBlank() && match.items.isBlank()) { dao.updateExpense(match.copy(items = e.items)); existing[k] = match.copy(items = e.items) }
                if (files.isEmpty() || dao.attachmentsFor(match.id).isNotEmpty()) continue
                id = match.id
            } else {
                id = dao.insertExpense(e); existing[k] = e.copy(id = id); added++
            }
            for (f in files) {
                val dest = java.io.File(receiptDir(id), "${System.currentTimeMillis()}_${safeName(f.name)}")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dest.writeBytes(f.bytes) }
                dao.insertAttachment(Attachment(expenseId = id, fileName = f.name, mime = f.mime, path = dest.absolutePath))
            }
        }
        onDone(added, items.size - added)
    }
    fun deleteExpense(e: Expense) = viewModelScope.launch {
        dao.attachmentsFor(e.id).forEach { a -> java.io.File(a.path).delete(); dao.deleteAttachment(a) }
        dao.deleteExpense(e)
    }

    // ---------- Receipt copies ----------
    private val app get() = getApplication<Application>()
    private fun receiptDir(expenseId: Long) = java.io.File(app.filesDir, "receipts/$expenseId").apply { mkdirs() }
    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "receipt" }

    /** A temp file in app storage the camera can write into. */
    fun newPhotoFile(): java.io.File = java.io.File(app.filesDir, "receipts/tmp").apply { mkdirs() }
        .let { java.io.File(it, "photo_${System.currentTimeMillis()}.jpg") }

    /** Moves a photo taken with the camera onto an expense. */
    fun attachPhoto(expenseId: Long, photo: java.io.File) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (!photo.exists() || photo.length() == 0L) return@launch
        val dest = java.io.File(receiptDir(expenseId), photo.name)
        photo.copyTo(dest, overwrite = true); photo.delete()
        dao.insertAttachment(Attachment(expenseId = expenseId, fileName = "Photo " + java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date()), mime = "image/jpeg", path = dest.absolutePath))
    }

    /** Copies a picked file (PDF, screenshot, photo) into app storage and attaches it. */
    fun attachUri(expenseId: Long, uri: android.net.Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val cr = app.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "receipt"
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val dest = java.io.File(receiptDir(expenseId), "${System.currentTimeMillis()}_${safeName(name)}")
        cr.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return@launch
        dao.insertAttachment(Attachment(expenseId = expenseId, fileName = name, mime = mime, path = dest.absolutePath))
    }

    fun deleteAttachment(a: Attachment) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        java.io.File(a.path).delete(); dao.deleteAttachment(a)
    }

    /** A receipt file carried inside an import file. */
    data class ImportFile(val name: String, val mime: String, val bytes: ByteArray)
}
