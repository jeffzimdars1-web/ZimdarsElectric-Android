package com.zimdarselectric.app.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A job laid out in the camera: boxes and devices placed on walls/ceilings, and cable or conduit runs between them.
 * Positions are in meters (the AR world). This file has no Android code so the math can be unit tested.
 */
object JobBuild {
    const val FT = 3.28084
    /** 16 inches on center. */
    const val STUD_OC_M = 0.4064

    enum class PartType(val label: String, val ceiling: Boolean = false) {
        RECEPTACLE("Receptacle"), GFCI("GFCI"), SWITCH("Switch"), SWITCH3("3-way switch"),
        LIGHT("Light (box)", ceiling = true), WAFER("Wafer light", ceiling = true), JBOX("Junction box"), PANEL("Panel / start"),
    }

    enum class RunType(val label: String, val conduit: Boolean, val gauge: Int, val cable: String = "", val emtSize: String = "") {
        NM14_2("14/2 Romex", false, 14, cable = "14/2"), NM12_2("12/2 Romex", false, 12, cable = "12/2"),
        NM14_3("14/3 Romex", false, 14, cable = "14/3"), NM12_3("12/3 Romex", false, 12, cable = "12/3"),
        EMT12_14("1/2\" EMT · #14", true, 14, emtSize = "1/2"), EMT12_12("1/2\" EMT · #12", true, 12, emtSize = "1/2"),
        EMT34_12("3/4\" EMT · #12", true, 12, emtSize = "3/4");
        val conductors: Int get() = if (cable.endsWith("/3")) 4 else 3
    }

