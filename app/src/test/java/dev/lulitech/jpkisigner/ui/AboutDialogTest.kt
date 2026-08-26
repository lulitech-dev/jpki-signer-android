package dev.lulitech.jpkisigner.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyrightYearsTest {

    @Test
    fun `shows a single year during the first year`() {
        assertEquals("2026", copyrightYears(firstYear = 2026, currentYear = 2026))
    }

    @Test
    fun `shows a range once the calendar moves on`() {
        assertEquals("2026-2027", copyrightYears(firstYear = 2026, currentYear = 2027))
        assertEquals("2026-2028", copyrightYears(firstYear = 2026, currentYear = 2028))
    }

    /** A device clock set into the past must not produce a reversed range. */
    @Test
    fun `a clock behind the first year still shows a single year`() {
        assertEquals("2026", copyrightYears(firstYear = 2026, currentYear = 2020))
    }
}
