package org.openautomaticchessboard.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardOffsetCalibrationTest {
    @Test fun profileProducesPortableHumanAnswer() {
        val profile = BoardOffsetProfile.fromEvent(listOf("354", "871"))
        assertEquals("Black offset 354; white offset 871", profile.answer)
        assertEquals("CALSET 354 871", profile.command)
    }

    @Test fun profileAndNudgesAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { BoardOffsetProfile(199, 871) }
        assertEquals("NUDGE X+ 1", nudgeCommand('x', true, false))
        assertEquals("NUDGE Y- 5", nudgeCommand('Y', false, true))
    }
}
