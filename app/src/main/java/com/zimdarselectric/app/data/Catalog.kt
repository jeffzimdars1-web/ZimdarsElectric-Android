package com.zimdarselectric.app.data

/** A built-in electrical material: what you'd call it on a quote, plus the unit you usually buy it in. */
data class CatalogItem(val name: String, val unit: String, val spec: String = "", val category: String)

/**
 * Generic electrical materials for autofill. Generated from size and type lists so it stays consistent.
 * Brand/model goes in [CatalogItem.spec] where it matters (breakers).
 */
object Catalog {
    val items: List<CatalogItem> by lazy { build() }

    private val conduitSizes = listOf("1/2", "3/4", "1", "1-1/4", "1-1/2", "2")

    private fun build(): List<CatalogItem> {
        val out = mutableListOf<CatalogItem>()
        fun add(name: String, unit: String = "ea", cat: String, spec: String = "") = out.add(CatalogItem(name, unit, spec, cat))

        // ---- Cable ----
        listOf("14/2", "14/3", "12/2", "12/3", "10/2", "10/3", "8/2", "8/3", "6/2", "6/3").forEach {
            add("$it NM-B cable w/ground", "ft", "Cable")
        }
        listOf("14/2", "14/3", "12/2", "12/3", "10/2", "10/3", "8/2", "8/3", "6/2", "6/3").forEach {
            add("$it UF-B cable w/ground", "ft", "Cable")
        }
        listOf("14/2", "14/3", "12/2", "12/3", "12/4", "10/2", "10/3", "8/3", "6/3").forEach {
            add("$it MC cable", "ft", "Cable")
        }
        listOf("2-2-2-4", "4/0-4/0-4/0-2/0", "1/0-1/0-1/0-2", "2/0-2/0-2/0-1/0").forEach {
            add("$it aluminum SER cable", "ft", "Cable")
        }
        listOf("2-2-4", "1/0-1/0-2", "4/0-4/0-2/0").forEach { add("$it aluminum SEU cable", "ft", "Cable") }

        // ---- Building wire ----
        val colors = listOf("black", "white", "red", "green", "blue")
        listOf("14", "12", "10").forEach { g -> colors.forEach { c -> add("$g AWG THHN copper, $c", "ft", "Wire") } }
        listOf("8", "6", "4", "3", "2", "1", "1/0", "2/0", "3/0", "4/0").forEach { add("$it AWG THHN copper", "ft", "Wire") }
        listOf("6", "4", "2", "1/0", "2/0", "3/0", "4/0", "250 kcmil").forEach { add("$it XHHW-2 aluminum", "ft", "Wire") }
        listOf("8", "6", "4", "2").forEach { add("$it AWG bare copper ground", "ft", "Wire") }
        listOf("1/0", "2/0", "4/0").forEach { add("$it USE-2 aluminum", "ft", "Wire") }

        // ---- Low voltage ----
        listOf("Cat6 data cable", "Cat5e data cable", "Cat6A data cable", "RG6 coax cable", "18/2 thermostat wire", "18/5 thermostat wire",
            "18/8 thermostat wire", "16/2 speaker wire", "16/4 security/fire alarm wire", "18/2 doorbell wire").forEach { add(it, "ft", "Low voltage") }
        listOf("RJ45 keystone jack, Cat6", "Coax F connector", "1-port data wallplate", "2-port data wallplate", "Coax wallplate",
            "Low voltage mounting bracket, 1-gang", "Low voltage mounting bracket, 2-gang", "Structured media enclosure", "Doorbell transformer").forEach { add(it, cat = "Low voltage") }

        // ---- Conduit ----
        conduitSizes.forEach { s ->
            add("$s in EMT conduit, 10 ft", "stick", "Conduit")
            add("$s in PVC Sch 40 conduit, 10 ft", "stick", "Conduit")
            add("$s in PVC Sch 80 conduit, 10 ft", "stick", "Conduit")
        }
        listOf("1/2", "3/4", "1").forEach { s ->
            add("$s in rigid (RMC) conduit, 10 ft", "stick", "Conduit")
            add("$s in flexible metal conduit (FMC)", "ft", "Conduit")
        }
        listOf("1/2", "3/4", "1", "1-1/4").forEach { add("$it in liquidtight flexible conduit", "ft", "Conduit") }

        // ---- Fittings ----
        conduitSizes.forEach { s ->
            add("$s in EMT set-screw connector", cat = "Fittings")
            add("$s in EMT set-screw coupling", cat = "Fittings")
            add("$s in EMT compression connector", cat = "Fittings")
            add("$s in EMT compression coupling", cat = "Fittings")
            add("$s in EMT 90° elbow", cat = "Fittings")
            add("$s in one-hole strap", cat = "Fittings")
            add("$s in two-hole strap", cat = "Fittings")
            add("$s in PVC coupling", cat = "Fittings")
            add("$s in PVC male adapter", cat = "Fittings")
            add("$s in PVC female adapter", cat = "Fittings")
            add("$s in PVC 90° elbow", cat = "Fittings")
            add("$s in PVC LB conduit body", cat = "Fittings")
            add("$s in PVC expansion coupling", cat = "Fittings")
            add("$s in locknut", cat = "Fittings")
            add("$s in plastic bushing", cat = "Fittings")
            add("$s in grounding bushing", cat = "Fittings")
            add("$s in rigid conduit locknut", cat = "Fittings")
            add("$s in meter hub", cat = "Fittings")
        }
        listOf("1/2", "3/4", "1", "1-1/4").forEach { s ->
            add("$s in liquidtight straight connector", cat = "Fittings")
            add("$s in liquidtight 90° connector", cat = "Fittings")
            add("$s in FMC connector", cat = "Fittings")
            add("$s in metal LB conduit body", cat = "Fittings")
        }
        listOf("1-1/4", "1-1/2", "2").forEach { add("$it in service entrance weatherhead", cat = "Fittings") }
        add("3/8 in NM cable connector", cat = "Fittings"); add("1/2 in NM cable connector", cat = "Fittings")
        add("3/8 in MC cable connector", cat = "Fittings"); add("Anti-short bushings (bag)", cat = "Fittings")
        add("Knockout seal / KO filler", cat = "Fittings")

        // ---- Boxes ----
        (1..4).forEach { g ->
            add("$g-gang old work box, plastic", cat = "Boxes")
            add("$g-gang new work box, plastic", cat = "Boxes")
            add("$g-gang steel device box", cat = "Boxes")
        }
        listOf("1-gang weatherproof box", "2-gang weatherproof box", "4 in square box, 1-1/2 in deep", "4 in square box, 2-1/8 in deep",
            "4-11/16 in square box, 2-1/8 in deep", "4 in octagon box", "Fan-rated ceiling box", "Fan-rated old work ceiling box",
            "Pancake box", "Round ceiling box, plastic", "1-gang mud ring", "2-gang mud ring", "4 in square blank cover",
            "Box extension ring", "Fan brace bar", "Junction box, PVC 4x4", "Junction box, PVC 6x6").forEach { add(it, cat = "Boxes") }

        // ---- Devices ----
        listOf("15A", "20A").forEach { a ->
            add("$a duplex receptacle", cat = "Devices")
            add("$a tamper-resistant duplex receptacle", cat = "Devices")
            add("$a TR weather-resistant receptacle", cat = "Devices")
            add("$a GFCI receptacle", cat = "Devices")
            add("$a TR weather-resistant GFCI receptacle", cat = "Devices")
            add("$a USB receptacle (Type A/C)", cat = "Devices")
            add("$a single-pole switch", cat = "Devices")
            add("$a 3-way switch", cat = "Devices")
            add("$a 4-way switch", cat = "Devices")
            add("$a decorator switch", cat = "Devices")
        }
        listOf("NEMA 14-30 dryer receptacle", "NEMA 14-50 range/EV receptacle", "NEMA 6-50 receptacle", "NEMA 6-20 receptacle",
            "NEMA TT-30 RV receptacle", "NEMA 5-20 single receptacle", "NEMA L14-30 twist-lock inlet (generator)",
            "Dimmer switch", "LED dimmer switch", "Smart switch", "Smart dimmer", "Motion sensor switch", "Occupancy/vacancy sensor switch",
            "Timer switch", "Pilot light switch", "Doorbell button").forEach { add(it, cat = "Devices") }

        // ---- Wallplates ----
        (1..4).forEach { g ->
            add("$g-gang toggle wallplate", cat = "Wallplates")
            add("$g-gang decorator wallplate", cat = "Wallplates")
        }
        listOf("1-gang duplex wallplate", "1-gang blank wallplate", "2-gang blank wallplate", "1-gang combo toggle/decorator plate",
            "Weatherproof in-use cover, 1-gang", "Weatherproof in-use cover, 2-gang", "Weatherproof flip cover, 1-gang",
            "Dryer/range receptacle plate").forEach { add(it, cat = "Wallplates") }

        // ---- Breakers (spec = brand line) ----
        listOf("Square D QO", "Square D Homeline", "Eaton BR", "Eaton CH", "Siemens", "GE").forEach { b ->
            listOf("15", "20").forEach { a -> add("${a}A 1-pole breaker", cat = "Breakers", spec = b) }
            listOf("15", "20", "30", "40", "50", "60", "100").forEach { a -> add("${a}A 2-pole breaker", cat = "Breakers", spec = b) }
            listOf("15", "20").forEach { a ->
                add("${a}A AFCI breaker", cat = "Breakers", spec = b)
                add("${a}A GFCI breaker", cat = "Breakers", spec = b)
                add("${a}A dual function AFCI/GFCI breaker", cat = "Breakers", spec = b)
                add("$a/${a}A tandem breaker", cat = "Breakers", spec = b)
            }
            add("50A 2-pole GFCI breaker (EV / hot tub)", cat = "Breakers", spec = b)
            add("Whole-home surge protective device", cat = "Breakers", spec = b)
        }

        // ---- Panels + service ----
        listOf("100A main breaker panel", "150A main breaker panel", "200A main breaker panel", "100A main lug load center",
            "125A main lug load center", "200A meter socket, ringless", "100A meter socket", "200A meter/main combo",
            "30A non-fused disconnect", "60A non-fused disconnect", "60A fused disconnect", "60A AC disconnect",
            "100A outdoor disconnect", "Generator interlock kit", "Manual transfer switch", "Power inlet box, 30A",
            "Power inlet box, 50A", "Panel directory labels").forEach { add(it, cat = "Panels + service") }

        // ---- Grounding ----
        listOf("8 ft ground rod, 5/8 in copper-bonded", "Ground rod acorn clamp, 5/8 in", "Water pipe ground clamp, 1/2-1 in",
            "Intersystem bonding terminal", "Green ground screws (bag)", "Ground pigtails (bag)", "Grounding bar kit",
            "Neutral bar kit", "Bonding jumper").forEach { add(it, cat = "Grounding") }

        // ---- Connectors + consumables ----
        listOf("yellow", "orange", "red", "blue", "gray", "tan").forEach { add("Twist-on wire connectors, $it (wire nuts)", cat = "Connectors") }
        listOf("2-port", "3-port", "5-port").forEach { add("Lever splice connector, $it (Wago style)", cat = "Connectors") }
        listOf("Insulated butt splices", "Ring terminals", "Spade terminals", "Heat shrink tubing", "Polaris insulated connector",
            "Split bolt connector", "Wire ferrules").forEach { add(it, cat = "Connectors") }
        listOf("NM cable staples", "Insulated cable staples", "Cable ties", "Electrical tape", "Self-fusing silicone tape", "Duct seal, 1 lb",
            "Wire pulling lubricant", "Cable stackers", "Wire labels", "Silicone sealant", "Masonry anchors / Tapcons",
            "Wood screws, exterior").forEach { add(it, cat = "Consumables") }

        // ---- Lighting + fixtures ----
        listOf("LED wafer light, 4 in", "LED wafer light, 6 in", "LED recessed retrofit, 4 in", "LED recessed retrofit, 6 in",
            "LED flush mount ceiling light", "Wall sconce", "Outdoor wall lantern", "LED flood light", "LED motion flood light",
            "LED shop light", "LED under-cabinet light", "Closet light", "Photocell dusk-to-dawn control", "Bathroom exhaust fan",
            "Bathroom exhaust fan with light", "Ceiling fan", "Ceiling fan with light", "Smoke alarm, hardwired",
            "CO alarm, hardwired", "Smoke/CO combo alarm, hardwired", "LED driver", "Fixture mounting bracket").forEach { add(it, cat = "Lighting") }

        return out
    }