    data class V3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = V3(x * s, y * s, z * s)
        fun dot(o: V3) = x * o.x + y * o.y + z * o.z
        fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        val length get() = sqrt(x * x + y * y + z * z)
        fun norm(): V3 { val l = length; return if (l < 1e-9) this else this * (1 / l) }
        companion object { val UP = V3(0.0, 1.0, 0.0) }
    }

    data class Part(val id: Int, val type: PartType, val pos: V3)
    data class Run(val from: Int, val to: Int, val type: RunType)

    /** A wall for the cutaway view: a point on one stud's center, and the wall's facing direction. */
    data class Wall(val stud: V3, val normal: V3) {
        /** Horizontal direction along the wall. */
        val along: V3 get() = V3.UP.cross(normal).norm()
        /** Stud center offsets (meters along the wall from [stud]) between [from] and [to]. */
        fun studsBetween(from: Double, to: Double): List<Double> {
            val lo = minOf(from, to); val hi = maxOf(from, to)
            val first = ceil(lo / STUD_OC_M).toInt(); val last = floor(hi / STUD_OC_M).toInt()
            return (first..last).map { it * STUD_OC_M }
        }
        fun offset(p: V3) = (p - stud).dot(along)
        fun distance(p: V3) = abs((p - stud).dot(normal.norm()))
    }

    /**
     * The way an electrician would run it: level from the start to right above/below the end, then straight up or down.
     * From a ceiling item it drops first, then goes level.
     */
    fun path(a: Part, b: Part): List<V3> {
        val corner = if (a.type.ceiling && !b.type.ceiling) V3(a.pos.x, b.pos.y, a.pos.z) else V3(b.pos.x, a.pos.y, b.pos.z)
        val pts = mutableListOf(a.pos)
        if ((corner - a.pos).length > 0.03 && (corner - b.pos).length > 0.03) pts += corner
        pts += b.pos
        return pts
    }

    fun lengthM(pts: List<V3>) = pts.zipWithNext().sumOf { (p, q) -> (q - p).length }

    /** Stud bays crossed by the level part of a run that's inside [wall] (holes to drill). */
    fun studHoles(pts: List<V3>, wall: Wall?): Int {
        wall ?: return 0
        return pts.zipWithNext().sumOf { (p, q) ->
            val level = abs(p.y - q.y) < 0.05
            if (!level || wall.distance(p) > 0.35 || wall.distance(q) > 0.35) 0
            else {
                // Don't count the stud a box is screwed to: ignore studs within 2" of either end.
                val a = wall.offset(p); val b = wall.offset(q)
                wall.studsBetween(a, b).count { abs(it - a) > 0.05 && abs(it - b) > 0.05 }
            }
        }
    }

    data class Line(val name: String, val unit: String, val qty: Double, val why: String = "")
    data class Takeoff(val lines: List<Line>, val feetByRun: Map<RunType, Double>, val holes: Int, val notes: List<String>)

    /**
     * The material list for the build.
     * @param oldWork walls/ceilings are closed (old work boxes, cable fished, no staples).
     * @param slackFt extra cable/wire at each box.
     */
    fun takeoff(parts: List<Part>, runs: List<Run>, wall: Wall? = null, oldWork: Boolean = true, slackFt: Double = 1.0): Takeoff {
        val out = LinkedHashMap<String, Line>()
        fun add(name: String, unit: String, qty: Double, why: String = "") {
            if (qty <= 0) return
            val cur = out[name]
            out[name] = if (cur == null) Line(name, unit, qty, why) else cur.copy(qty = cur.qty + qty)
        }
        val byId = parts.associateBy { it.id }
        val feet = LinkedHashMap<RunType, Double>()
        var holes = 0
        val notes = mutableListOf<String>()

        // ---- Runs ----
        runs.forEach { r ->
            val a = byId[r.from] ?: return@forEach; val b = byId[r.to] ?: return@forEach
            val pts = path(a, b)
            val runFt = lengthM(pts) * FT
            feet[r.type] = (feet[r.type] ?: 0.0) + runFt
            holes += studHoles(pts, wall)
            val bends = pts.size - 2
            if (!r.type.conduit) {
                add("${r.type.cable} NM-B cable w/ground", "ft", ceil(runFt + 2 * slackFt))
                if (!oldWork) add("NM cable staples", "ea", ceil(runFt / 4.5) + 2, "Every 4.5 ft and within 12 in of each box")
            } else {
                val s = r.type.emtSize
                val sticks = ceil(runFt / 10.0)
                add("$s in EMT conduit, 10 ft", "stick", sticks)
                add("$s in EMT set-screw coupling", "ea", max(0.0, sticks - 1))
                add("$s in EMT set-screw connector", "ea", 2.0)
                add("$s in one-hole strap", "ea", ceil(runFt / 10.0) + 1, "Every 10 ft and within 3 ft of each box")
                if (bends > 0) add("$s in EMT 90° elbow", "ea", bends.toDouble(), "Or bend it")
                val wireFt = ceil(runFt + 2 * slackFt)
                add("${r.type.gauge} AWG THHN copper, black", "ft", wireFt)
                add("${r.type.gauge} AWG THHN copper, white", "ft", wireFt)
                add("${r.type.gauge} AWG THHN copper, green", "ft", wireFt)
            }
        }

        // ---- Boxes + devices ----
        fun gaugeAt(p: Part) = runs.filter { it.from == p.id || it.to == p.id }.minOfOrNull { it.type.gauge } ?: 14
        fun conduitAt(p: Part) = runs.any { (it.from == p.id || it.to == p.id) && it.type.conduit }
        fun cablesAt(p: Part) = runs.count { it.from == p.id || it.to == p.id }
        val box1 = if (oldWork) "1-gang old work box, plastic" else "1-gang new work box, plastic"
        parts.forEach { p ->
            val amps = if (gaugeAt(p) <= 12) "20A" else "15A"
            val deviceBox = if (conduitAt(p)) "1-gang steel device box" else box1
            when (p.type) {
                PartType.RECEPTACLE -> { add(deviceBox, "ea", 1.0); add("$amps tamper-resistant duplex receptacle", "ea", 1.0); add("1-gang duplex wallplate", "ea", 1.0) }
                PartType.GFCI -> { add(deviceBox, "ea", 1.0); add("$amps GFCI receptacle", "ea", 1.0); add("1-gang decorator wallplate", "ea", 1.0) }
                PartType.SWITCH -> { add(deviceBox, "ea", 1.0); add("15A single-pole switch", "ea", 1.0); add("1-gang toggle wallplate", "ea", 1.0) }
                PartType.SWITCH3 -> { add(deviceBox, "ea", 1.0); add("15A 3-way switch", "ea", 1.0); add("1-gang toggle wallplate", "ea", 1.0) }
                PartType.LIGHT -> add(if (oldWork) "Fan-rated old work ceiling box" else "Fan-rated ceiling box", "ea", 1.0)
                PartType.WAFER -> add("LED wafer light, 6 in", "ea", 1.0)
                PartType.JBOX -> { add(if (conduitAt(p)) "4 in square box, 2-1/8 in deep" else "4 in square box, 1-1/2 in deep", "ea", 1.0); add("4 in square blank cover", "ea", 1.0) }
                PartType.PANEL -> { add("${if (gaugeAt(p) <= 12) "20" else "15"}A 1-pole breaker", "ea", 1.0, "Check AFCI/GFCI need for the room") }
            }
            // Splices: anywhere two or more cables meet, plus every light and junction box.
            val cables = cablesAt(p)
            val nuts = when {
                p.type == PartType.PANEL -> 0
                p.type == PartType.LIGHT || p.type == PartType.JBOX || p.type == PartType.WAFER -> 3
                cables >= 2 -> 3
                else -> 0
            }
            if (nuts > 0) add("Twist-on wire connectors, yellow (wire nuts)", "ea", nuts.toDouble())
        }
        if (parts.any { it.type == PartType.LIGHT }) notes += "Light fixtures aren't included. Add the fixture if you're supplying it."
        if (parts.none { it.type == PartType.PANEL } && runs.isNotEmpty()) notes += "No panel/start point placed. Add the home run feet to the panel if it's a new circuit."
        if (holes > 0) notes += "$holes stud ${if (holes == 1) "hole" else "holes"} to drill (cutaway, 16 in on center)."
        return Takeoff(out.values.toList(), feet, holes, notes)
    }
}
