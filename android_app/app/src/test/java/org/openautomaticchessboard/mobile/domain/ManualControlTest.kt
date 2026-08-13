package org.openautomaticchessboard.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openautomaticchessboard.mobile.protocol.Telemetry

class ManualControlTest {
    @Test fun headOnlyNeedsOneSquareAndNeverBuildsPieceCommand() {
        val result = ManualSelection().choose(28, setOf(28))
        assertEquals("HEAD e4", result.selection.command())
        assertNull(result.selection.source)
    }

    @Test fun pieceModeRequiresOccupiedSourceAndEmptyTarget() {
        val occupied = setOf(12, 48)
        val emptyRejected = ManualSelection(ManualMoveMode.MOVE_PIECE).choose(20, occupied)
        assertNull(emptyRejected.selection.source)
        val source = emptyRejected.selection.choose(12, occupied).selection
        assertEquals(12, source.source)
        assertNull(source.choose(48, occupied).selection.target)
        assertEquals("PIECE e2e4", source.choose(28, occupied).selection.command())
    }

    @Test fun verificationRequiresExactCalibrationAndPhysicalOccupancyTransition() {
        val telemetry = Telemetry("ACB2", 1, true, false, false, false, 5, 6, true, true, 1023, 700, 10)
        assertTrue(ManualVerification.calibrationMatches("e6", telemetry))
        assertFalse(ManualVerification.calibrationMatches("e5", telemetry))
        assertTrue(ManualVerification.pieceMoveMatches(12, 28, setOf(28)))
        assertFalse(ManualVerification.pieceMoveMatches(12, 28, setOf(12)))
    }

    @Test fun carriagePositionIsHiddenAfterHaltOrLossOfHoming() {
        val trusted = Telemetry("ACB2", 1, true, false, false, false, 5, 6, true, true, 1023, 700, 10)
        assertTrue(ManualVerification.positionIsTrusted(trusted))
        assertEquals(4 to 5, ManualVerification.trustedPosition(trusted))

        listOf(
            trusted.copy(homed = false),
            trusted.copy(motionFault = true),
            trusted.copy(trolleyX = 0),
        ).forEach { untrusted ->
            assertFalse(ManualVerification.positionIsTrusted(untrusted))
            assertNull(ManualVerification.trustedPosition(untrusted))
        }
    }
}
