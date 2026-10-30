package com.zimdarselectric.app.data

import kotlin.math.ceil

/**
 * NEC 2023 numbers for the field calculators (New York's Uniform Code uses NFPA 70-2023 from Dec 31, 2025).
 * Tables: 314.16(A)/(B) box fill, Chapter 9 Tables 1/4/5 conduit fill, 310.16 ampacity, 310.12 dwelling services,
 * Chapter 9 Table 8 resistance, 220.82 dwelling optional load calculation.
 */
object Nec {
    val sizes = listOf("14", "12", "10", "8", "6", "4", "3", "2", "1", "1/0", "2/0", "3/0", "4/0")

    // ---------------- Box fill (314.16) ----------------
    /** Cubic inches per conductor, Table 314.16(B). */
    val boxVolumePerWire = mapOf("18" to 1.5, "16" to 1.75, "14" to 2.0, "12" to 2.25, "10" to 2.5, "8" to 3.0, "6" to 5.0)

    /** Standard metal boxes, Table 314.16(A) (cu in). Plastic boxes: use the volume stamped inside. */
    val metalBoxes = listOf(
        "4 in round/octagon × 1-1/4" to 12.5, "4 in octagon × 1-1/2" to 15.5, "4 in octagon × 2-1/8" to 21.5,
        "4 in square × 1-1/4" to 18.0, "4 in square × 1-1/2" to 21.0, "4 in square × 2-1/8" to 30.3,
        "4-11/16 sq × 1-1/4" to 25.5, "4-11/16 sq × 1-1/2" to 29.5, "4-11/16 sq × 2-1/8" to 42.0,
        "Device 3×2×1-1/2" to 7.5, "Device 3×2×2" to 10.0, "Device 3×2×2-1/4" to 10.5, "Device 3×2×2-1/2" to 12.5,
        "Device 3×2×2-3/4" to 14.0, "Device 3×2×3-1/2" to 18.0,
        "Handy box 4×2-1/8×1-1/2" to 10.3, "Handy box 4×2-1/8×1-7/8" to 13.0, "Handy box 4×2-1/8×2-1/8" to 14.5,
    )

    data class BoxFill(val required: Double, val lines: List<Pair<String, Double>>)

    /**
     * @param wires count of insulated conductors (hots, neutrals, travelers) by size, each one entering, leaving
     *  or spliced counts once; a wire passing through unbroken also counts once.
     * @param grounds number of equipment grounds entering (all together count once by the largest; ¼ more each over 4).
     * @param devices switches/receptacles (each yoke counts 2 of the largest wire connected to it).
     * @param clamps internal cable clamps present (any number count once by the largest wire).
     * @param studs fixture studs / hickeys (each counts once by the largest wire).
     */
    fun boxFill(wires: Map<String, Int>, grounds: Int, devices: Int, clamps: Boolean, studs: Int): BoxFill {
        val present = wires.filter { it.value > 0 }.keys
        val largest = present.maxByOrNull { boxVolumePerWire[it] ?: 0.0 } ?: "14"
        val big = boxVolumePerWire[largest] ?: 2.0
        val lines = mutableListOf<Pair<String, Double>>()
        wires.filter { it.value > 0 }.forEach { (sz, n) -> lines += "$n × #$sz conductors" to n * (boxVolumePerWire[sz] ?: 2.0) }
        if (grounds > 0) lines += "Grounds ($grounds)" to big * (1 + maxOf(0, grounds - 4) * 0.25)
        if (devices > 0) lines += "$devices device${if (devices == 1) "" else "s"} × 2" to devices * 2 * big
        if (clamps) lines += "Internal clamps" to big
        if (studs > 0) lines += "$studs fixture stud/hickey" to studs * big
        return BoxFill(lines.sumOf { it.second }, lines)
    }

    // ---------------- Conduit fill (Chapter 9) ----------------
    /** Total internal area (sq in) by trade size, Chapter 9 Table 4. */
    val conduits: Map<String, List<Pair<String, Double>>> = mapOf(
        "EMT" to listOf("1/2" to 0.304, "3/4" to 0.533, "1" to 0.864, "1-1/4" to 1.496, "1-1/2" to 2.036, "2" to 3.356),
        "PVC Sch 40" to listOf("1/2" to 0.285, "3/4" to 0.508, "1" to 0.832, "1-1/4" to 1.453, "1-1/2" to 1.986, "2" to 3.291),
        "PVC Sch 80" to listOf("1/2" to 0.217, "3/4" to 0.409, "1" to 0.688, "1-1/4" to 1.237, "1-1/2" to 1.711, "2" to 2.874),
    )
    /** THHN/THWN-2 area per wire (sq in), Chapter 9 Table 5. */
    val thhnArea = mapOf("14" to 0.0097, "12" to 0.0133, "10" to 0.0211, "8" to 0.0366, "6" to 0.0507, "4" to 0.0824, "3" to 0.0973,
        "2" to 0.1158, "1" to 0.1562, "1/0" to 0.1855, "2/0" to 0.2223, "3/0" to 0.2679, "4/0" to 0.3237)

