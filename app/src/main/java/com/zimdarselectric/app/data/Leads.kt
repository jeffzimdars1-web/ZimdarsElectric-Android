package com.zimdarselectric.app.data

/** Where customers find you, and what each place earns. */
object Leads {
    val sources = listOf("Google", "Facebook", "Referral", "Booking page", "Truck / sign", "Repeat customer", "Other")

    data class Row(val source: String, val customers: Int, val jobs: Int, val income: Double)

    /** Customers and paid income (no sales tax) by source, for jobs paid between [from] and [to]. */
    fun bySource(from: Long, to: Long, customers: List<Customer>, quotes: List<Quote>, lines: List<QuoteLine>, s: Settings): List<Row> {
        val src = customers.associate { it.id to it.source.ifBlank { "Not set" } }
        val paid = quotes.filter { it.parentId == null && it.optionOf == null && it.paidAt != null && it.paidAt >= from && it.paidAt < to }
        val byS = paid.groupBy { src[it.customerId] ?: "Not set" }
        val newCust = customers.filter { it.createdAt in from until to }.groupBy { it.source.ifBlank { "Not set" } }
        return (byS.keys + newCust.keys).distinct().map { k ->
            val jobs = byS[k].orEmpty()
            val income = jobs.sumOf { q ->
                val related = quotes.filter { it.id == q.id || it.parentId == q.id }
                val t = related.map { j -> computeTotals(j, lines.filter { it.quoteId == j.id }, s) }
                val total = t.sumOf { it.total }; val tax = t.sumOf { it.salesTax }
                if (total > 0) q.paidAmount * (total - tax) / total else q.paidAmount
            }
            Row(k, newCust[k]?.size ?: 0, jobs.size, income)
        }.sortedByDescending { it.income }
    }
}
