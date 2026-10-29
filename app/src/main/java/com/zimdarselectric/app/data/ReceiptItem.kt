package com.zimdarselectric.app.data

import org.json.JSONArray

/** One line from a receipt in Books, e.g. "ID TWISTER TAN 250PK JAR", 1 × $34.98, $38.04 with tax. */
data class ReceiptItem(
    val name: String,
    val qty: Double,
    /** Price per unit before tax. */
    val each: Double,
    /** Price per unit including its share of the sales tax: what it really cost you. */
    val paidEach: Double,
    val sku: String,
    val model: String,
    val store: String,
    val date: Long,
    val expenseId: Long,
) {
    /** Pieces per pack if the receipt name says so ("250PK", "100 pack"). */
    val packCount: Int? get() = LabelReader.read(name).packCount

    /** Feet on a roll if the receipt name says so ("50-FT", "250 ft"). */
    val feet: Int? get() = Regex("""(?i)(?<![\d/])(\d{2,4})\s*-?\s*(?:ft|feet)\b""").find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 10..2500 }

    companion object {
        fun from(e: Expense): List<ReceiptItem> {
            if (e.items.isBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(e.items)
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val each = o.optDouble("each", 0.0)
                    ReceiptItem(o.optString("name"), o.optDouble("qty", 1.0), each, o.optDouble("paidEach", each),
                        o.optString("sku"), o.optString("model"), e.vendor, e.date, e.id)
                }
            }.getOrDefault(emptyList())
        }

        /** Short receipt words spelled out, so "INS STPL" matches "insulated staples". */
        private val expand = mapOf(
            "ins" to "insulated", "stpl" to "staples", "stpls" to "staples", "con" to "connector", "conn" to "connector",
            "mtl" to "metal", "recept" to "receptacle", "rcpt" to "receptacle", "comm" to "commercial", "rnd" to "round",
            "hol" to "hole", "sw" to "switch", "gfi" to "gfci", "pwrpro" to "power pro", "ext" to "exterior",
            "etn" to "eaton", "hbl" to "hubbell", "id" to "ideal", "twister" to "twister wire connector", "wing-nut" to "wire connector", "twist-on" to "twist-on wire connector", "connectors" to "connector", "staple" to "staples",
        )
        private val skip = setOf("in", "the", "and", "with", "for", "of", "pack", "ea", "box")

        fun words(text: String): Set<String> =
            LabelReader.normalizeText(text).split(' ', '-', '/', ',').map { it.trim('.', '(', ')') }
                .flatMap { (expand[it] ?: it).split(' ') }
                .filter { it.length >= 3 && it !in skip }.toSet()

        /** Receipt lines that look like [query], best match first. Blank query lists everything. */
        fun search(items: List<ReceiptItem>, query: String): List<ReceiptItem> {
            val q = words(query)
            if (q.isEmpty()) return items.sortedByDescending { it.date }
            return items.map { it to words(it.name + " " + it.model).count { w -> w in q || q.any { qq -> qq.length >= 5 && (w.startsWith(qq) || qq.startsWith(w)) } } }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<ReceiptItem, Int>> { it.second }.thenByDescending { it.first.date })
                .map { it.first }
        }
    }
}
