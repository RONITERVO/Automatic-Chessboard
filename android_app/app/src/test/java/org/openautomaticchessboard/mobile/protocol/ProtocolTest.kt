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
        val info = Protocol.parseInfo(Protocol.parseEvent("INFO ACB3 5.0.1 NANO"))
        assertEquals("5.0.1", info.firmware)
        assertTrue(info.compatible)
        assertTrue("ESTOP" in info.capabilities)
        assertEquals(
            "MKS_GEN_L_V1",
            Protocol.parseInfo(Protocol.parseEvent("INFO ACB3 5.0.1 MKS_GEN_L_V1")).hardware,
        )
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.parseInfo(Protocol.parseEvent("INFO ACB2 4.8.0 BOARD,TELEM"))
        }
        val telemetry = Protocol.parseTelemetry(Protocol.parseEvent("TELEM ACB3 17 1 1 0 0 5 6 1 1 1023 847 65"))
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
        assertEquals(CommandRisk.UNKNOWN, Protocol.classifyCommand("PLAY e2e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("CALIBRATE"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("HEAD e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("PIECE e2e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("PATH e2e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("JOG W+"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("PLAN e2e4---"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("DRAG e2e4"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("REMOVE"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("COMMIT"))
        assertEquals(CommandRisk.READ_ONLY, Protocol.classifyCommand("GEOMETRY"))
        assertEquals(CommandRisk.READ_ONLY, Protocol.classifyCommand("ALIGN STATUS"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("ALIGN a2 H"))
        assertEquals(CommandRisk.MOTION, Protocol.classifyCommand("NUDGE X+"))
        assertEquals(CommandRisk.CONTROL, Protocol.classifyCommand("STOP"))
        assertEquals(CommandRisk.EMERGENCY, Protocol.classifyCommand("!"))
        assertEquals(CommandRisk.UNKNOWN, Protocol.classifyCommand("MOTOR 1"))
        assertEquals("START W", Protocol.startGameCommand(humanWhite = true))
        assertEquals("START B APP", Protocol.startGameCommand(humanWhite = false, appBoard = true))
        assertEquals("HELLO 5.0.1", Protocol.helloCommand())
        assertEquals("HEAD e6", Protocol.headCommand("e6"))
        assertEquals("PIECE e2e4", Protocol.pieceCommand("e2", "e4"))
        assertEquals("ALIGN h7 H", Protocol.alignmentCommand("h7"))
        assertEquals("ALIGN h7 M", Protocol.alignmentCommand("H7", magneticMarker = true))
        assertEquals("NUDGE X+", Protocol.nudgeCommand('x', '+'))
    }

    @Test fun parsesGeometryAndAlignmentStatus() {
        val geometry = Protocol.parseGeometry(
            Protocol.parseEvent("GEOMETRY ACB1 188 189 354 871 1"),
        )
        assertEquals(188, geometry.filePitch)
        assertEquals(189, geometry.rankPitch)
        assertEquals(354, geometry.blackPark)
        assertEquals(871, geometry.whitePark)
        assertEquals(1, geometry.microsteps)
        val active = Protocol.parseAlignment(Protocol.parseEvent("ALIGN ACTIVE a2 M -3 7"))
        assertEquals("a2", active.square)
        assertTrue(active.magneticMarker)
        assertEquals(-3, active.offsetX)
        assertEquals(7, active.offsetY)
        assertEquals("IDLE", Protocol.parseAlignment(Protocol.parseEvent("ALIGN IDLE")).state)
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.parseAlignment(Protocol.parseEvent("ALIGN ACTIVE a2 M 61 0"))
        }
    }

    @Test fun buildsAndParsesRouteTransactions() {
        assertEquals("PLAN e2e4---", Protocol.planCommand("e2e4"))
        assertEquals("PLAN a7a8q--", Protocol.planCommand("a7a8q"))
        assertEquals("PLAN e1g1k--", Protocol.planCommand("e1g1", castlingSide = "kingside"))
        assertEquals("PLAN e5d6-d5", Protocol.planCommand("e5d6", Protocol.squareIndex("d5")))
        assertEquals("REMOVE", Protocol.removeCommand(Protocol.squareIndex("d5")))

        val request = Protocol.parsePlanCommand("PLAN e5d6-d5")
        assertEquals("e5d6", request.uci)
        assertEquals('-', request.mode)
        assertEquals(Protocol.squareIndex("d5"), request.captureSquare)

        val path = listOf(
            Protocol.squareIndex("a1"),
            Protocol.squareIndex("a2"),
            Protocol.squareIndex("b2"),
        )
        assertEquals(2, Protocol.splitRouteRuns(path).size)
        assertThrows(IllegalArgumentException::class.java) { Protocol.dragCommand(path) }
        assertEquals("DRAG a1a2", Protocol.dragCommand(path.take(2)))
        val drag = Protocol.parseDragCommand("DRAG a1a4")
        assertEquals(Protocol.squareIndex("a1"), drag.source)
        assertEquals(Protocol.squareIndex("a4"), drag.target)
        assertEquals(4, drag.path.size)
        assertThrows(IllegalArgumentException::class.java) { Protocol.parseDragCommand("DRAG a1b2") }
        assertThrows(IllegalStateException::class.java) { Protocol.splitRouteRuns(listOf(7, 8)) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.parsePlanCommand("PLAN e2e4--") }
        assertThrows(IllegalArgumentException::class.java) { Protocol.parsePlanCommand("PLAN e2e4x--") }
        assertThrows(IllegalArgumentException::class.java) { Protocol.parsePlanCommand("PLAN e1c1k--") }
        assertThrows(IllegalArgumentException::class.java) { Protocol.parsePlanCommand("PLAN e1g1c--") }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.planCommand("e1c1", castlingSide = "kingside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.planCommand("e8g8", castlingSide = "queenside")
        }
    }

    @Test fun telemetryPreservesReleasedAndFaultFlags() {
        val value = Protocol.parseTelemetry(Protocol.parseEvent("TELEM ACB2 10 0 1 1 0 -1 -1 0 1 100 500 9"))
        assertTrue(value.motionFault)
        assertFalse(value.buttonAReleased)
        assertEquals(-1, value.trolleyX)
    }

    @Test fun directCarriesAreQueenAligned() {
        assertTrue(Protocol.queenAligned(0, 7))
        assertTrue(Protocol.queenAligned(0, 56))
        assertTrue(Protocol.queenAligned(0, 63))
        assertFalse(Protocol.queenAligned(1, 18))
        assertFalse(Protocol.queenAligned(0, 0))
    }
}
