package com.zimdarselectric.app.data

import org.json.JSONObject

/** What you know about a customer's house: panel, service, wiring… saved on the customer, one entry per property address. */
object House {
    data class Field(val key: String, val label: String, val choices: List<String> = emptyList(), val multi: Boolean = false)

    val fields = listOf(
        Field("amps", "Service size", listOf("60A", "100A", "150A", "200A", "400A")),
        Field("panel", "Panel brand / model (e.g. Square D QO, 40 space)"),
        Field("panelWhere", "Panel location (e.g. basement, north wall)"),
        Field("panelAge", "Panel age or year"),
        Field("subpanels", "Subpanels (where, size)"),
        Field("service", "Service drop", listOf("Overhead", "Underground")),
        Field("wiring", "Wiring", listOf("Romex", "BX / armored", "Conduit", "Knob & tube", "Aluminum branch", "Cloth-covered"), multi = true),
        Field("grounding", "Grounding / bonding (rods, water pipe, gas bond)"),
        Field("detectors", "Smoke / CO detectors (type, age)"),
        Field("generator", "Generator / inlet / transfer switch"),
        Field("surge", "Surge protection"),
        Field("built", "Year the house was built"),
        Field("notes", "Notes (access, attic, crawl space, dog, gate code…)"),
    )

    private val words = mapOf("street" to "st", "road" to "rd", "avenue" to "ave", "place" to "pl", "drive" to "dr", "lane" to "ln",
        "court" to "ct", "boulevard" to "blvd", "route" to "rt", "highway" to "hwy", "north" to "n", "south" to "s", "east" to "e", "west" to "w")

    /** The same house, however the address was typed: "110 State Street, Prospect" == "110 state st". */
    fun key(address: String): String = address.substringBefore(',').lowercase()
        .split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.joinToString("") { words[it] ?: it }

    fun sameHouse(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && key(a) == key(b)

    fun get(c: Customer?, address: String): Map<String, String> {
        if (c == null || c.houses.isBlank()) return emptyMap()
        val o = runCatching { JSONObject(c.houses).optJSONObject(key(address)) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    }

    fun put(c: Customer, address: String, info: Map<String, String>): Customer {
        val all = runCatching { JSONObject(c.houses) }.getOrNull() ?: JSONObject()
        all.put(key(address), JSONObject().apply { info.filterValues { it.isNotBlank() }.forEach { (k, v) -> put(k, v) } })
        return c.copy(houses = all.toString())
    }

    /** Label + value pairs for printing (job copy). */
    fun facts(info: Map<String, String>): List<Pair<String, String>> =
        fields.mapNotNull { f -> info[f.key]?.takeIf { it.isNotBlank() }?.let { f.label.substringBefore(" (") to it } }

    /** Jobs and visits at this house, newest first. */
    fun jobs(c: Customer, address: String, quotes: List<Quote>): List<Quote> = quotes.filter { q ->
        q.customerId == c.id && q.optionOf == null && (sameHouse(q.site, address) || (q.site.isBlank() && sameHouse(c.address, address)))
    }.sortedByDescending { it.completedAt ?: it.startAt ?: it.createdAt }
}
