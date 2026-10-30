package com.zimdarselectric.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Letter-size PDFs of a quote. Long quotes flow onto more pages; every page repeats the
 * business heading and quote number, and a table that continues repeats its column titles.
 */
object QuotePdf {
    private const val W = 612f
    private const val H = 792f
    private const val M = 42f
    private val day = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    private val stamp = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.US)

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = when {
            mono -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            bold -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }
    private val body = paint(10.5f)
    private val small = paint(8.5f, color = 0xFF555B60.toInt())
    private val label = paint(7.5f, bold = true, color = 0xFF555B60.toInt(), mono = true).apply { letterSpacing = 0.12f }
    private val h2 = paint(11.5f, bold = true).apply { letterSpacing = 0.06f }
    private val strong = paint(10.5f, bold = true)
    private val line = Paint().apply { color = 0xFFC9CED2.toInt(); strokeWidth = 0.7f }
    private val ink = Paint().apply { color = Color.BLACK; strokeWidth = 1.4f; style = Paint.Style.STROKE }

    /** Lays out pages. Runs twice: once to count pages, once to draw with "Page n of N". */
    private class Pager(val s: Settings, val number: String, val copyName: String, val total: Int) {
        val doc = PdfDocument()
        lateinit var c: Canvas
        private var page: PdfDocument.Page? = null
        var pageNo = 0
        var y = 0f
        val bottom = H - M - 18f
        /** Redrawn at the top of the next page when a table runs over. */
        var continuing: (() -> Unit)? = null

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create())
            c = page!!.canvas
            // Heading on every page
            var top = M
            c.drawText(s.businessName.uppercase(), M, top + 16f, paint(17f, bold = true))
            val contact = listOf(s.phone, s.email).filter { it.isNotBlank() }.joinToString("  ·  ")
            c.drawText(contact, M, top + 30f, small)
            c.drawText(s.address, M, top + 41f, small)
            val lic = listOf(if (s.license.isNotBlank()) "License ${s.license}" else "", if (s.insurer.isNotBlank()) "Insured: ${s.insurer}" else "").filter { it.isNotBlank() }.joinToString("  ·  ")
            if (lic.isNotBlank()) c.drawText(lic, M, top + 52f, small)
            val right = paint(15f, bold = true).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("$copyName $number", W - M, top + 16f, right)
            c.drawText(if (pageNo > 1) "Page $pageNo of $total (continued)" else "Page 1 of $total", W - M, top + 30f, small.right())
            top += if (lic.isNotBlank()) 60f else 50f
            c.drawRect(M, top, W - M, top + 2.5f, Paint().apply { color = Color.BLACK })
            y = top + 18f
            // Footer
            c.drawText("$number  ·  Page $pageNo of $total", W / 2, H - M + 6f, small.center())
            continuing?.invoke()
        }

        fun need(h: Float) { if (y + h > bottom) newPage() }

        fun layout(text: String, p: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(10)).setAlignment(align).setLineSpacing(1.5f, 1f).build()

        fun draw(l: StaticLayout, x: Float, atY: Float = y) { c.save(); c.translate(x, atY); l.draw(c); c.restore() }

        /** Wrapped paragraph that can break across pages line by line. */
        fun para(text: String, p: TextPaint = body, x: Float = M, width: Float = W - 2 * M, after: Float = 6f) {
            if (text.isBlank()) return
            text.split('\n').forEach { para ->
                val l = layout(para.ifBlank { " " }, p, width)
                for (i in 0 until l.lineCount) {
                    val lh = (l.getLineBottom(i) - l.getLineTop(i)).toFloat()
                    need(lh)
                    c.drawText(para, l.getLineStart(i), l.getLineEnd(i).let { e -> if (e > l.getLineStart(i) && para[e - 1] == ' ') e - 1 else e }, x, y + (l.getLineBaseline(i) - l.getLineTop(i)), p)
                    y += lh
                }
            }
            y += after
        }

        /** Section heading; [keep] is how much room the start of the section needs, so a heading never sits alone at a page bottom. */
        fun section(title: String, keep: Float = 40f) {
            need(keep + 31f)
            y += 8f
            c.drawText(title.uppercase(), M, y + 10f, h2)
            y += 15f
            c.drawLine(M, y, W - M, y, line)
            y += 8f
        }

        /** Job photos, two across, with captions. */
        fun photos(list: List<JobPhoto>) {
            if (list.isEmpty()) return
            section("Photos")
            val gap = 12f; val colW = (W - 2 * M - gap) / 2
            list.chunked(2).forEach { row ->
                val bmps = row.map { p ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(p.path, o)
                    var sample = 1; while (o.outWidth / (sample * 2) >= 700) sample *= 2
                    BitmapFactory.decodeFile(p.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                val hs = bmps.map { b -> if (b == null) 0f else minOf(colW * b.height / b.width, 230f) }
                val rowH = (hs.maxOrNull() ?: 0f) + 26f
                need(rowH)
                row.forEachIndexed { i, p ->
                    val b = bmps[i] ?: return@forEachIndexed
                    val h = hs[i]; val w = h * b.width / b.height
                    val x = M + i * (colW + gap)
                    c.drawBitmap(b, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    val cap = listOf(p.caption, java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(Date(p.takenAt))).filter { it.isNotBlank() }.joinToString(" · ")
                    c.drawText(cap.take(60), x, y + h + 11f, small)
                    b.recycle()
                }
                y += rowH
            }
        }

        fun finish(): PdfDocument { page?.let { doc.finishPage(it) }; return doc }
    }

    private fun TextPaint.right() = TextPaint(this).apply { textAlign = Paint.Align.RIGHT }
    private fun TextPaint.center() = TextPaint(this).apply { textAlign = Paint.Align.CENTER }

    private fun build(total: Int, s: Settings, number: String, copyName: String, content: Pager.() -> Unit): Pager =
        Pager(s, number, copyName, total).apply { newPage(); content() }

    private fun render(ctx: Context, s: Settings, number: String, copyName: String, fileName: String, content: Pager.() -> Unit): File {
        val dry = build(99, s, number, copyName, content)
        val pages = dry.pageNo
        dry.finish().close()
        val doc = build(pages, s, number, copyName, content).finish()
        val dir = File(ctx.cacheDir, "pdfs").apply { mkdirs() }
        val f = File(dir, fileName)
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** Two side-by-side facts: small label over the value. */
    private fun Pager.facts(pairs: List<Pair<String, String>>) {
        val colW = (W - 2 * M - 20f) / 2
        pairs.chunked(2).forEach { row ->
            val layouts = row.map { (_, v) -> layout(v.ifBlank { "—" }, body, colW) }
            val h = 12f + (layouts.maxOfOrNull { it.height } ?: 0) + 8f
            need(h)
            row.forEachIndexed { i, (k, _) ->
                val x = M + i * (colW + 20f)
                c.drawText(k.uppercase(), x, y + 8f, label)
                draw(layouts[i], x, y + 11f)
            }
            y += h
        }
    }

    // ---------------- Customer copy ----------------

    fun customerCopy(ctx: Context, q: Quote, lines: List<QuoteLine>, customer: Customer?, s: Settings, photos: List<JobPhoto> = emptyList()): File {
        val t = computeTotals(q, lines, s)
        val factor = 1 + q.markupPct / 100.0
        val signed = q.signedAt != null
        return render(ctx, s, q.number, "QUOTE", "${q.number}${if (signed) "-signed" else ""}.pdf") {
            // Dates
            c.drawText("Date: ${day.format(Date(q.createdAt))}", M, y + 9f, body)
            c.drawText("Good until: ${day.format(Date(q.createdAt + s.quoteValidDays * 86_400_000L))}", W - M, y + 9f, body.right())
            y += 20f

            section("Customer and job")
            facts(listOf(
                "Customer" to listOfNotNull(customer?.name ?: q.customerName, customer?.phone?.takeIf { it.isNotBlank() }, customer?.email?.takeIf { it.isNotBlank() }).joinToString("\n"),
                "Job site" to q.site.ifBlank { customer?.address ?: "" },
                "Type of work" to when (q.jobType) { JobType.REPAIR -> "Repair / maintenance"; JobType.BOTH -> "New installation (capital improvement) and repair, listed separately"; else -> "New installation (capital improvement)" },
                "Planned dates" to q.scheduled.ifBlank { "To be scheduled together" },
            ))
            if (q.title.isNotBlank() || q.notes.isNotBlank() || q.repairNotes.isNotBlank()) {
                section("Work to be done")
                para(q.title, strong, after = 4f)
                if (q.jobType == JobType.BOTH) {
                    if (q.notes.isNotBlank()) { para("New installation", strong, after = 2f); para(q.notes) }
                    if (q.repairNotes.isNotBlank()) { para("Repair", strong, after = 2f); para(q.repairNotes) }
                } else para(q.notes)
            }

            val groups = if (q.jobType == JobType.BOTH)
                listOf("Materials · new installation" to lines.filter { !it.repair }, "Materials · repair" to lines.filter { it.repair })
            else listOf("Materials" to lines)
            for ((title, group) in groups) if (group.isNotEmpty()) {
                section(title)
                val xQty = W - M - 190f; val xEach = W - M - 95f; val xAmt = W - M
                val nameW = xQty - M - 70f
                val header: () -> Unit = {
                    c.drawText("ITEM", M, y + 8f, label)
                    c.drawText("QTY", xQty, y + 8f, label.right()); c.drawText("EACH", xEach, y + 8f, label.right()); c.drawText("AMOUNT", xAmt, y + 8f, label.right())
                    y += 13f; c.drawLine(M, y, W - M, y, line); y += 5f
                }
                header()
                continuing = header
                group.forEach { l ->
                    val nl = layout(l.name, body, nameW)
                    val h = nl.height + 9f
                    need(h)
                    draw(nl, M, y)
                    val base = y + 10f
                    c.drawText("${qtyText(l.qty)} ${l.unit}", xQty, base, body.right())
                    c.drawText(money(l.unitCost * factor), xEach, base, body.right())
                    c.drawText(money(l.qty * l.unitCost * factor), xAmt, base, body.right())
                    y += h; c.drawLine(M, y - 4f, W - M, y - 4f, line)
                }
                continuing = null
            }

            val rows = buildList {
                if (q.jobType == JobType.BOTH) {
                    val instMat = lines.filter { !it.repair }.sumOf { it.qty * it.unitCost } * factor
                    val repMat = lines.filter { it.repair }.sumOf { it.qty * it.unitCost } * factor
                    add("Install materials" to money(instMat))
                    add("Install labor (${qtyText(q.laborHours)} hr)" to money(q.laborHours * q.laborRate))
                    add("New installation subtotal" to money(t.installPart))
                    add("Repair materials" to money(repMat))
                    add("Repair labor (${qtyText(q.repairHours)} hr)" to money(q.repairHours * q.laborRate))
                    add("Repair subtotal" to money(t.repairPart))
                    add("Sales tax (${qtyText(q.taxRatePct)}%) on repair" to money(t.salesTax))
                } else {
                    add("Materials" to money(t.materialsPrice))
                    add((if (q.laborHours > 0) "Labor (${qtyText(q.laborHours)} hr)" else "Labor") to money(t.labor))
                    if (q.jobType == JobType.REPAIR) add("Sales tax (${qtyText(q.taxRatePct)}%)" to money(t.salesTax))
                }
            }
            section("Price", rows.size * 15f + 40f)
            val lx = W - M - 220f
            rows.forEach { (k, v) -> c.drawText(k, lx, y + 9f, body); c.drawText(v, W - M, y + 9f, body.right()); y += 15f }
            c.drawRect(lx, y + 2f, W - M, y + 3.5f, Paint().apply { color = Color.BLACK })
            y += 22f
            c.drawText("TOTAL", lx, y, paint(15f, bold = true)); c.drawText(money(t.total), W - M, y, paint(17f, bold = true).right())
            y += 14f
            if (q.jobType == JobType.INSTALL) {
                y += 4f
                para("No sales tax: this is a capital improvement to your property. You'll sign New York State form ST-124 (Certificate of Capital Improvement) to confirm this.", small)
            } else if (q.jobType == JobType.BOTH) {
                y += 4f
                para("The new installation is a capital improvement to your property, so it has no sales tax; you'll sign New York State form ST-124 for that part. Sales tax is charged on the repair part only.", small)
            }

            photos(photos)

            section("Terms")
            if (s.chargeNote.isNotBlank()) para("How I charge: " + s.chargeNote)
            if (s.paymentTerms.isNotBlank()) para("Payment: " + s.paymentTerms)
            if (s.warranty.isNotBlank()) para("Warranty: " + s.warranty)
            SignService.contractNotices(s).forEach { para(it, small, after = 4f) }

            // Signature
            section(if (signed) "Signed" else "Approval", 110f)
            if (signed) {
                val sig = q.signaturePath.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                val sh = if (sig != null) minOf(70f, 220f * sig.height / sig.width) else 0f
                need(sh + 70f)
                if (sig != null) {
                    val sw = sh * sig.width / sig.height
                    c.drawBitmap(sig, null, RectF(M, y, M + sw, y + sh), Paint(Paint.FILTER_BITMAP_FLAG))
                    y += sh + 2f
                }
                c.drawLine(M, y, M + 260f, y, ink); y += 13f
                c.drawText(q.signerName, M, y, strong); y += 13f
                c.drawText("Signed electronically ${stamp.format(Date(q.signedAt!!))}" + (if (q.signerIp.isNotBlank()) "  ·  IP ${q.signerIp}" else ""), M, y, small); y += 12f
                c.drawText("The customer checked: \"I have read this quote and agree to the work, price and terms above, and agree to sign electronically.\"", M, y, small)
                y += 12f
            } else {
                need(70f)
                para("To approve, sign and date below.", small)
                y += 26f
                c.drawLine(M, y, M + 300f, y, ink); c.drawLine(M + 330f, y, W - M, y, ink)
                y += 11f
                c.drawText("CUSTOMER SIGNATURE", M, y, label); c.drawText("DATE", M + 330f, y, label)
                y += 26f
                c.drawLine(M, y, M + 300f, y, ink); y += 11f
                c.drawText("PRINTED NAME", M, y, label)
            }
        }
    }

    // ---------------- Job copy (yours) ----------------

    fun jobCopy(ctx: Context, q: Quote, lines: List<QuoteLine>, materials: List<Material>, customer: Customer?, s: Settings, photos: List<JobPhoto> = emptyList()): File {
        val t = computeTotals(q, lines, s)
        return render(ctx, s, q.number, "JOB COPY", "${q.number}-job-copy.pdf") {
            c.drawText("Your copy for the job. Not for the customer.", M, y + 9f, strong)
            c.drawText(q.status + (if (q.signedAt != null) " · signed" else ""), W - M, y + 9f, label.right())
            y += 20f
            section("Customer and job")
            facts(listOf(
                "Customer" to (customer?.name ?: q.customerName),
                "Phone" to (customer?.phone ?: ""),
                "Job site" to q.site.ifBlank { customer?.address ?: "" },
                "Planned" to q.scheduled,
                "Type" to when (q.jobType) { JobType.REPAIR -> "Repair (sales tax charged)"; JobType.BOTH -> "Install + repair (ST-124 for install, tax on repair)"; else -> "New install (get ST-124 signed)" },
                "Labor planned" to if (q.jobType == JobType.BOTH) "${qtyText(q.laborHours)} hr install + ${qtyText(q.repairHours)} hr repair" else "${qtyText(q.laborHours)} hr",
            ))
            if (q.title.isNotBlank() || q.notes.isNotBlank() || q.repairNotes.isNotBlank()) {
                section("Work")
                para(q.title, strong, after = 4f)
                para(q.notes)
                if (q.jobType == JobType.BOTH && q.repairNotes.isNotBlank()) { para("Repair", strong, after = 2f); para(q.repairNotes) }
            }

            section("Pull list")
            val xQty = W - M - 150f; val xWhere = W - M
            val header: () -> Unit = {
                c.drawText("✓   ITEM", M, y + 8f, label)
                c.drawText("QTY", xQty, y + 8f, label.right()); c.drawText("WHERE", xWhere, y + 8f, label.right())
                y += 13f; c.drawLine(M, y, W - M, y, line); y += 5f
            }
            header(); continuing = header
            lines.forEach { l ->
                val m = materials.firstOrNull { it.id == l.materialId }
                val nl = layout(l.name + (m?.spec?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""), body, xQty - M - 80f)
                val h = maxOf(nl.height.toFloat(), 14f) + 10f
                need(h)
                c.drawRect(M, y + 1f, M + 10f, y + 11f, ink)
                draw(nl, M + 20f, y)
                c.drawText("${qtyText(l.qty)} ${l.unit}", xQty, y + 10f, strong.right())
                c.drawText(m?.location ?: "Buy", xWhere, y + 10f, body.right())
                y += h; c.drawLine(M, y - 4f, W - M, y - 4f, line)
            }
            if (lines.isEmpty()) para("No materials on this quote.", small)
            continuing = null

            section("Money")
            facts(listOf(
                "Quote total" to money(t.total),
                "Paid" to if (q.paidAt != null) "${money(q.paidAmount)} on ${day.format(Date(q.paidAt))}" else "Not yet",
            ))

            section("Notes on site")
            repeat(6) { need(24f); y += 22f; c.drawLine(M, y, W - M, y, line) }
            photos(photos)
        }
    }
}
