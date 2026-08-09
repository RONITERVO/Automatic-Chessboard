package org.openautomaticchessboard.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openautomaticchessboard.mobile.protocol.FirmwareInfo
import org.openautomaticchessboard.mobile.protocol.Telemetry

class MonitorStateTest {
    @Test fun mismatchIsVisibleAndActionable() {
        val state = MonitorState(
            connected = true, lastSeenMs = 10_000, firmware = FirmwareInfo("ACB2", "3.29", setOf("BOARD")),
            sensorSquares = (MonitorState.initialOccupancy - 12) + 28,
            expectedSquares = MonitorState.initialOccupancy,
        )
        assertEquals(setOf(12), state.missingSquares())
        assertEquals(setOf(28), state.unexpectedSquares())
        assertEquals(HealthLevel.WARN, state.health(10_100).second)
        assertTrue(state.guidance().contains("differ"))
    }

    @Test fun faultOutranksMismatch() {
        val telemetry = Telemetry("ACB2", 10, false, true, true, false, 0, 0, true, true, 1023, 800, 3)
        val state = MonitorState(connected = true, lastSeenMs = 1_000, telemetry = telemetry)
        assertEquals("Motion fault", state.health(1_100).first)
        assertTrue(state.guidance().contains("physical motor power"))
    }

    @Test fun staleDataIsNeverCalledReady() {
        val state = MonitorState(connected = true, lastSeenMs = 1_000)
        assertEquals("Connection stale", state.health(20_000).first)
    }
}