    /** Turns shorthand into catalog words: "romex 12-2" -> "nm-b 12/2". */
    fun normalize(q: String): List<String> {
        var s = q.lowercase().trim()
        s = Regex("""(\d)\s*-\s*(\d)""").replace(s) { "${it.groupValues[1]}/${it.groupValues[2]}" }
        val synonyms = mapOf(
            "romex" to "nm-b", "uf" to "uf-b", "wirenut" to "twist-on", "wirenuts" to "twist-on",
            "wago" to "lever", "wagos" to "lever", "gfi" to "gfci", "outlet" to "receptacle", "outlets" to "receptacle",
            "plug" to "receptacle", "recep" to "receptacle", "breakers" to "breaker", "afci" to "afci", "can" to "recessed",
            "cans" to "recessed", "jbox" to "junction", "j-box" to "junction", "emt" to "emt", "pvc" to "pvc",
            "sticks" to "conduit", "pipe" to "conduit", "homeline" to "homeline", "qo" to "qo", "ground" to "ground",
        )
        return s.replace("wire nut", "wirenut").split(Regex("""[\s,]+""")).filter { it.isNotBlank() }.map { synonyms[it] ?: it }
    }

    /** Best matches for what you've typed. Every word must appear in the name or brand. */
    fun search(query: String, limit: Int = 8, exclude: Set<String> = emptySet()): List<CatalogItem> {
        val tokens = normalize(query)
        if (tokens.isEmpty()) return emptyList()
        return items.asSequence()
            .filter { it.name.lowercase() !in exclude }
            .map { item ->
                val hay = (item.name + " " + item.spec).lowercase()
                val hit = tokens.all { hay.contains(it) }
                val score = if (!hit) -1 else (if (hay.startsWith(tokens.first())) 2 else 0) + (if (item.name.lowercase().startsWith(tokens.first())) 1 else 0)
                item to score
            }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    fun matchesText(text: String, query: String): Boolean {
        val tokens = normalize(query)
        val hay = text.lowercase()
        return tokens.all { hay.contains(it) }
    }
}
