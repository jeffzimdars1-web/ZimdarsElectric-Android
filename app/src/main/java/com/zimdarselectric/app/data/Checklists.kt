package com.zimdarselectric.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Job checklists, saved on the quote as JSON. */
object Checklists {
    data class Item(val text: String, val done: Boolean)
    data class List_(val title: String, val items: List<Item>)

    val templates: Map<String, List<String>> = linkedMapOf(
        "Start of job · safety" to listOf("Walked the job with the customer", "Found and labeled the right breaker(s)", "Locked out / tagged out and verified dead with a tester",
            "Tested the tester on a known live source", "Drop cloths down, path protected", "Ladder and work area safe"),
        "Panel inspection" to listOf("Panel make/model and amperage noted", "No signs of heat, rust or water", "Breakers match wire sizes", "No double-tapped breakers",
            "Neutrals and grounds correct (separated in subpanels)", "Bonding and grounding electrode in place", "Directory filled out and legible", "AFCI/GFCI protection where required"),
        "Receptacle / switch work" to listOf("Correct device rating (15A / 20A)", "Tamper-resistant where required", "GFCI where required, tested", "Polarity and ground checked with tester",
            "Box fill OK", "Plate on, flush and straight"),
        "Finish + clean-up" to listOf("All circuits back on and tested", "GFCI/AFCI test buttons work", "Covers and plates installed", "Work area cleaned up, debris removed",
            "Walked the customer through the work", "Photos taken (after)", "Panel directory updated"),
        "Service upgrade" to listOf("Utility disconnect/reconnect scheduled", "Permit pulled", "Meter base and mast secure", "Service entrance conductors sized right",
            "Grounding electrode system complete", "Bonding (water, gas) done", "Inspection scheduled", "Directory filled out"),
    )

    fun parse(json: String): List<List_> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val items = o.getJSONArray("items")
            List_(o.getString("title"), (0 until items.length()).map { j -> items.getJSONObject(j).let { Item(it.getString("t"), it.optBoolean("done")) } })
        }
    }.getOrDefault(emptyList())

    fun write(lists: List<List_>): String = JSONArray().apply {
        lists.forEach { l -> put(JSONObject().put("title", l.title).put("items", JSONArray().apply { l.items.forEach { put(JSONObject().put("t", it.text).put("done", it.done)) } })) }
    }.toString()

    fun add(json: String, template: String): String =
        write(parse(json) + List_(template, templates[template].orEmpty().map { Item(it, false) }))

    fun toggle(json: String, list: Int, item: Int): String = write(parse(json).mapIndexed { i, l ->
        if (i != list) l else l.copy(items = l.items.mapIndexed { j, it -> if (j == item) it.copy(done = !it.done) else it })
    })

    fun remove(json: String, list: Int): String = write(parse(json).filterIndexed { i, _ -> i != list })
}
