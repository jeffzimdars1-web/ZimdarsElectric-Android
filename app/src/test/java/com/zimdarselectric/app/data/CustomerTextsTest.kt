package com.zimdarselectric.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CustomerTextsTest {
    private val start = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 31, 10, 0, 0) }.timeInMillis
    private val s = Settings(ownerName = "Jeff", phone = "(315) 555-0100")
    private val c = Customer(id = 1, name = "Pat Sample", phone = "315-555-0199")

    @Test fun eveningBefore() {
        val m = CustomerTexts.message(Quote(number = "Q-1", startAt = start, site = "12 Main St"), c, s, nightBefore = true)
        assertEquals("Zimdars Electric LLC appointment reminder: Tomorrow, Saturday, October 31, arrival at 10:00 AM (arrival window 9:45 AM to 10:15 AM) at 12 Main St. To reschedule, call or text (315) 555-0100.", m)
    }

    @Test fun hourBefore() {
        val m = CustomerTexts.message(Quote(number = "Q-1", startAt = start), c, s, nightBefore = false)
        assertEquals("Zimdars Electric LLC: Jeff is scheduled to arrive at about 10:00 AM (arrival window 9:45 AM to 10:15 AM). See you soon.", m)
    }

    @Test fun noSetTime() {
        val m = CustomerTexts.message(Quote(number = "Q-1", startAt = start, hasTime = false), c, s, nightBefore = true)
        assertTrue(m, m.contains("We will text you when we are on the way."))
    }

    @Test fun confirmationLink() {
        val m = CustomerTexts.confirmation(Quote(number = "Q-1", startAt = start), s, "https://x/appt/?t=abc")
        assertEquals("Zimdars Electric LLC: Your service appointment is scheduled for Saturday, October 31 at 10:00 AM. Please review and confirm here: https://x/appt/?t=abc", m)
    }

    @Test fun onMyWay() {
        assertEquals("Zimdars Electric LLC: Jeff is on the way and expects to arrive in about 18 minutes.", CustomerTexts.onMyWay(Quote(number = "Q-1"), c, s, 18))
    }
}
