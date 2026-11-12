package cn.edu.usst.jwgl.ui.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWeekCardLayoutTest {

    @Test
    fun `two-node card fits five lines when height allows`() {
        // Typical 2-node card: cardHeight=360px, vPad=11px each side, lineHeight=42px -> (360-22)/42 = 8 lines
        val maxLines = ScheduleWeekFragment.computeCardMaxLines(
            cardHeight = 360,
            verticalPadding = 11,
            lineHeightPx = 42
        )
        assertEquals(8, maxLines)
        assertTrue(maxLines >= 5)
    }

    @Test
    fun `very short card enforces minimum of two lines`() {
        val maxLines = ScheduleWeekFragment.computeCardMaxLines(
            cardHeight = 40,
            verticalPadding = 8,
            lineHeightPx = 40
        )
        assertEquals(2, maxLines)
    }
}
