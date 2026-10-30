package com.zimdarselectric.app.data

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** EMT hand-bender math: stubs, offsets, saddles, rolling offsets. All inches. */
object Bends {
    /** Stub-up take-up (deduct) for common hand benders. */
    val takeUp = mapOf("1/2" to 5.0, "3/4" to 6.0, "1" to 8.0, "1-1/4" to 11.0)

    /** Offset angle → distance multiplier and shrink per inch of offset depth. */
    data class Angle(val deg: String, val multiplier: Double, val shrinkPerInch: Double)
    val angles = listOf(
        Angle("10°", 6.0, 1.0 / 16), Angle("22.5°", 2.6, 3.0 / 16), Angle("30°", 2.0, 1.0 / 4),
        Angle("45°", 1.4, 3.0 / 8), Angle("60°", 1.2, 1.0 / 2),
    )

    /** "12-3/8\"" rounded to the nearest 1/16. */
    fun inches(v: Double): String {
        val sixteenths = (v * 16).roundToInt()
        val whole = sixteenths / 16; var n = sixteenths % 16; var d = 16
        while (n != 0 && n % 2 == 0) { n /= 2; d /= 2 }
        return when { n == 0 -> "$whole\""; whole == 0 -> "$n/$d\""; else -> "$whole-$n/$d\"" }
    }

    data class Stub(val mark: Double)
    /** Mark the pipe at stub height minus take-up, line the mark up with the arrow, bend to 90. */
    fun stub(height: Double, size: String) = Stub(height - (takeUp[size] ?: 5.0))

    data class Offset(val between: Double, val shrink: Double, val firstMark: Double)
    /** [distance] = from the end of the pipe to the obstruction. */
    fun offset(depth: Double, angle: Angle, distance: Double): Offset {
        val shrink = depth * angle.shrinkPerInch
        return Offset(depth * angle.multiplier, shrink, distance + shrink)
    }

    data class Saddle3(val center: Double, val outer1: Double, val outer2: Double, val shrink: Double)
    /** 45° center, two 22.5° outside bends 2.5 × depth from center; shrink 3/16" per inch. [distance] = to the center of the obstruction. */
    fun saddle3(depth: Double, distance: Double): Saddle3 {
        val shrink = depth * 3.0 / 16
        val c = distance + shrink
        return Saddle3(c, c - 2.5 * depth, c + 2.5 * depth, shrink)
    }

    data class Saddle4(val marks: List<Double>, val shrink: Double)
    /** Two offsets over an obstruction [width] wide, starting [distance] from the end to the near side. */
    fun saddle4(depth: Double, width: Double, angle: Angle, distance: Double): Saddle4 {
        val shrink = depth * angle.shrinkPerInch
        val m2 = distance + shrink
        val m1 = m2 - depth * angle.multiplier
        val m3 = m2 + width
        val m4 = m3 + depth * angle.multiplier
        return Saddle4(listOf(m1, m2, m3, m4), shrink * 2)
    }

    /** Rolling offset: the true offset from rise and roll (then bend it as a normal offset, rotated). */
    fun rolling(rise: Double, roll: Double) = sqrt(rise * rise + roll * roll)
}
