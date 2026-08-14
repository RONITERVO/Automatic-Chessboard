package org.openautomaticchessboard.mobile.domain.routing

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {
    private fun planner(
        maxTemporaryPieces: Int = 4,
        timeLimitMillis: Long = 5_000,
    ) = RearrangementPlanner(
        PlannerConfig(
            timeLimitMillis = timeLimitMillis,
            maxNodes = 150_000,
            maxTemporaryPieces = maxTemporaryPieces,
            parkingCandidates = 8,
            corridorCandidates = 6,
            dependencyDepth = 5,
        ),
    )

    @Test fun directAndTurningRoutesCountPhysicalPickups() {
        val direct = planner().plan(
            PlanningProblem(listOf(PieceTask("main", parseSquare("a1"), parseSquare("a3"), true)), "a1a3"),
        )
        direct.validate()
        assertEquals(1, direct.dragCount)
        assertEquals(1, direct.pickupCount)

        val turning = planner().plan(
            PlanningProblem(listOf(PieceTask("main", parseSquare("a1"), parseSquare("b2"), true)), "a1b2"),
        )
        turning.validate()
        assertEquals(2, turning.dragCount)
        assertEquals(2, turning.pickupCount)
        assertEquals(2, turning.protocolCommands().count { it.startsWith("DRAG ") })
    }

    @Test fun planAEvacuatesAndRestoresOneBarrier() {
        val pieces = mutableListOf(PieceTask("main", parseSquare("a1"), parseSquare("a8"), true))
        "abcdefgh".forEach { file ->
            pieces += PieceTask("barrier-$file", parseSquare("${file}4"), parseSquare("${file}4"))
        }
        val plan = planner(2).plan(PlanningProblem(pieces, "a1a8"))
        plan.validate()
        assertEquals(1, plan.temporaryPieceCount)
        assertTrue(plan.relocations.any { it.purpose == "evacuate" })
        assertTrue(plan.relocations.any { it.purpose == "restore" })
    }

    @Test fun planBStagesPrimaryUntilBlockerReturns() {
        val problem = PlanningProblem(
            listOf(
                PieceTask("main", parseSquare("a3"), parseSquare("a3"), true),
                PieceTask("blocker", parseSquare("a4"), parseSquare("a2")),
                PieceTask("wall-b1", parseSquare("b1"), parseSquare("b1")),
                PieceTask("wall-b2", parseSquare("b2"), parseSquare("b2")),
                PieceTask("other-primary", parseSquare("h1"), parseSquare("h2"), true),
            ),
            "h1h2",
        )
        val plan = planner(1).plan(problem)
        plan.validate()
        val stage = plan.relocations.indexOfFirst { it.pieceKey == "main" && it.purpose == "stage" }
        val blockerRestore = plan.relocations.indexOfFirst {
            it.pieceKey == "blocker" && it.target == parseSquare("a2")
        }
        val mainReturn = plan.relocations.indexOfLast {
            it.pieceKey == "main" && it.target == parseSquare("a3")
        }
        assertTrue(stage >= 0 && stage < blockerRestore && blockerRestore < mainReturn)
    }

    @Test fun planCRecursivelyFreesTrappedPiece() {
        val problem = PlanningProblem(
            listOf(
                PieceTask("trapped", parseSquare("a1"), parseSquare("a3")),
                PieceTask("secondary-c", parseSquare("a2"), parseSquare("a2")),
                PieceTask("secondary-d", parseSquare("b1"), parseSquare("b1")),
                PieceTask("primary", parseSquare("h1"), parseSquare("h2"), true),
            ),
            "h1h2",
        )
        val plan = planner(2).plan(problem)
        plan.validate()
        val trapped = plan.relocations.indexOfFirst { it.pieceKey == "trapped" }
        assertTrue(trapped > 0)
        assertTrue(plan.relocations[trapped - 1].pieceKey in setOf("secondary-c", "secondary-d"))
        assertEquals(2, plan.temporaryPieceCount)
    }

    @Test fun snapshotMismatchFailsBeforeSearch() {
        val problem = PlanningProblem(
            listOf(PieceTask("main", parseSquare("a1"), parseSquare("a2"), true)),
            "a1a2",
            initialPhysicalOccupancy = setOf(parseSquare("h8")),
        )
        assertThrows(PlanningException::class.java) { planner().plan(problem) }
    }

    @Test fun deferredCaptureClearsBinLaneBeforeRemoval() {
        val board = Board()
        listOf("e2e4", "d7d5", "e4e5", "e7e6", "a2a4", "b8c6", "f2f4").forEach { uci ->
            board.doMove(Move(uci, board.sideToMove), true)
        }
        val move = Move("c6e5", Side.BLACK)
        val problem = planningProblemFromChess(
            board,
            move,
            (0 until 64).filter { board.getPiece(Square.values()[it]) != Piece.NONE }.toSet(),
            deferredCapture = true,
        )

        // This legacy full-board rearrangement intentionally exercises the
        // exhaustive 4.7 lane model. Keep cold, shared CI runners from turning
        // its wall-clock guard into a flaky correctness test.
        val plan = planner(4, timeLimitMillis = 15_000).plan(problem)

        plan.validate()
        val commands = plan.protocolCommands()
        assertTrue("REMOVE must be present in a deferred capture plan", "REMOVE" in commands)
        val removeIndex = commands.indexOf("REMOVE")
        assertTrue(commands.subList(2, removeIndex).any { it.startsWith("DRAG ") })
        assertEquals("BOARD", commands[removeIndex + 1])
        assertEquals(1, plan.captureRemovalIndex)
    }

    @Test fun edgeExitRoutesCaptureWithoutMovingUnrelatedA4() {
        val board = Board()
        listOf("e2e4", "d7d5", "e4e5", "e7e6", "a2a4", "b8c6", "f2f4").forEach { uci ->
            board.doMove(Move(uci, board.sideToMove), true)
        }
        val move = Move("c6e5", Side.BLACK)
        val problem = planningProblemFromChess(
            board,
            move,
            (0 until 64).filter { board.getPiece(Square.values()[it]) != Piece.NONE }.toSet(),
            deferredCapture = true,
            edgeCaptureExit = true,
        )

        val plan = planner(4).plan(problem)

        plan.validate()
        assertEquals(0, plan.temporaryPieceCount)
        assertEquals(0, plan.captureRemovalIndex)
        assertEquals(parseSquare("e5"), plan.capturePath.first())
        assertEquals(parseSquare("a3"), plan.capturePath.last())
        val commands = plan.protocolCommands()
        assertFalse("DRAG a4a5" in commands)
        assertTrue("The edge-lane drag must be present", "DRAG e3a3" in commands)
        assertTrue("REMOVE must be present in a deferred capture plan", "REMOVE" in commands)
        assertTrue(commands.indexOf("DRAG e3a3") < commands.indexOf("REMOVE"))
    }

    @Test fun edgeExitUsesAWindingEmptyRouteBeforeDisturbingPieces() {
        val fixed = listOf(
            "a1", "a2", "a3", "a4", "a5", "a7", "a8",
            "b1", "b2", "b3", "b4", "b7", "b8",
        )
        val pieces = mutableListOf(PieceTask("main", parseSquare("h8"), parseSquare("h7"), true))
        fixed.forEach { name ->
            pieces += PieceTask("fixed-$name", parseSquare(name), parseSquare(name))
        }
        val problem = PlanningProblem(
            pieces,
            "h8h7",
            capturedSquare = parseSquare("e5"),
            deferredCapture = true,
            edgeCaptureExit = true,
        )

        val plan = planner(0).plan(problem)

        plan.validate()
        assertEquals(0, plan.temporaryPieceCount)
        assertEquals(
            listOf("e5", "e6", "d6", "c6", "b6", "a6").map(::parseSquare),
            plan.capturePath,
        )
    }

    @Test fun chessAdapterHandlesCaptureEnPassantPromotionAndCastling() {
        val captureBoard = Board()
        captureBoard.doMove(Move("e2e4", Side.WHITE), true)
        captureBoard.doMove(Move("d7d5", Side.BLACK), true)
        val capture = planningProblemFromChess(captureBoard, Move("e4d5", Side.WHITE))
        assertEquals(Square.D5.ordinal, capture.capturedSquare)
        assertTrue(capture.pieces.none { it.start == Square.D5.ordinal })

        val enPassantBoard = Board()
        listOf("e2e4", "a7a6", "e4e5", "d7d5").forEach { uci ->
            enPassantBoard.doMove(Move(uci, enPassantBoard.sideToMove), true)
        }
        val enPassant = planningProblemFromChess(enPassantBoard, Move("e5d6", Side.WHITE))
        assertEquals(Square.D5.ordinal, enPassant.capturedSquare)

        val promotionBoard = Board().apply { loadFromFen("8/P7/8/8/8/8/8/4K2k w - - 0 1") }
        val promotion = planningProblemFromChess(promotionBoard, Move("a7a8q", Side.WHITE))
        assertEquals("PLAN a7a8q--", planner().plan(promotion).protocolCommands().first())

        val castlingBoard = Board().apply { loadFromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1") }
        val castling = planningProblemFromChess(castlingBoard, Move("e1g1", Side.WHITE))
        assertEquals("kingside", castling.castlingSide)
        assertEquals(
            mapOf(Square.E1.ordinal to Square.G1.ordinal, Square.H1.ordinal to Square.F1.ordinal),
            castling.pieces.filter { it.primary }.associate { it.start to it.goal },
        )
        assertEquals("PLAN e1g1k--", planner().plan(castling).protocolCommands().first())
    }
}
