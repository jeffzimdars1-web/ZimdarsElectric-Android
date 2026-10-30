package com.zimdarselectric.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.zimdarselectric.app.data.Trip
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
    val photos = hot(dao.photos(), emptyList())
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
                val now = System.currentTimeMillis()
                if (quotes.value.any { it.awaitingSignature || (it.st124Token.isNotBlank() && it.st124SignedAt == null) }) {
                    runCatching { SignService.notifySigned(app, SignService.sync(app)) }
                    kotlinx.coroutines.delay(20_000)
                } else if (quotes.value.any { it.kind == com.zimdarselectric.app.data.Kind.APPT && it.signToken.isNotBlank() && (it.startAt ?: 0) > now }) {
                    // Confirmed appointments coming up: watch for "different time" requests.
                    runCatching { SignService.sync(app) }
                    kotlinx.coroutines.delay(60_000)
                } else kotlinx.coroutines.delay(5_000)
            }
        }
        runCatching { SignService.scheduleBackgroundCheck(app) }
        runCatching { com.zimdarselectric.app.data.Backup.schedule(app) }
        runCatching { com.zimdarselectric.app.data.Billing.schedule(app) }
        // Keep job reminders matched to quote dates and reminder settings.
        viewModelScope.launch {
            combine(dao.quotes(), dao.settings()) { q, s -> q to (s ?: Settings()) }
                .collect { (q, s) -> runCatching { com.zimdarselectric.app.data.Reminders.syncAll(app, q, s) } }
        }
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
            val changeOf = current.parentId?.let { dao.quote(it)?.number }
            val link = SignService.create(s.signKey.trim(), SignService.payload(current, lines, customer, s, changeOf), current.number,
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
            val pics = dao.photosFor(q.id)
            runCatching { if (jobCopy) QuotePdf.jobCopy(app, q, lines, mats, customer, s, pics) else QuotePdf.customerCopy(app, q, lines, customer, s, pics.filter { it.onCustomerCopy }) }.getOrNull()
        }
        onDone(f)
    }

    /** Books list position, search and month, kept while you open a receipt and come back. */
    class BooksUi {
        val list = androidx.compose.foundation.lazy.LazyListState()
        var search by androidx.compose.runtime.mutableStateOf("")
        /** "2026-05" for one month, null for the whole tax year. */
        var month by androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val booksUi = BooksUi()

    /** Quotes tab: list or calendar, and which month the calendar shows. Kept while you open a quote. */
    class QuotesUi {
        var calendar by androidx.compose.runtime.mutableStateOf(false)
        var month by androidx.compose.runtime.mutableStateOf(java.time.YearMonth.now())
        var day by androidx.compose.runtime.mutableStateOf(java.time.LocalDate.now())
    }
    val quotesUi = QuotesUi()

    // ---------- Settings ----------
    fun saveSettings(s: Settings) = viewModelScope.launch { dao.saveSettings(s) }

    // ---------- Customers ----------
    fun saveCustomer(c: Customer, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        if (c.id == 0L) onSaved(dao.insertCustomer(c)) else { dao.updateCustomer(c); onSaved(c.id) }
    }
    // ---------- New phone contacts ----------
    private fun digits(p: String) = p.filter(Char::isDigit).takeLast(10)
    private val _newContacts = kotlinx.coroutines.flow.MutableStateFlow<List<com.zimdarselectric.app.data.PhoneContacts.Contact>>(emptyList())
    /** Phone contacts that aren't customers yet and that you haven't skipped. */
    val newContacts: StateFlow<List<com.zimdarselectric.app.data.PhoneContacts.Contact>> get() = _newContacts

    /** Looks for new phone contacts. Called whenever the app opens or comes back to the front. */
    fun refreshNewContacts() = viewModelScope.launch {
        if (!com.zimdarselectric.app.data.PhoneContacts.allowed(app)) { _newContacts.value = emptyList(); return@launch }
        val have = dao.customersNow()
        val phones = have.map { digits(it.phone) }.filter { it.length >= 7 }.toSet()
        val names = have.map { it.name.trim().lowercase() }.toSet()
        val skipped = (dao.settingsNow()?.skippedContacts ?: "").lines().filter { it.isNotBlank() }.toSet()
        _newContacts.value = com.zimdarselectric.app.data.PhoneContacts.all(app).filter { k ->
            val d = digits(k.phone)
            d !in phones && k.name.trim().lowercase() !in names && d !in skipped
        }
    }

    fun addContact(k: com.zimdarselectric.app.data.PhoneContacts.Contact) = viewModelScope.launch {
        dao.insertCustomer(com.zimdarselectric.app.data.PhoneContacts.toCustomer(app, k)); refreshNewContacts()
    }
    fun skipContact(k: com.zimdarselectric.app.data.PhoneContacts.Contact) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        dao.saveSettings(s.copy(skippedContacts = (s.skippedContacts.lines() + digits(k.phone)).filter { it.isNotBlank() }.distinct().joinToString("\n")))
        refreshNewContacts()
    }
    fun addAllNewContacts(onDone: (Int) -> Unit) = viewModelScope.launch {
        val list = _newContacts.value
        list.forEach { dao.insertCustomer(com.zimdarselectric.app.data.PhoneContacts.toCustomer(app, it)) }
        refreshNewContacts(); onDone(list.size)
    }

    /** Copies every phone contact into Customers, skipping anyone already saved (same number or same name). */
    fun importAllContacts(onDone: (added: Int, skipped: Int) -> Unit) = viewModelScope.launch {
        val contacts = com.zimdarselectric.app.data.PhoneContacts.all(app)
        val have = dao.customersNow()
        val phones = have.map { it.phone.filter(Char::isDigit).takeLast(10) }.filter { it.length >= 7 }.toMutableSet()
        val names = have.map { it.name.trim().lowercase() }.toMutableSet()
        var added = 0
        for (k in contacts) {
            val p = k.phone.filter(Char::isDigit).takeLast(10)
            if (p in phones || k.name.trim().lowercase() in names) continue
            dao.insertCustomer(com.zimdarselectric.app.data.PhoneContacts.toCustomer(app, k))
            phones += p; names += k.name.trim().lowercase(); added++
        }
        onDone(added, contacts.size - added)
        refreshNewContacts()
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

    // ---------- Jobs Claude prepared (online inbox) ----------
    private val _inboxAdded = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    /** Quotes just added from the inbox: id to a short description, for the Home banner. */
    val inboxAdded: kotlinx.coroutines.flow.StateFlow<List<Pair<Long, String>>> = _inboxAdded
    fun dismissInbox() { _inboxAdded.value = emptyList() }

    /** Picks up jobs Claude wrote up for you and adds them as quotes (or updates the quote number given). */
    fun pullInbox() = viewModelScope.launch {
        val s0 = dao.settingsNow() ?: return@launch
        val items = runCatching { com.zimdarselectric.app.data.Inbox.pending(s0.signKey) }.getOrDefault(emptyList())
        for (item in items) {
            if (item.kind != "job") continue
            val added = runCatching { importJob(item.data) }.getOrNull() ?: continue
            com.zimdarselectric.app.data.Inbox.done(s0.signKey, item.id)
            _inboxAdded.value = _inboxAdded.value + added
        }
    }

    private suspend fun importJob(d: org.json.JSONObject): Pair<Long, String> {
        fun digits(p: String) = p.filter(Char::isDigit).takeLast(10)
        val c = d.optJSONObject("customer") ?: org.json.JSONObject()
        val qo = d.optJSONObject("quote") ?: org.json.JSONObject()
        val name = c.optString("name").trim()
        val phone = c.optString("phone").trim()
        val site = qo.optString("site").trim()
        // Customer: match by phone, then name; otherwise add them.
        var cust = dao.customersNow().firstOrNull { (phone.isNotBlank() && digits(it.phone) == digits(phone)) } ?: dao.customersNow().firstOrNull { name.isNotBlank() && it.name.equals(name, true) }
        if (cust == null && name.isNotBlank()) {
            val nc = Customer(name = name, phone = phone, email = c.optString("email"), address = c.optString("address").ifBlank { site })
            cust = nc.copy(id = dao.insertCustomer(nc))
        }
        if (cust != null && site.isNotBlank() && cust.allProperties.none { it.address.equals(site, true) }) {
            cust = if (cust.address.isBlank()) cust.copy(address = site)
                   else cust.withProperties(cust.otherProperties + Customer.Property(c.optString("propertyLabel"), site))
            dao.updateCustomer(cust)
        }
        // Quote: update the one with this number, or make a new one.
        val number = qo.optString("number").trim()
        val existing = if (number.isNotBlank()) dao.quotesNow().firstOrNull { it.number.equals(number, true) } else null
        val s = dao.settingsNow() ?: Settings()
        val base = existing ?: Quote(number = "Q-" + s.nextQuoteNo.toString().padStart(4, '0'), laborRate = s.laborRate, markupPct = s.markupPct, taxRatePct = s.repairTaxPct)
        val q = com.zimdarselectric.app.data.Inbox.applyQuote(base, qo).copy(customerId = cust?.id ?: base.customerId, customerName = cust?.name ?: base.customerName)
        val id = if (existing != null) { dao.updateQuote(q); existing.id } else { val nid = dao.insertQuote(q); dao.saveSettings(s.copy(nextQuoteNo = s.nextQuoteNo + 1)); nid }
        // Materials (added to what's there; matched to your stock by name).
        val lines = d.optJSONArray("lines")
        if (lines != null && lines.length() > 0) {
            fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9/.]+"), " ").trim()
            val stock = dao.materialsNow()
            for (i in 0 until lines.length()) {
                val l = lines.getJSONObject(i)
                val m = stock.firstOrNull { norm(it.name) == norm(l.optString("name")) }
                dao.insertLine(QuoteLine(quoteId = id, materialId = m?.id, name = m?.name ?: l.optString("name"), unit = l.optString("unit", m?.unit ?: "ea"),
                    qty = l.optDouble("qty", 1.0), unitCost = if (l.has("unitCost")) l.getDouble("unitCost") else m?.cost ?: 0.0, repair = l.optBoolean("repair")))
            }
        }
        return id to "${q.number} · ${q.customerName.ifBlank { q.title }}"
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

    /** New appointment: a booked visit with no prices yet. Texts the customer reminders by default. */
    fun newAppointment(startAt: Long?, onCreated: (Long) -> Unit) = newQuote { id ->
        viewModelScope.launch {
            dao.quote(id)?.let {
                dao.updateQuote(it.copy(kind = com.zimdarselectric.app.data.Kind.APPT, textCustomer = true, title = "Service call",
                    jobType = com.zimdarselectric.app.data.JobType.REPAIR,
                    startAt = startAt, hasTime = false, scheduled = com.zimdarselectric.app.data.scheduleText(startAt, 0.0, false)))
            }
            onCreated(id)
        }
    }

    /** After the visit: turn the appointment into a quote, keeping customer, address, date and notes. */
    fun appointmentToQuote(q: Quote) = viewModelScope.launch {
        dao.quote(q.id)?.let {
            // The confirmation link belongs to the appointment; the quote gets its own signing link later.
            dao.updateQuote(it.copy(kind = com.zimdarselectric.app.data.Kind.QUOTE, title = if (it.title == "Service call") "" else it.title,
                signToken = "", signUrl = "", sentAt = null, viewedAt = null, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Sends the appointment confirmation: makes (or replaces) the confirmation page link and texts it to the customer
     * from this phone. [onDone] gets (link, error, texted).
     */
    fun sendAppointment(q: Quote, onDone: (url: String?, error: String?, texted: Boolean) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        if (s.signKey.isBlank()) { onDone(null, "Add your signing key in Settings first.", false); return@launch }
        val cur = dao.quote(q.id) ?: return@launch
        val c = cur.customerId?.let { dao.customer(it) }
        try {
            val link = SignService.create(s.signKey.trim(), SignService.apptPayload(cur, c, s), cur.number,
                cur.signToken.takeIf { it.isNotBlank() }, kind = "appt")
            val now = System.currentTimeMillis()
            val latest = dao.quote(q.id) ?: return@launch
            dao.updateQuote(latest.copy(signToken = link.token, signUrl = link.url, sentAt = now, updatedAt = now, viewedAt = null, confirmedAt = null))
            SignService.scheduleBackgroundCheck(app)
            val texted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.zimdarselectric.app.data.CustomerTexts.sendText(app, latest, c, com.zimdarselectric.app.data.CustomerTexts.confirmation(latest, s, link.url))
            }
            onDone(link.url, null, texted)
        } catch (e: Exception) { onDone(null, friendly(e), false) }
    }

    // ---------- GPS: on my way, job clock, mileage ----------
    val trips = hot(dao.trips(), emptyList())

    /** Texts the customer your drive time and starts tracking the drive. [onResult] gets a short status line. */
    fun onMyWay(q: Quote, onResult: (String) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        var job = dao.quote(q.id) ?: return@launch
        if ((job.siteLat == null || job.siteLon == null) && job.site.isNotBlank()) {
            com.zimdarselectric.app.data.Geo.locate(job.site)?.let { p -> job = job.copy(siteLat = p.lat, siteLon = p.lon); dao.updateQuote(job) }
        }
        val here = com.zimdarselectric.app.data.Geo.here(app)
        val tLat = job.siteLat; val tLon = job.siteLon
        val minutes = if (here != null && tLat != null && tLon != null)
            com.zimdarselectric.app.data.Geo.route(here.latitude, here.longitude, tLat, tLon).minutes else null
        val c = job.customerId?.let { dao.customer(it) }
        val texted = if (minutes != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.zimdarselectric.app.data.CustomerTexts.sendText(app, job, c, com.zimdarselectric.app.data.CustomerTexts.onMyWay(job, c, s, minutes))
        } else false
        com.zimdarselectric.app.data.DriveService.startToJob(app, job.id)
        onResult(when {
            minutes == null && tLat == null -> "Couldn't find that address on the map. Tracking miles; tap Stop when you get there."
            minutes == null -> "Couldn't get your location. Tracking miles; tap Stop when you get there."
            texted -> "Texted ${job.customerName.substringBefore(' ')}: about $minutes min. Tracking your drive."
            else -> "About $minutes min away. Tap the notification to send the text. Tracking your drive."
        })
    }

    /** Saves where a job is without counting it as an edit to the quote. */
    fun setSiteCoords(q: Quote, lat: Double, lon: Double) = viewModelScope.launch { dao.quote(q.id)?.let { dao.updateQuote(it.copy(siteLat = lat, siteLon = lon)) } }

    fun markArrived(q: Quote) = viewModelScope.launch { dao.quote(q.id)?.let { dao.updateQuote(it.copy(arrivedAt = System.currentTimeMillis(), leftAt = null)) } }
    fun leaveJob(q: Quote) = viewModelScope.launch { dao.quote(q.id)?.let { dao.updateQuote(it.copy(leftAt = System.currentTimeMillis())) } }
    fun saveTrip(t: Trip) = viewModelScope.launch { if (t.id == 0L) dao.insertTrip(t) else dao.updateTrip(t) }
    fun deleteTrip(t: Trip) = viewModelScope.launch { dao.deleteTrip(t) }

    /** New quote already scheduled on [startAt] (from the calendar). */
    fun newQuoteAt(startAt: Long, onCreated: (Long) -> Unit) = newQuote { id ->
        viewModelScope.launch {
            dao.quote(id)?.let { dao.updateQuote(it.copy(startAt = startAt, hasTime = false, scheduled = com.zimdarselectric.app.data.scheduleText(startAt, 0.0, false))) }
            onCreated(id)
        }
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
        val key = dao.settingsNow()?.signKey.orEmpty()
        if (q.invoiceToken.isNotBlank() && key.isNotBlank()) runCatching { SignService.markInvoicePaid(key, q.invoiceToken) }
    }

    /** Accept the customer's requested time: update the job, their page, and text them the new time. */
    fun acceptNewTime(q: Quote, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        val current = dao.quote(q.id) ?: return@launch
        val start = current.rescheduleWhen ?: return@launch
        val updated = current.copy(startAt = start, hasTime = true, scheduled = current.rescheduleText, rescheduleText = "", updatedAt = System.currentTimeMillis())
        dao.updateQuote(updated)
        if (current.signToken.isNotBlank() && s.signKey.isNotBlank()) runCatching { SignService.retime(s.signKey, current.signToken, start, true) }
        val customer = current.customerId?.let { dao.customer(it) }
        val ok = com.zimdarselectric.app.data.CustomerTexts.canSend(app) &&
            com.zimdarselectric.app.data.CustomerTexts.sendText(app, updated, customer, com.zimdarselectric.app.data.CustomerTexts.timeChanged(updated, s))
        onDone(ok)
    }

    // ---------- ST-124 ----------
    /** Texts the customer a link to sign Form ST-124 by itself (for a job already signed or done). */
    fun sendSt124(q: Quote, onDone: (url: String?, error: String?, texted: Boolean) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        if (s.signKey.isBlank()) { onDone(null, "Add your signing key in Settings first.", false); return@launch }
        val current = dao.quote(q.id) ?: return@launch
        val customer = current.customerId?.let { dao.customer(it) }
        try {
            val link = SignService.create(s.signKey.trim(), SignService.st124Payload(current, customer, s), current.number,
                current.st124Token.takeIf { current.st124SignedAt == null }, kind = "st124")
            val now = System.currentTimeMillis()
            dao.quote(q.id)?.let { dao.updateQuote(it.copy(st124Token = link.token, st124Url = link.url, st124SentAt = now)) }
            val text = "${s.businessName}: Please sign New York State Form ST-124 for the work at ${current.site.ifBlank { "your property" }}. It confirms the new installation is a capital improvement, so no sales tax is charged on it. Sign here: ${link.url}"
            val texted = com.zimdarselectric.app.data.CustomerTexts.canSend(app) && com.zimdarselectric.app.data.CustomerTexts.sendText(app, current, customer, text)
            SignService.scheduleBackgroundCheck(app)
            onDone(link.url, null, texted)
        } catch (e: Exception) { onDone(null, friendly(e), false) }
    }

    /** Saves your own signature (drawn once) for the contractor line on ST-124. */
    fun saveOwnerSignature(bmp: android.graphics.Bitmap) = viewModelScope.launch {
        val f = java.io.File(java.io.File(app.filesDir, "signatures").apply { mkdirs() }, "owner_signature.png")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        val s = dao.settingsNow() ?: Settings()
        dao.saveSettings(s.copy(ownerSignaturePath = f.absolutePath))
    }

    // ---------- Change orders ----------
    fun newChangeOrder(parent: Quote, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val n = dao.quotesNow().count { it.parentId == parent.id } + 1
        val id = dao.insertQuote(Quote(
            number = "${parent.number}-CO$n", title = "Change order $n", parentId = parent.id,
            customerId = parent.customerId, customerName = parent.customerName, site = parent.site,
            siteLat = parent.siteLat, siteLon = parent.siteLon, jobType = parent.jobType,
            laborRate = parent.laborRate, markupPct = parent.markupPct, taxRatePct = parent.taxRatePct,
            startAt = parent.startAt, hasTime = parent.hasTime, scheduled = parent.scheduled, askReview = false,
        ))
        onCreated(id)
    }

    // ---------- Invoices ----------
    fun sendInvoice(q: Quote, onDone: (url: String?, error: String?, texted: Boolean) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        if (s.signKey.isBlank()) { onDone(null, "Add your signing key in Settings first.", false); return@launch }
        val current = dao.quote(q.id) ?: return@launch
        val customer = current.customerId?.let { dao.customer(it) }
        val all = dao.quotesNow(); val lines = dao.allLinesNow()
        val children = all.filter { it.parentId == current.id }
        try {
            val payload = com.zimdarselectric.app.data.Billing.payload(current, children, lines, customer, s)
            val link = SignService.create(s.signKey.trim(), payload, current.number, current.invoiceToken.takeIf { it.isNotBlank() }, kind = "invoice")
            val due = payload.getJSONObject("invoice").getDouble("due")
            dao.quote(q.id)?.let { dao.updateQuote(it.copy(invoiceToken = link.token, invoiceUrl = link.url, invoiceSentAt = System.currentTimeMillis(), invoiceRemindedAt = null)) }
            val texted = com.zimdarselectric.app.data.CustomerTexts.canSend(app) &&
                com.zimdarselectric.app.data.CustomerTexts.sendText(app, current, customer, com.zimdarselectric.app.data.Billing.invoiceText(current, s, due, link.url))
            onDone(link.url, null, texted)
        } catch (e: Exception) { onDone(null, friendly(e), false) }
    }

    fun sendInvoiceReminder(q: Quote, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val s = dao.settingsNow() ?: Settings()
        val current = dao.quote(q.id) ?: return@launch
        val customer = current.customerId?.let { dao.customer(it) }
        val due = com.zimdarselectric.app.data.Billing.invoiceTotal(current, dao.quotesNow().filter { it.parentId == current.id }, dao.allLinesNow(), s) - current.paidAmount
        val ok = com.zimdarselectric.app.data.CustomerTexts.sendText(app, current, customer, com.zimdarselectric.app.data.Billing.reminderText(current, s, due, current.invoiceUrl))
        dao.updateQuote(current.copy(invoiceRemindedAt = System.currentTimeMillis()))
        onDone(ok)
    }

    // ---------- Job photos ----------
    private fun photoDir() = java.io.File(app.filesDir, "photos").apply { mkdirs() }
    fun newJobPhotoFile(): java.io.File = java.io.File(photoDir(), "job_${System.currentTimeMillis()}.jpg")
    fun addJobPhoto(quoteId: Long, f: java.io.File) = shrinkItemPhoto(f) { ok ->
        if (ok) viewModelScope.launch { dao.insertPhoto(com.zimdarselectric.app.data.JobPhoto(quoteId = quoteId, path = f.absolutePath)) }
    }
    /** Copies a picture picked from the gallery into the app, then adds it. */
    fun addJobPhotoFromUri(quoteId: Long, uri: android.net.Uri) = viewModelScope.launch {
        val f = newJobPhotoFile()
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }; true }.getOrDefault(false)
        }
        if (ok) addJobPhoto(quoteId, f)
    }
    fun savePhoto(p: com.zimdarselectric.app.data.JobPhoto) = viewModelScope.launch { dao.updatePhoto(p) }
    fun deletePhoto(p: com.zimdarselectric.app.data.JobPhoto) = viewModelScope.launch {
        dao.deletePhoto(p); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(p.path).delete() }
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
                autoStock(id)
            }
            for (f in files) {
                val dest = java.io.File(receiptDir(id), "${System.currentTimeMillis()}_${safeName(f.name)}")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dest.writeBytes(f.bytes) }
                dao.insertAttachment(Attachment(expenseId = id, fileName = f.name, mime = f.mime, path = dest.absolutePath))
            }
        }
        onDone(added, items.size - added)
    }
    // ---------- Receipts -> stock ----------
    enum class ReceiptAction { ADD, COUNTED, SKIP }

    private suspend fun markLine(r: com.zimdarselectric.app.data.ReceiptItem, value: String) {
        val e = dao.expense(r.expenseId) ?: return
        dao.updateExpense(e.copy(items = com.zimdarselectric.app.data.ReceiptItem.mark(e.items, r.index, value)))
    }

    private suspend fun addLineToStock(r: com.zimdarselectric.app.data.ReceiptItem, m0: Material) {
        val m = dao.material(m0.id) ?: return
        val pieces = r.piecesFor(m)
        val unitCost = if (pieces > 0) r.paidEach * r.qty / pieces else 0.0
        dao.updateMaterial(m.copy(onHand = m.onHand + pieces, cost = if (m.cost == 0.0) unitCost else m.cost).withAliases(r.keys))
        val day = java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(r.date))
        dao.insertMove(StockMove(materialId = m.id, delta = pieces, reason = "From receipt · ${r.store} $day"))
        markLine(r, m.id.toString())
    }

    /** Lines on a newly imported receipt that you've matched before go straight into stock. */
    private suspend fun autoStock(expenseId: Long) {
        val e = dao.expense(expenseId) ?: return
        val mats = dao.materialsNow()
        com.zimdarselectric.app.data.ReceiptItem.from(e).filter { !it.decided }.forEach { r ->
            mats.firstOrNull { it.matchesReceipt(r.keys) }?.let { addLineToStock(r, it) }
        }
    }

    /** Your decision for one receipt line: add it to a stock item, say it's already counted, or not stock at all. */
    fun receiptToStock(r: com.zimdarselectric.app.data.ReceiptItem, m: Material?, action: ReceiptAction) = viewModelScope.launch {
        when (action) {
            ReceiptAction.ADD -> if (m != null) addLineToStock(r, m)
            ReceiptAction.COUNTED -> {
                if (m != null) dao.material(m.id)?.let { cur -> dao.updateMaterial((if (cur.cost == 0.0) cur.copy(cost = r.paidEach * r.qty / r.piecesFor(cur)) else cur).withAliases(r.keys)) }
                markLine(r, "counted")
            }
            ReceiptAction.SKIP -> markLine(r, "skip")
        }
    }

    /** Makes a new stock item from a receipt line (name from the catalog when it can tell) and adds the line to it. */
    fun newStockFromReceipt(r: com.zimdarselectric.app.data.ReceiptItem, onDone: (Material) -> Unit = {}) = viewModelScope.launch {
        val guess = com.zimdarselectric.app.data.LabelReader.read(r.name)
        val top = guess.matches.firstOrNull()
        val feet = r.feet
        val pack = r.packCount?.takeIf { it > 1 }?.toDouble() ?: 0.0
        val m = Material(
            name = top?.name ?: r.name.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
            spec = listOfNotNull(guess.brand, r.model.takeIf { it.isNotBlank() }).joinToString(" "),
            unit = if (feet != null) "ft" else top?.unit ?: "ea",
            packSize = pack,
        )
        val id = dao.insertMaterial(m)
        addLineToStock(r, m.copy(id = id))
        dao.material(id)?.let(onDone)
    }

    /**
     * Stock changes from an update file: [{name, packSize, boxPrice | cost, fromReceipt:{date, vendor, amount, line}, action:"add"|"counted"}].
     * Sets box size and price, then adds the receipt line to stock (once; a line already added is left alone).
     */
    fun importStockUpdates(list: org.json.JSONArray, onDone: (updated: Int, notFound: List<String>) -> Unit) = viewModelScope.launch {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        var updated = 0
        val notFound = mutableListOf<String>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val m0 = dao.materialsNow().firstOrNull { it.name.equals(name, true) }
            if (m0 == null) { notFound += name; continue }
            val pack = o.optDouble("packSize", m0.packSize).takeIf { !it.isNaN() } ?: m0.packSize
            val cost = when {
                o.has("boxPrice") && pack > 1.0 -> o.optDouble("boxPrice") / pack
                o.has("cost") -> o.optDouble("cost")
                else -> m0.cost
            }
            var m = m0.copy(packSize = if (pack > 1.0) pack else 0.0, cost = cost)
            dao.updateMaterial(m)
            o.optJSONObject("fromReceipt")?.let { fr ->
                val e = dao.expensesNow().firstOrNull {
                    day.format(java.util.Date(it.date)) == fr.optString("date") && it.vendor.equals(fr.optString("vendor"), true) &&
                        Math.round(it.amount * 100) == Math.round(fr.optDouble("amount") * 100)
                }
                val r = e?.let { com.zimdarselectric.app.data.ReceiptItem.from(it).getOrNull(fr.optInt("line", -1)) }
                if (r != null && !r.decided) {
                    if (o.optString("action", "add") == "counted") {
                        dao.updateMaterial(m.withAliases(r.keys)); markLine(r, "counted")
                    } else addLineToStock(r, m)
                }
            }
            updated++
        }
        onDone(updated, notFound)
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
