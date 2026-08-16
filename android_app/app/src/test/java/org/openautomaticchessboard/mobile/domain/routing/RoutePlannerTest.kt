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
import java.security.MessageDigest
import kotlin.random.Random

class RoutePlannerTest {
    private class XorShift64(seed: Long) {
        private var state = seed

        fun nextIndex(bound: Int): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return java.lang.Long.remainderUnsigned(state, bound.toLong()).toInt()
        }
    }

    private fun planner(
        maxTemporaryPieces: Int = 4,
        timeLimitMillis: Long = 5_000,
        maxNodes: Int = 150_000,
        exactSearch: Boolean = false,
        constructiveFallback: Boolean = true,
    ) = RearrangementPlanner(
        PlannerConfig(
            timeLimitMillis = timeLimitMillis,
            maxNodes = maxNodes,
            maxTemporaryPieces = maxTemporaryPieces,
            parkingCandidates = 8,
            corridorCandidates = 6,
            dependencyDepth = 5,
            exactSearch = exactSearch,
            constructiveFallback = constructiveFallback,
        ),
    )

    private fun physicalFenProblem(fen: String, uci: String): PlanningProblem {
        val board = Board().apply { loadFromFen(fen) }
        val move = Move(uci, board.sideToMove)
        val pieces = (0 until 64).mapNotNull { square ->
            val piece = board.getPiece(Square.values()[square])
            if (piece == Piece.NONE || square == move.to.ordinal) {
                null
            } else {
                PieceTask(
                    "${piece.name}-$square",
                    square,
                    if (square == move.from.ordinal) move.to.ordinal else square,
                    square == move.from.ordinal,
                )
            }
        }
        val captured = board.getPiece(move.to) != Piece.NONE
        return PlanningProblem(
            pieces,
            uci,
            capturedSquare = if (captured) move.to.ordinal else null,
            initialPhysicalOccupancy = (0 until 64).filter {
                board.getPiece(Square.values()[it]) != Piece.NONE
            }.toSet(),
            deferredCapture = captured,
            edgeCaptureExit = captured,
        )
    }

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

    @Test fun fewerPickupsOutrankShorterCarriedDistance() {
        val problem = PlanningProblem(
            listOf(
                PieceTask("main", parseSquare("a1"), parseSquare("a5"), true),
                PieceTask("wall-b2", parseSquare("b2"), parseSquare("b2")),
                PieceTask("wall-a4", parseSquare("a4"), parseSquare("a4")),
            ),
            "a1a5",
        )

        val plan = planner(maxTemporaryPieces = 0).plan(problem)

        plan.validate()
        assertEquals(listOf(0, 3, 8, 2), plan.objective)
    }

    @Test fun fewerCapturePickupsOutrankShorterBinRoute() {
        val problem = PlanningProblem(
            listOf(
                PieceTask("main", parseSquare("h8"), parseSquare("h7"), true),
                PieceTask("fixed-a1", parseSquare("a1"), parseSquare("a1")),
                PieceTask("fixed-f2", parseSquare("f2"), parseSquare("f2")),
            ),
            "h8h7",
            capturedSquare = parseSquare("h1"),
            deferredCapture = true,
            edgeCaptureExit = true,
        )

        val plan = planner(maxTemporaryPieces = 0).plan(problem)

        plan.validate()
        assertEquals(listOf(0, 4, 10, 1), plan.objective)
        assertEquals(parseSquare("a3"), plan.capturePath.last())
    }

    @Test fun exactMacroSearchCertifiesSmallCase() {
        val problem = PlanningProblem(
            listOf(
                PieceTask("main", parseSquare("a1"), parseSquare("a5"), true),
                PieceTask("wall-b2", parseSquare("b2"), parseSquare("b2")),
                PieceTask("wall-a4", parseSquare("a4"), parseSquare("a4")),
            ),
        )

        val plan = planner(maxTemporaryPieces = 0, exactSearch = true).plan(problem)

        plan.validate()
        assertTrue(plan.statistics.optimal)
        assertEquals("exhaustive", plan.statistics.searchMode)
        assertEquals(listOf(0, 3, 8, 2), plan.objective)
    }

    @Test fun structuralClassifierRejectsProvenImpossibleCases() {
        val captured = parseSquare("b1")
        val fullBoardPieces = (0 until 64).filter { it != captured }.map { square ->
            PieceTask(
                "p-$square",
                square,
                if (square == parseSquare("a1")) captured else square,
                square == parseSquare("a1"),
            )
        }
        val fullBoard = PlanningProblem(
            fullBoardPieces,
            capturedSquare = captured,
            deferredCapture = true,
            edgeCaptureExit = true,
            initialPhysicalOccupancy = (0 until 64).toSet(),
        )
        assertEquals("proven_impossible", analyzeFeasibility(fullBoard).status)
        assertThrows(PlanningException::class.java) { planner().plan(fullBoard) }

        val blank = parseSquare("h8")
        val first = parseSquare("a1")
        val second = parseSquare("c1")
        val oddSwap = PlanningProblem(
            (0 until 64).filter { it != blank }.map { square ->
                PieceTask(
                    "p-$square",
                    square,
                    when (square) {
                        first -> second
                        second -> first
                        else -> square
                    },
                    square == first,
                )
            },
        )
        assertEquals("proven_impossible", analyzeFeasibility(oddSwap).status)
    }

    @Test fun denseChallengeGeometriesHaveConstructivePlans() {
        val cases = listOf(
            Triple("8/2pppp2/1pbqkbp1/1prpprp1/1PPnnPP1/1PBBKQP1/2PNNP2/2R2R2 w - - 0 1", "d3e4", listOf(5, 46, 110, 32)),
            Triple("4k3/2pppp2/1prqnbp1/1pNBBpr1/1PRRNPP1/1PBBQNP1/2PPKP2/8 b - - 0 1", "d5c4", listOf(4, 37, 92, 25)),
            Triple("2k5/2pppp2/1pbbnrp1/1prppqp1/1PPpnPP1/1PBBNNP1/2PRQP2/2K3R1 w - - 0 1", "e3f5", listOf(6, 50, 105, 34)),
            Triple("4k3/2pppp2/1pnbqrp1/1pnrbrp1/1PRBNPP1/1PBQNRP1/2PPKP2/8 b - - 0 1", "c6d4", listOf(4, 42, 104, 28)),
            Triple("4k3/2pppp2/1pbbqnp1/2prnpr1/1PPPNPP1/2PRQBP1/2PRNP2/4K3 w - - 0 1", "e4d5", listOf(5, 33, 73, 19)),
            Triple("4k3/2pppp2/1pbbqrp1/1pnRnRp1/1PRQNPP1/1PBBNRP1/2PPKP2/8 w - - 0 1", "d4e5", listOf(5, 41, 100, 27)),
            Triple("4k3/2pppp2/1pnbqrp1/1pnrbrp1/1PBRNPP1/1PBQNRP1/2PPKP2/8 w - - 0 1", "d4d5", listOf(2, 15, 24, 8)),
            Triple("4k3/2pppp2/1pnbqrp1/1pnrprb1/1PRKNPP1/1PBQNRP1/2PPBP2/8 w - - 0 1", "d4e5", listOf(5, 41, 100, 27)),
            Triple("4k3/8/8/rnbq4/pppp4/PP1P4/PPPP4/RNBQ1K2 w - - 0 1", "b1c3", listOf(4, 38, 99, 28)),
            Triple("4k3/8/8/8/8/n1nnnnnn/P1PPPPPP/RNBQKBNR w - - 0 1", "c1a3", listOf(4, 32, 72, 19)),
        )
        val densePlanner = RearrangementPlanner(
            PlannerConfig(
                timeLimitMillis = 1_000,
                maxNodes = 1,
                maxTemporaryPieces = 10,
                parkingCandidates = 12,
                corridorCandidates = 8,
                dependencyDepth = 8,
            ),
        )

        cases.forEach { (fen, uci, expectedObjective) ->
            val problem = physicalFenProblem(fen, uci)
            assertEquals("proven_solvable", analyzeFeasibility(problem).status)
            val plan = densePlanner.plan(problem)
            plan.validate()
            assertEquals(expectedObjective, plan.objective)
            assertTrue(plan.temporaryPieceCount <= 10)
            if (problem.capturedSquare != null) assertEquals(0, plan.capturePath.last() % 8)
        }
    }

    @Test fun deterministicConstructiveStressPlansReplay() {
        val random = Random(20260816)
        val stressPlanner = RearrangementPlanner(
            PlannerConfig(
                timeLimitMillis = 1_000,
                maxNodes = 1,
                maxTemporaryPieces = 63,
                corridorCandidates = 8,
                parkingCandidates = 12,
                dependencyDepth = 8,
                broadCandidatesPerPiece = 3,
            ),
        )
        listOf(false, true).forEach { capture ->
            repeat(500) {
                val pieceCount = random.nextInt(2, 33)
                val occupied = buildSet {
                    while (size < pieceCount) add(random.nextInt(64))
                }
                val source = occupied.sorted()[random.nextInt(occupied.size)]
                val target = if (capture) {
                    (occupied - source).sorted().let { it[random.nextInt(it.size)] }
                } else {
                    ((0 until 64).toSet() - occupied).sorted().let { it[random.nextInt(it.size)] }
                }
                val active = if (capture) occupied - target else occupied
                val problem = PlanningProblem(
                    active.sorted().map { square ->
                        PieceTask(
                            "piece-$square",
                            square,
                            if (square == source) target else square,
                            square == source,
                        )
                    },
                    capturedSquare = if (capture) target else null,
                    deferredCapture = capture,
                    edgeCaptureExit = capture,
                )
                assertEquals("proven_solvable", analyzeFeasibility(problem).status)
                stressPlanner.plan(problem).validate()
            }
        }
    }

    @Test fun captureCorridorLimitNeverDiscardsABinExit() {
        val start = 7
        val occupied = setOf(
            1, 2, 3, 5, 9, 10, 14, 15, 16, 17, 24, 25, 31, 32, 34, 36,
            37, 39, 44, 45, 48, 49, 52, 53, 56, 58, 59, 60, 61, 62, 63,
        )
        val occupant = buildMap {
            put(start, -1)
            occupied.forEachIndexed { index, square -> put(square, index) }
        }

        val corridors = relaxedCorridors(
            start, (0 until 64 step 8).toSet(), occupant, -1, limit = 4,
        )

        assertEquals((0 until 64 step 8).toSet(), corridors.map { it.last() }.toSet())
    }

    @Test fun randomLegalChessCorpusMatchesSharedParityDigest() {
        val selector = XorShift64(20260816)
        val digest = MessageDigest.getInstance("SHA-256")
        var board = Board()
        var captures = 0
        var promotions = 0
        val corpusPlanner = RearrangementPlanner(
            PlannerConfig(
                timeLimitMillis = 1_000,
                maxNodes = 1,
                maxTemporaryPieces = 31,
                corridorCandidates = 8,
                parkingCandidates = 12,
                dependencyDepth = 8,
                broadCandidatesPerPiece = 3,
            ),
        )
        repeat(1000) { case ->
            if (case > 0 && case % 80 == 0) {
                board = Board()
                digest.update("RESET\n".toByteArray())
            }
            var legal = board.legalMoves().map { it.toString().lowercase() }.sorted()
            if (legal.isEmpty()) {
                board = Board()
                digest.update("RESET\n".toByteArray())
                legal = board.legalMoves().map { it.toString().lowercase() }.sorted()
            }
            val uci = legal[selector.nextIndex(legal.size)]
            digest.update("$uci\n".toByteArray())
            val move = Move(uci, board.sideToMove)
            val problem = planningProblemFromChess(
                board,
                move,
                (0 until 64).filter {
                    board.getPiece(Square.values()[it]) != Piece.NONE
                }.toSet(),
                deferredCapture = true,
                edgeCaptureExit = true,
            )
            if (problem.capturedSquare != null) captures++
            if (uci.length == 5) promotions++
            val plan = corpusPlanner.plan(problem)
            plan.validate()
            assertEquals("constructive-fallback", plan.statistics.searchMode)
            val commands = plan.protocolCommands()
            assertTrue(commands.first().startsWith("PLAN "))
            assertEquals("COMMIT", commands.last())
            commands.forEach { assertTrue(it.length < 32) }
            commands.filter { it.startsWith("DRAG ") }.forEach { command ->
                val text = command.substring(5)
                assertTrue(text[0] == text[2] || text[1] == text[3])
            }
            board.doMove(move, true)
        }

        assertEquals(124, captures)
        assertEquals(2, promotions)
        assertEquals(
            "0E2822D7D5A4A2500587ABCC85799E8417A5AAEE6C3EBF9FCD2AD2164120757A",
            digest.digest().joinToString("") { "%02X".format(it) },
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
