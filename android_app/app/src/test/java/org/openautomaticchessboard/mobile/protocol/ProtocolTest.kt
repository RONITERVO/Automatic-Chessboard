package org.openautomaticchessboard.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test fun reassemblesSplitBleLines() {
        val buffer = LineBuffer()
        assertTrue(buffer.feed("MOVE e2".toByteArray()).isEmpty())
        assertEquals(listOf("MOVE e2e4"), buffer.feed("e4\r\nTURN HU".toByteArray()))
        assertEquals(listOf("TURN HUMAN"), buffer.feed("MAN\n".toByteArray()))
    }

    @Test fun filtersNonPrintableBytesAndDiscardsOverflowedLines() {
        val buffer = LineBuffer(maximum = 8)
        assertEquals(listOf("PING"), buffer.feed(byteArrayOf(1) + "PING\n".toByteArray() + byteArrayOf(127)))
        assertTrue(buffer.feed("123456789discard".toByteArray()).isEmpty())
        assertEquals(listOf("INFO"), buffer.feed("\nINFO\n".toByteArray()))
    }

    @Test fun parsesInfoTelemetryAndBoard() {
        val info = Protocol.parseInfo(Protocol.parseEvent("INFO ACB2 3.29 BOARD,TELEM,REMOTE,ESTOP"))
        assertEquals("3.29", info.firmware)
        assertTrue("ESTOP" in info.capabilities)
        val current = Protocol.parseInfo(Protocol.parseEvent("INFO ACB2 3.31 BOARD,TELEM,MANUAL,SENSORFRAME"))
        assertTrue("SENSORFRAME" in current.capabilities)
        val telemetry = Protocol.parseTelemetry(Protocol.parseEvent("TELEM ACB2 17 1 1 0 0 5 6 1 1 1023 847 65"))
        assertEquals(17, telemetry.sequence)
        assertTrue(telemetry.homed)
        assertEquals(1023, telemetry.buttonBRaw)
        val squares = setOf(0, 7, 8, 55, 56, 63)
        val firmwareVector = "8180000000000181"
        assertEquals(firmwareVector, Protocol.boardHexFromSquares(squares))
        assertEquals(squares, Protocol.parseBoardHex(firmwareVector))
        assertThrows(IllegalArgumentException::class.java) { Protocol.parseBoardHex("bad") }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.parseTelemetry(Protocol.parseEvent("TELEM ACB2 bad 1 1 0 0 5 6 1 1 1023 847 65"))
        }
    }

    @Test fun classifiesAndBuildsGuardedCommands() {
        assertEquals(CommandRisk.READ_ONLY, Protocol.classifyCommand("TELEM"))
        assertEquals(CommandRisk.READ_ONLY, Protocol.classifyCommand("SWTEST"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("PLAY e2e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("CALIBRATE"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("HEAD e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("PIECE e2e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("PATH e2e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("JOG W+"))
        assertEquals(CommandRisk.CONTROL, Protocol.classifyCommand("STOP"))
        assertEquals(CommandRisk.EMERGENCY, Protocol.classifyCommand("!"))
        assertEquals(CommandRisk.UNKNOWN, Protocol.classifyCommand("MOTOR 1"))
        assertEquals("PLAY e1g1 C", Protocol.playCommand("e1g1", castling = true))
        assertEquals("PLAY e5d6 E", Protocol.playCommand("e5d6", enPassant = true))
        assertThrows(IllegalArgumentException::class.java) { Protocol.playCommand("z9z8") }
        assertEquals("HEAD e6", Protocol.headCommand("e6"))
        assertEquals("PIECE e2e4", Protocol.pieceCommand("e2", "e4"))
    }

    @Test fun telemetryPreservesReleasedAndFaultFlags() {
        val value = Protocol.parseTelemetry(Protocol.parseEvent("TELEM ACB2 10 0 1 1 0 -1 -1 0 1 100 500 9"))
        assertTrue(value.motionFault)
        assertFalse(value.buttonAReleased)
        assertEquals(-1, value.trolleyX)
    }
}
