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
    val settings: StateFlow<Settings> = hot(dao.settings().map { it ?: Settings() }, Settings())

    /** Material id -> quantity on hold across all open quotes. */
    val holds: StateFlow<Map<Long, Double>> = hot(
        combine(dao.quotes(), dao.allLines()) { q, l -> holdsByMaterial(q, l) }, emptyMap()
    )

    init {
        viewModelScope.launch { if (dao.settingsNow() == null) dao.saveSettings(Settings()) }
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
    fun deleteMaterial(m: Material) = viewModelScope.launch { dao.deleteMaterial(m) }

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
                // Already in Books: only add receipt copies if it has none yet.
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