    /** Allowed fill: 1 wire 53%, 2 wires 31%, 3+ wires 40% (Chapter 9 Table 1). */
    fun fillLimit(count: Int) = when (count) { 0 -> 0.0; 1 -> 0.53; 2 -> 0.31; else -> 0.40 }

    data class ConduitFill(val wireArea: Double, val conduitArea: Double, val pct: Double, val limit: Double) { val ok get() = pct <= limit * 100 + 1e-9 }

    fun conduitFill(type: String, size: String, wires: Map<String, Int>): ConduitFill {
        val area = conduits[type]?.firstOrNull { it.first == size }?.second ?: 0.0
        val wa = wires.entries.sumOf { (s, n) -> (thhnArea[s] ?: 0.0) * n }
        val n = wires.values.sum()
        return ConduitFill(wa, area, if (area > 0) wa / area * 100 else 0.0, fillLimit(n))
    }

    /** Smallest trade size of [type] these wires fit in, or null if none up to 2". */
    fun smallestConduit(type: String, wires: Map<String, Int>): String? =
        conduits[type]?.firstOrNull { conduitFill(type, it.first, wires).ok }?.first

    /** How many of one wire size fit (Annex C style). */
    fun maxWires(type: String, size: String, wire: String): Int {
        val area = conduits[type]?.firstOrNull { it.first == size }?.second ?: return 0
        val w = thhnArea[wire] ?: return 0
        var n = (area * 0.40 / w).toInt()
        if (n <= 2) n = when { area * 0.31 / w >= 2 -> 2; area * 0.53 / w >= 1 -> 1; else -> 0 }
        return n
    }

    // ---------------- Ampacity (310.16) ----------------
    /** Copper ampacity at 60 / 75 / 90 °C. */
    val copper = mapOf("14" to listOf(15, 20, 25), "12" to listOf(20, 25, 30), "10" to listOf(30, 35, 40), "8" to listOf(40, 50, 55),
        "6" to listOf(55, 65, 75), "4" to listOf(70, 85, 95), "3" to listOf(85, 100, 115), "2" to listOf(95, 115, 130), "1" to listOf(110, 130, 145),
        "1/0" to listOf(125, 150, 170), "2/0" to listOf(145, 175, 195), "3/0" to listOf(165, 200, 225), "4/0" to listOf(195, 230, 260))
    val aluminum = mapOf("12" to listOf(15, 20, 25), "10" to listOf(25, 30, 35), "8" to listOf(35, 40, 45), "6" to listOf(40, 50, 55),
        "4" to listOf(55, 65, 75), "3" to listOf(65, 75, 85), "2" to listOf(75, 90, 100), "1" to listOf(85, 100, 115),
        "1/0" to listOf(100, 120, 135), "2/0" to listOf(115, 135, 150), "3/0" to listOf(130, 155, 175), "4/0" to listOf(150, 180, 205))
    /** 240.4(D) small-conductor breaker limits. */
    private val smallLimit = mapOf("cu14" to 15, "cu12" to 20, "cu10" to 30, "al12" to 15, "al10" to 25)
    /** Ohms per 1000 ft, Chapter 9 Table 8 (uncoated copper / aluminum, stranded). */
    val ohmsCu = mapOf("14" to 3.14, "12" to 1.98, "10" to 1.24, "8" to 0.778, "6" to 0.491, "4" to 0.308, "3" to 0.245, "2" to 0.194,
        "1" to 0.154, "1/0" to 0.122, "2/0" to 0.0967, "3/0" to 0.0766, "4/0" to 0.0608)
    val ohmsAl = mapOf("12" to 3.25, "10" to 2.04, "8" to 1.28, "6" to 0.808, "4" to 0.508, "3" to 0.403, "2" to 0.319, "1" to 0.253,
        "1/0" to 0.201, "2/0" to 0.159, "3/0" to 0.126, "4/0" to 0.100)

    enum class WireType(val label: String, val column: Int) { NM("NM-B (Romex) / UF", 0), THHN("THHN in conduit · 75°C ends", 1), SER("SER / XHHW · 75°C ends", 1) }

