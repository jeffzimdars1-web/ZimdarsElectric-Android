package com.zimdarselectric.app.data

import com.zimdarselectric.app.data.JobBuild.Part
import com.zimdarselectric.app.data.JobBuild.PartType
import com.zimdarselectric.app.data.JobBuild.Run
import com.zimdarselectric.app.data.JobBuild.RunType
import com.zimdarselectric.app.data.JobBuild.V3
import com.zimdarselectric.app.data.JobBuild.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobBuildTest {
    private val inch = 0.0254
    // Wall faces +z; studs along x. A stud center at x = 0.
    private val wall = Wall(V3(0.0, 0.0, 0.0), V3(0.0, 0.0, 1.0))
    private fun line(t: JobBuild.Takeoff, name: String) = t.lines.firstOrNull { it.name == name }

    @Test fun receptacleToSwitch_romex() {
        // Receptacle at 16" high, switch 6 ft over and 48" high.
        val rec = Part(1, PartType.RECEPTACLE, V3(0.2, 16 * inch, 0.0))
        val sw = Part(2, PartType.SWITCH, V3(0.2 + 72 * inch, 48 * inch, 0.0))
        val t = JobBuild.takeoff(listOf(rec, sw), listOf(Run(1, 2, RunType.NM14_2)), wall)
        // 6 ft level + 32 in up = 8.67 ft, + 1 ft slack each end = 10.67 → 11 ft
        assertEquals(11.0, line(t, "14/2 NM-B cable w/ground")!!.qty, 0.001)
        assertEquals(1.0, line(t, "15A tamper-resistant duplex receptacle")!!.qty, 0.001)
        assertEquals(1.0, line(t, "15A single-pole switch")!!.qty, 0.001)
        assertEquals(2.0, line(t, "1-gang old work box, plastic")!!.qty, 0.001)
        // Studs at 16, 32, 48, 64 in from x=0 lie between 7.9 in and 79.9 in → 5 studs (16,32,48,64,... 80 is past) = 4? 16,32,48,64 = 4
        assertEquals(4, t.holes)
        assertTrue(line(t, "NM cable staples") == null) // old work: fished, no staples
    }

    @Test fun twelveGauge_makes20A_and_newWorkStaples() {
        val a = Part(1, PartType.RECEPTACLE, V3(0.0, 0.4, 0.0))
        val b = Part(2, PartType.GFCI, V3(3.0, 0.4, 0.0))
        val t = JobBuild.takeoff(listOf(a, b), listOf(Run(1, 2, RunType.NM12_2)), null, oldWork = false)
        assertTrue(line(t, "20A tamper-resistant duplex receptacle") != null)
        assertTrue(line(t, "20A GFCI receptacle") != null)
        // 9.84 ft → staples ceil(9.84/4.5)=3 + 2 = 5
        assertEquals(5.0, line(t, "NM cable staples")!!.qty, 0.001)
        assertEquals(2.0, line(t, "1-gang new work box, plastic")!!.qty, 0.001)
    }

    @Test fun emtRun_fittings() {
        val j = Part(1, PartType.JBOX, V3(0.0, 2.4, 0.0))
        val r = Part(2, PartType.RECEPTACLE, V3(7.0, 1.0, 0.0)) // 22.97 ft level + 4.59 ft drop
        val t = JobBuild.takeoff(listOf(j, r), listOf(Run(1, 2, RunType.EMT12_12)), null)
        val ft = (7.0 + 1.4) * JobBuild.FT // 27.56
        assertEquals(3.0, line(t, "1/2 in EMT conduit, 10 ft")!!.qty, 0.001)
        assertEquals(2.0, line(t, "1/2 in EMT set-screw coupling")!!.qty, 0.001)
        assertEquals(2.0, line(t, "1/2 in EMT set-screw connector")!!.qty, 0.001)
        assertEquals(4.0, line(t, "1/2 in one-hole strap")!!.qty, 0.001)
        assertEquals(1.0, line(t, "1/2 in EMT 90° elbow")!!.qty, 0.001)
        assertEquals(kotlin.math.ceil(ft + 2), line(t, "12 AWG THHN copper, black")!!.qty, 0.001)
        assertTrue(line(t, "1-gang steel device box") != null)
        assertTrue(line(t, "4 in square box, 2-1/8 in deep") != null)
    }

    @Test fun ceilingLight_dropsFirst_andGetsWireNuts() {
        val sw = Part(1, PartType.SWITCH, V3(0.0, 1.2, 0.0))
        val light = Part(2, PartType.LIGHT, V3(2.0, 2.4, 1.5))
        val pts = JobBuild.path(light, sw)
        assertEquals(3, pts.size)
        assertEquals(1.2, pts[1].y, 1e-9) // drops to switch height, then level
        val t = JobBuild.takeoff(listOf(sw, light), listOf(Run(1, 2, RunType.NM14_2)), null)
        assertEquals(3.0, line(t, "Twist-on wire connectors, yellow (wire nuts)")!!.qty, 0.001)
        assertTrue(t.notes.any { it.contains("fixture") })
    }

    @Test fun studsBetween_isInclusive() {
        assertEquals(listOf(0.0, JobBuild.STUD_OC_M), wall.studsBetween(-0.1, 0.5))
        assertEquals(0, JobBuild.studHoles(listOf(V3(0.0, 0.0, 1.0), V3(2.0, 0.0, 1.0)), wall)) // 1 m off the wall
    }
}
