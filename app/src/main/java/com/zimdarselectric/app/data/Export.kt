package com.zimdarselectric.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A year's books for your accountant: income, expenses, mileage (spreadsheet files), a summary, and receipt copies. */
object Export {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun d(ms: Long?) = ms?.let { day.format(Date(it)) } ?: ""
    private fun m(v: Double) = "%.2f".format(Locale.US, v)
    private fun csv(vararg cells: Any?) = cells.joinToString(",") { c ->
        val t = (c ?: "").toString()
        if (t.any { it == ',' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    suspend fun build(context: Context, year: Int, withReceipts: Boolean): File = withContext(Dispatchers.IO) {
        val dao = AppDb.get(context).dao()
        val s = dao.settingsNow() ?: Settings()
        val from = MoneyCalc.ms(LocalDate.of(year, 1, 1)); val to = MoneyCalc.ms(LocalDate.of(year + 1, 1, 1))
        fun inYear(t: Long?) = t != null && t >= from && t < to
        val quotes = dao.quotesNow(); val lines = dao.allLinesNow()
        val expenses = dao.expensesNow().filter { inYear(it.date) }.sortedBy { it.date }
        val trips = dao.tripsNow().filter { inYear(it.startedAt) }.sortedBy { it.startedAt }
        val attachments = dao.attachmentsNow()

        val income = StringBuilder(csv("date_done", "date_paid", "number", "customer", "job", "type", "total_billed", "sales_tax", "capital_improvement_part", "repair_part", "amount_paid", "job_site") + "\n")
        quotes.filter { it.parentId == null && (inYear(it.paidAt) || inYear(it.completedAt)) }.sortedBy { it.paidAt ?: it.completedAt }.forEach { q ->
            val related = quotes.filter { it.id == q.id || it.parentId == q.id }
            val t = related.map { j -> computeTotals(j, lines.filter { it.quoteId == j.id }, s) }
            income.append(csv(d(q.completedAt), d(q.paidAt), q.number, q.customerName, q.title, q.jobType,
                m(t.sumOf { it.total }), m(t.sumOf { it.salesTax }), m(t.sumOf { it.installPart }), m(t.sumOf { it.repairPart }), m(q.paidAmount), q.site)).append('\n')
        }
        val exp = StringBuilder(csv("date", "vendor", "schedule_c_category", "amount", "note", "receipt_files") + "\n")
        expenses.forEach { e ->
            val files = attachments.filter { it.expenseId == e.id }.joinToString("; ") { "receipts/${e.id}_${it.fileName}" }
            exp.append(csv(d(e.date), e.vendor, e.category, m(e.amount), e.note, files)).append('\n')
        }
        val miles = StringBuilder(csv("date", "from", "to", "miles", "purpose", "irs_rate", "deduction") + "\n")
        trips.forEach { t -> val r = Geo.irsRate(t.startedAt); miles.append(csv(d(t.startedAt), t.fromLabel, t.toLabel, t.miles, t.purpose, r, m(t.miles * r))).append('\n') }
        quotes.filter { q -> q.miles > 0 && inYear(q.completedAt ?: q.paidAt) && trips.none { it.quoteId == q.id } }.forEach { q ->
            val at = q.completedAt ?: q.paidAt!!; val r = Geo.irsRate(at)
            miles.append(csv(d(at), s.address, q.site, q.miles, "Round trip for ${q.number} ${q.title}", r, m(q.miles * r))).append('\n')
        }
        val sum = MoneyCalc.summarize(from, to, quotes, lines, expenses, trips, s)
        val byCat = expenses.groupBy { it.category }.toSortedMap().entries.joinToString("\n") { (c, l) -> "  $c: $" + m(l.sumOf { it.amount }) }
        val summary = """
            ${s.businessName} · $year books (made ${d(System.currentTimeMillis())})

            Income from jobs (not counting sales tax): $${m(sum.income)}  (${sum.jobsPaid} jobs paid)
            Sales tax collected (owed to NY, not income): $${m(sum.taxCollected)}
            Expenses: $${m(sum.expenses)}
            $byCat
            Business miles: ${"%.1f".format(Locale.US, sum.miles)}  →  mileage deduction $${m(sum.mileage)} (IRS standard rate)
            Net profit: $${m(sum.profit)}

            Files: income.csv, expenses.csv, mileage.csv${if (withReceipts) ", receipts/ (copies of every receipt)" else ""}.
            Open the .csv files in Excel or Google Sheets.
        """.trimIndent()

        val out = File(File(context.cacheDir, "pdfs").apply { mkdirs() }, "${s.businessName.replace(Regex("[^A-Za-z0-9]+"), "-")}-$year-books.zip")
        ZipOutputStream(FileOutputStream(out)).use { z ->
            fun text(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
            text("summary.txt", summary); text("income.csv", income.toString()); text("expenses.csv", exp.toString()); text("mileage.csv", miles.toString())
            if (withReceipts) expenses.forEach { e ->
                attachments.filter { it.expenseId == e.id }.forEach { a ->
                    val f = File(a.path)
                    if (f.exists()) { z.putNextEntry(ZipEntry("receipts/${e.id}_${a.fileName}")); FileInputStream(f).use { it.copyTo(z) }; z.closeEntry() }
                }
            }
        }
        out
    }
}