    data class WirePick(val size: String, val ampacity: Int, val breakerMax: Int, val dropPct: Double, val why: String)

    /** Usable ampacity for a size: the table column for the wire type, limited by 240.4(D) for 14–10 AWG. */
    fun ampacity(size: String, aluminum: Boolean, type: WireType): Int? {
        val row = (if (aluminum) this.aluminum else copper)[size] ?: return null
        val a = row[type.column]
        val lim = smallLimit[(if (aluminum) "al" else "cu") + size]
        return if (lim != null) minOf(a, lim) else a
    }

    /** Volts dropped on a single-phase 2-wire run of [feet] one way. */
    fun drop(size: String, aluminum: Boolean, amps: Double, feet: Double): Double =
        2 * feet * ((if (aluminum) ohmsAl else ohmsCu)[size] ?: 99.0) * amps / 1000.0

    /**
     * Smallest wire that carries [amps] (×125% for continuous loads) and keeps voltage drop at or under [maxDropPct].
     */
    fun pickWire(amps: Double, feet: Double, volts: Double, aluminum: Boolean, type: WireType, continuous: Boolean, maxDropPct: Double = 3.0): WirePick? {
        val need = if (continuous) amps * 1.25 else amps
        val list = if (aluminum) sizes.filter { it != "14" } else sizes
        var ampOnly: String? = null
        for (s in list) {
            val a = ampacity(s, aluminum, type) ?: continue
            if (a < need) continue
            if (ampOnly == null) ampOnly = s
            val pct = drop(s, aluminum, amps, feet) / volts * 100
            if (pct <= maxDropPct) {
                val why = if (s != ampOnly) "#$ampOnly would carry the load, but the drop over ${feet.toInt()} ft is too much, so go up to #$s." else "Carries ${"%.0f".format(need)} A${if (continuous) " (125% for a continuous load)" else ""}."
                return WirePick(s, a, a, pct, why)
            }
        }
        return null
    }

    /** Dwelling service or main feeder conductors, Table 310.12 (Cu to Al). */
    val dwellingService = listOf(100 to ("4" to "2"), 110 to ("3" to "1"), 125 to ("2" to "1/0"), 150 to ("1" to "2/0"), 175 to ("1/0" to "3/0"),
        200 to ("2/0" to "4/0"), 225 to ("3/0" to "250 kcmil"), 250 to ("4/0" to "300 kcmil"), 300 to ("250 kcmil" to "350 kcmil"),
        350 to ("350 kcmil" to "500 kcmil"), 400 to ("400 kcmil" to "600 kcmil"))

    // ---------------- Dwelling load calc (220.82 optional method) ----------------
    data class Appliance(val name: String, val watts: Double)
    val commonAppliances = listOf(
        Appliance("Electric range", 12000.0), Appliance("Electric dryer", 5000.0), Appliance("Water heater", 4500.0),
        Appliance("Dishwasher", 1200.0), Appliance("Disposal", 900.0), Appliance("Microwave", 1500.0),
        Appliance("EV charger (32 A)", 7680.0), Appliance("Well pump", 1500.0), Appliance("Hot tub", 7500.0),
        Appliance("Furnace blower", 800.0), Appliance("Refrigerator", 800.0),
    )

    data class LoadCalc(val general: Double, val generalDemand: Double, val heatCool: Double, val total: Double, val amps: Double, val service: Int)

    /**
     * 220.82: 3 VA/sq ft + 1500 VA per small-appliance and laundry circuit + appliance nameplates;
     * first 10 kVA at 100%, the rest at 40%; plus the largest of A/C (100%) or electric heat (65% if under 4 units, 40% if 4+).
     */
    fun loadCalc(sqft: Double, smallApplianceCircuits: Int, laundryCircuits: Int, appliances: List<Appliance>,
                 acWatts: Double, heatWatts: Double, heatUnits: Int): LoadCalc {
        val general = sqft * 3 + (smallApplianceCircuits + laundryCircuits) * 1500 + appliances.sumOf { it.watts }
        val demand = minOf(general, 10_000.0) + maxOf(0.0, general - 10_000) * 0.40
        val heat = heatWatts * if (heatUnits >= 4) 0.40 else 0.65
        val hc = maxOf(acWatts, heat)
        val total = demand + hc
        val amps = total / 240.0
        val service = listOf(100, 125, 150, 200, 225, 320, 400).firstOrNull { it >= amps } ?: (ceil(amps / 100) * 100).toInt()
        return LoadCalc(general, demand, hc, total, amps, service)
    }
}
