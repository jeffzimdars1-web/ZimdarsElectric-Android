package com.zimdarselectric.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fills in the official NY Form ST-124 (Certificate of Capital Improvement, 12/15) with the job, the customer's
 * online signature and your saved signature. The blank form ships with the app (assets/st124.pdf).
 */
object St124Pdf {
    private const val W = 612f
    private const val H = 792f

    data class Addr(val street: String, val city: String, val state: String, val zip: String)

    /** "110 State Street, Prospect, NY 13435" → parts. Anything it can't split goes in the street. */
    fun parseAddress(a: String): Addr {
        val parts = a.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return Addr(a.trim(), "", "", "")
        val last = parts.last()
        val m = Regex("""^([A-Za-z]{2})\s*(\d{5}(?:-\d{4})?)?$""").find(last)
        return if (m != null && parts.size >= 3) Addr(parts.dropLast(2).joinToString(", "), parts[parts.size - 2], m.groupValues[1].uppercase(), m.groupValues[2])
        else if (m != null) Addr(parts[0], "", m.groupValues[1].uppercase(), m.groupValues[2])
        else Addr(parts.dropLast(1).joinToString(", "), last, "", "")
    }

    /** What goes in "Describe capital improvement": the install work only. */
    fun description(q: Quote): String =
        (listOf(q.title) + q.notes.lines())
            .map { it.trim().trimStart('-', '•', '*', ' ').trim().trimEnd('.', ';', ',') }
            .filter { it.isNotBlank() && !it.startsWith("Customer supplied", true) }
            .joinToString("; ").let { if (it.isBlank()) it else "$it." }

    fun make(
        ctx: Context, q: Quote, customer: Customer?, s: Settings,
        signerName: String, signature: File?, role: String, contract: String, signedAt: Long,
    ): File {
        val blank = File(ctx.cacheDir, "st124_blank.pdf")
        if (!blank.exists()) ctx.assets.open("st124.pdf").use { i -> blank.outputStream().use { i.copyTo(it) } }
        val out = File(ctx.filesDir, "st124").apply { mkdirs() }.let { File(it, "${q.number}_ST-124.pdf") }
        val doc = PdfDocument()
        PdfRenderer(ParcelFileDescriptor.open(blank, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            for (i in 0 until minOf(r.pageCount, 2)) {
                val pg = r.openPage(i)
                val bmp = Bitmap.createBitmap((W * 2.5f).toInt(), (H * 2.5f).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); pg.close()
                val page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), i + 1).create())
                val c = page.canvas
                c.drawBitmap(bmp, null, RectF(0f, 0f, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
                if (i == 0) fill(c, q, customer, s, signerName, signature, role, contract, signedAt)
                doc.finishPage(page)
            }
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun fill(c: Canvas, q: Quote, customer: Customer?, s: Settings, signerName: String, signature: File?, role: String, contract: String, signedAt: Long) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 30, 110); textSize = 9.5f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        // (x1, y1) are the PDF field's lower-left corner; PDF y counts up from the bottom.
        fun text(t: String, x1: Float, y1: Float, x2: Float) {
            var v = t
            while (v.isNotEmpty() && ink.measureText(v) > x2 - x1 - 4) v = v.dropLast(1)
            c.drawText(v, x1 + 2, H - (y1 + 3), ink)
        }
        fun mark(x1: Float, y1: Float) {
            val p = Paint(ink).apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD }
            c.drawText("X", x1 + 1, H - (y1 + 1), p)
        }
        fun image(f: File?, x1: Float, y1: Float, x2: Float, y2: Float) {
            val b = f?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } ?: return
            val boxW = x2 - x1; val boxH = y2 - y1
            val scale = minOf(boxW / b.width, boxH / b.height)
            val w = b.width * scale; val h = b.height * scale
            c.drawBitmap(b, null, RectF(x1, H - y1 - h, x1 + w, H - y1), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val day = SimpleDateFormat("MM/dd/yyyy", Locale.US).format(Date(signedAt))

        // Contractor (left column on this form)
        val biz = parseAddress(s.address)
        text(s.businessName, 36f, 600f, 307f)
        text(biz.street, 36f, 576f, 307f)
        text(biz.city, 36f, 552f, 210f); text(biz.state, 210f, 552f, 239f); text(biz.zip, 238f, 552f, 306f)
        // Customer (right column)
        val site = q.site.ifBlank { customer?.address.orEmpty() }
        val cust = parseAddress(customer?.address?.ifBlank { site } ?: site)
        text(customer?.name ?: q.customerName, 306f, 600f, 577f)
        text(cust.street, 306f, 576f, 577f)
        text(cust.city, 306f, 552f, 478f); text(cust.state, 480f, 552f, 510f); text(cust.zip, 509f, 552f, 576f)
        if (s.coaNumber.isNotBlank()) text(s.coaNumber, 306f, 528f, 576f)
        // Describe the capital improvement (4 lines)
        val lines = wrap(description(q), ink, 536f).take(4)
        listOf(480f, 456f, 432f, 408f).forEachIndexed { i, y -> lines.getOrNull(i)?.let { text(it, 36f, y, 578f) } }
        text(q.title.ifBlank { "Electrical work" } + " (${q.number})", 37f, 360f, 576f)
        val w = parseAddress(site)
        text(w.street, 37f, 336f, 311f); text(w.city, 311f, 336f, 474f); text(w.state, 474f, 336f, 509f); text(w.zip, 509f, 336f, 576f)
        if (role == "tenant") mark(202f, 312f) else mark(154f, 312f)
        if (contract == "includes") mark(173f, 288f) else mark(229f, 288f)
        // Customer signature, title, date
        image(signature, 40f, 150f, 290f, 176f)
        text(if (role == "tenant") "Tenant" else "Owner", 295f, 156f, 492f)
        text(day, 493f, 156f, 574f)
        val small = Paint(ink).apply { textSize = 6.5f }
        c.drawText("Signed electronically by $signerName", 40f, H - 146f, small)
        // Contractor signature, title, date
        if (s.ownerSignaturePath.isNotBlank()) image(File(s.ownerSignaturePath), 40f, 54f, 290f, 80f)
        text("Owner", 295f, 60f, 492f)
        text(day, 493f, 60f, 574f)
    }

    private fun wrap(t: String, p: Paint, width: Float): List<String> {
        val out = mutableListOf<String>(); var line = ""
        t.split(' ').forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) <= width) line = next else { if (line.isNotEmpty()) out += line; line = word }
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}
