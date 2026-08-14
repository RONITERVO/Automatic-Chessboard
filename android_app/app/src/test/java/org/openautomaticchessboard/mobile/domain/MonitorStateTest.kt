package org.openautomaticchessboard.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openautomaticchessboard.mobile.protocol.FirmwareInfo
import org.openautomaticchessboard.mobile.protocol.Telemetry

class MonitorStateTest {
    @Test fun mismatchIsVisibleAndActionable() {
        val state = MonitorState(
            connected = true, lastSeenMs = 10_000,
            firmware = FirmwareInfo("ACB2", "3.29", "NANO", setOf("BOARD")),
            sensorSquares = (MonitorState.initialOccupancy - 12) + 28,
            expectedSquares = MonitorState.initialOccupancy,
        )
        assertEquals(setOf(12), state.missingSquares())
        assertEquals(setOf(28), state.unexpectedSquares())
        assertEquals(HealthLevel.WARN, state.health(10_100).second)
        assertTrue(state.guidance().contains("differ"))
    }

    @Test fun faultOutranksMismatch() {
        val telemetry = Telemetry(
            protocol = "ACB2", sequence = 10, homed = false, remoteMode = true,
            motionFault = true, magnetOn = false, trolleyX = 0, trolleyY = 0,
            buttonAReleased = true, buttonBReleased = true, buttonBRaw = 1023,
            freeRam = 800, uptimeSeconds = 3,
        )
        val state = MonitorState(connected = true, lastSeenMs = 1_000, telemetry = telemetry)
        assertEquals("Motion fault", state.health(1_100).first)
        assertTrue(state.guidance().contains("physical motor power"))
    }

    @Test fun staleDataIsNeverCalledReady() {
        val state = MonitorState(connected = true, lastSeenMs = 1_000)
        assertEquals("Connection stale", state.health(20_000).first)
    }

    @Test fun expectedMotionExplainsSuppressedPolling() {
        val state = MonitorState(connected = true, lastSeenMs = 1_000, motionExpected = true)
        assertEquals("Motion in progress", state.health(20_000).first)
        assertEquals(HealthLevel.WARN, state.health(20_000).second)
    }

    @Test fun freshHealthyStateIsReady() {
        val now = 10_000L
        val state = MonitorState(
            connected = true,
            lastSeenMs = now,
            firmware = FirmwareInfo("ACB3", "5.0.1", "NANO", setOf("TELEM", "BOARD")),
            telemetry = Telemetry(
                protocol = "ACB2", sequence = 1, homed = false, remoteMode = false,
                motionFault = false, magnetOn = false, trolleyX = 8, trolleyY = 1,
                buttonAReleased = true, buttonBReleased = true, buttonBRaw = 1023,
                freeRam = 728, uptimeSeconds = 42,
            ),
            sensorSquares = MonitorState.initialOccupancy,
            sensorUpdatedMs = now,
        )
        assertEquals("Ready", state.health(now).first)
        assertEquals(HealthLevel.GOOD, state.health(now).second)
    }

    @Test fun staleSensorsAndErrorsAreNeverReady() {
        val now = 20_000L
        val base = MonitorState(
            connected = true, lastSeenMs = now,
            firmware = FirmwareInfo("ACB3", "5.0.1", "NANO", setOf("BOARD")),
            sensorSquares = MonitorState.initialOccupancy,
            sensorUpdatedMs = 1_000L,
        )
        assertEquals("Sensor data stale", base.health(now).first)
        assertEquals(HealthLevel.WARN, base.copy(sensorUpdatedMs = now, lastError = "sample error").health(now).second)
    }

    @Test fun currentFirmwareStatesHaveStableNames() {
        fun state(sequence: Int) = MonitorState(
            telemetry = Telemetry(
                "ACB2", sequence, true, false, false, false, 5, 6,
                true, true, 1023, 800, 1,
            ),
        ).sequenceName()
        assertEquals("Board alignment", state(12))
        assertEquals("Direct app movement", state(19))
        assertEquals("Verified route transaction", state(20))
        assertEquals("Unknown state 22", state(22))
    }
}
