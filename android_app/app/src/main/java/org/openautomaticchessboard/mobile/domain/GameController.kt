package org.openautomaticchessboard.mobile.domain

import android.os.Handler
import android.os.Looper
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import org.openautomaticchessboard.mobile.domain.routing.MotionPlan
import org.openautomaticchessboard.mobile.domain.routing.PlannerConfig
import org.openautomaticchessboard.mobile.domain.routing.PlanningException
import org.openautomaticchessboard.mobile.domain.routing.RearrangementPlanner
import org.openautomaticchessboard.mobile.domain.routing.planningProblemFromChess
import org.openautomaticchessboard.mobile.domain.routing.squareName
import org.openautomaticchessboard.mobile.protocol.BoardEvent
import org.openautomaticchessboard.mobile.protocol.Protocol
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.Executors

data class GameSnapshot(
    val active: Boolean,
    val humanWhite: Boolean,
    val status: String,
    val pieces: Map<Int, Piece>,
    val expectedSquares: Set<Int>,
    val history: List<String>,
    val engineThinking: Boolean,
)

class GameController(
    private val engine: StockfishEngine,
    private val channel: GameBoardChannel,
    private val onChanged: (GameSnapshot) -> Unit,
    private val onPromotionChoice: (reported: String, choose: (Char) -> Unit) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "chess-engine").apply { isDaemon = true } }
    private var board = Board()
    private var moveList = MoveList()
    private var active = false
    private var humanWhite = true
    private var status = "No game in progress"
    private var pendingEngineMove: Move? = null
    private var engineThinking = false
    private var awaitingPromotionConfirmation = false
    private var routePhase = RoutePhase.NONE
    private var activeRoutePlan: MotionPlan? = null
    private val routeCommands = ArrayDeque<String>()
    private var routeCurrentCommand = ""
    private var routeExpectedOccupancy = emptySet<Int>()
    private var routeSnapshotRequestedMs = 0L
    private var routeMotionSent = false
    private var routeExclusive = false
    private var routeGeneration = 0
    private var routeTimeoutToken = 0
    @Volatile private var closed = false
    var elo = 2000
    var thinkMillis = 800L
    var routeTimeMillis = ROUTE_PLANNING_TIMEOUT_MS
    var routeMaxTemporaryPieces = 10

    val snapshot: GameSnapshot get() {
        val pieces = (0..63).mapNotNull { index ->
            val piece = board.getPiece(Square.values()[index])
            if (piece == Piece.NONE) null else index to piece
        }.toMap()
        val sans = runCatching { moveList.toSanArray().toList() }.getOrElse { moveList.map(Move::toString) }
        return GameSnapshot(active, humanWhite, status, pieces, pieces.keys, sans, engineThinking)
    }

    fun chooseHumanSide(white: Boolean) {
        if (active) return
        humanWhite = white
        publish()
    }

    fun start(humanPlaysWhite: Boolean): Result<Unit> = runCatching {
        check(!active) { "Stop the current game first" }
        check(engine.isInstalled) { "Stockfish is unavailable for this Android CPU" }
        resetRoute()
        board = Board()
        moveList = MoveList()
        active = true
        humanWhite = humanPlaysWhite
        pendingEngineMove = null
        awaitingPromotionConfirmation = false
        status = "Calibration requested. Keep hands clear."
        publish()
        channel.sendCommand(if (humanWhite) "START W" else "START B").getOrThrow()
    }.onFailure {
        if (!closed) {
            active = false
            status = it.message ?: "Could not start game"
            publish()
        }
    }

    fun stop() {
        val result = if (routeExclusive) channel.abortRouteTransaction() else channel.sendCommand("STOP")
        resetRoute()
        active = false
        engineThinking = false
        pendingEngineMove = null
        status = result.fold(
            onSuccess = { "Stop requested" },
            onFailure = { "Stop was not delivered: ${it.message ?: "connection failed"}" },
        )
        publish()
    }

    fun handle(event: BoardEvent) {
        if (handleRouteEvent(event)) {
            publish()
            return
        }
        when (event.kind) {
            "SETUP" -> status = "Set all starting pieces, then press physical Button A."
            "SESSION" -> status = "Remote game started"
            "TURN" -> when (event.args.firstOrNull()) {
                "COMPUTER" -> when {
                    !active -> status = "Ignored computer turn: no remote game is active."
                    sideToMoveIsHuman() -> status = "Ignored computer turn: the logical position expects the human."
                    else -> startEngineThink()
                }
                "HUMAN" -> status = "Your move. Press Button A on the board when complete."
            }
            "MOVE" -> event.args.firstOrNull()?.let(::acceptHumanMove)
            "MOVING" -> status = "Carriage is moving. Keep hands clear."
            "DONE" -> event.args.firstOrNull()?.let(::completeEngineMove)
            "PROMOTE" -> status = "Replace pawn with ${event.args.firstOrNull()?.uppercase() ?: "piece"}, then press Button A."
            "PROMOTION" -> if (event.args.firstOrNull() == "OK") {
                awaitingPromotionConfirmation = false
                if (isGameOver()) finishGame() else status = "Your move."
            }
            "ESTOP" -> {
                active = false; engineThinking = false; pendingEngineMove = null
                status = "REMOTE HALT SENT — inspect locally"
            }
            "ERR" -> status = "Board error: ${event.args.joinToString(" ")}"
            "STOPPED" -> {
                active = false; engineThinking = false; pendingEngineMove = null
                status = "Remote game stopped; standalone mode remains available."
            }
        }
        publish()
    }

    private fun acceptHumanMove(reported: String) {
        if (!active || !sideToMoveIsHuman()) {
            channel.sendCommand("REJECT")
            return
        }
        val matches = board.legalMoves().filter { it.toString().startsWith(reported.lowercase()) }
        if (matches.isEmpty()) {
            channel.sendCommand("REJECT")
            status = "Illegal move $reported; restore the pieces physically."
            publish()
            return
        }
        val promotions = matches.filter { it.promotion != Piece.NONE }
        if (promotions.isNotEmpty() && reported.length == 4) {
            onPromotionChoice(reported) { symbol ->
                val chosen = promotions.firstOrNull { it.toString().last() == symbol.lowercaseChar() } ?: promotions.first()
                commitHumanMove(chosen)
            }
        } else commitHumanMove(matches.first())
    }

    private fun commitHumanMove(move: Move) {
        if (!board.doMove(move, true)) {
            channel.sendCommand("REJECT")
            status = "Move ${move} became invalid; restore the pieces."
            publish()
            return
        }
        moveList.add(move)
        channel.sendCommand("ACCEPT")
        if (isGameOver()) finishGame() else status = "Move accepted; waiting for computer turn."
        publish()
    }

    private fun startEngineThink() {
        if (engineThinking || !active) return
        engineThinking = true
        status = "Stockfish is thinking on this phone…"
        val fen = board.fen
        val selectedElo = elo
        val selectedTime = thinkMillis
        publish()
        worker.execute {
            val result = runCatching { engine.bestMove(fen, selectedElo, selectedTime) }
            main.post {
                if (closed) return@post
                engineThinking = false
                if (!active || board.fen != fen) {
                    status = "Ignored completed engine analysis because the game position changed."
                    publish()
                    return@post
                }
                result.onSuccess { move ->
                    if (move == null) status = "Stockfish reports no legal move in the current position."
                    else sendEngineMove(move)
                }.onFailure {
                    status = "Stockfish failed: ${it.message}"
                    active = false
                }
                publish()
            }
        }
    }

    private fun sendEngineMove(uci: String) {
        val move = Move(uci, board.sideToMove)
        if (!board.isMoveLegal(move, true)) {
            status = "Stockfish returned illegal move $uci"
            return
        }
        pendingEngineMove = move
        if ("PLANROUTE" in channel.firmwareCapabilities) {
            routePhase = RoutePhase.SNAPSHOT
            routeGeneration++
            routeSnapshotRequestedMs = System.currentTimeMillis()
            status = "Reading board for collision-safe routing…"
            channel.sendCommand("BOARD")
                .onSuccess { armRouteTimeout(ROUTE_CONTROL_TIMEOUT_MS, "BOARD snapshot") }
                .onFailure { failRoute(it.message ?: "Could not request the board snapshot") }
            return
        }
        val movingPiece = board.getPiece(move.from)
        val castling = movingPiece.pieceType == PieceType.KING &&
            kotlin.math.abs(move.from.ordinal % 8 - move.to.ordinal % 8) == 2
        val enPassant = movingPiece.pieceType == PieceType.PAWN && board.getPiece(move.to) == Piece.NONE &&
            move.from.ordinal % 8 != move.to.ordinal % 8
        channel.sendCommand(Protocol.playCommand(uci, castling, enPassant)).onSuccess {
            status = "Board is moving $uci; keep hands clear."
        }.onFailure {
            pendingEngineMove = null
            status = it.message ?: "Could not request engine move"
        }
    }

    private fun handleRouteEvent(event: BoardEvent): Boolean {
        if (routePhase == RoutePhase.NONE) return false
        return when (event.kind) {
            "BOARD" -> handleRouteBoard()
            "PLAN" -> handleRoutePlan(event)
            "MOVED" -> handleRouteMoved(event)
            "DONE" -> handleRouteDone(event)
            "ERR" -> {
                failRoute(event.args.joinToString(" ").ifBlank { "unknown route error" })
                true
            }
            "ESTOP", "STOPPED" -> {
                resetRoute()
                false
            }
            else -> false
        }
    }

    private fun handleRouteBoard(): Boolean {
        if (routePhase == RoutePhase.SNAPSHOT) {
            cancelRouteTimeout()
            startRoutePlanning()
            return true
        }
        if (routePhase != RoutePhase.BOARD) return false
        val actual = channel.physicalOccupancy
        if (actual == null || actual != routeExpectedOccupancy) {
            val missing = routeExpectedOccupancy - actual.orEmpty()
            val unexpected = actual.orEmpty() - routeExpectedOccupancy
            failRoute(
                "routed sensor proof failed (missing ${formatSquares(missing)}; " +
                    "extra ${formatSquares(unexpected)})",
            )
        } else {
            cancelRouteTimeout()
            advanceRoute()
        }
        return true
    }

    private fun handleRoutePlan(event: BoardEvent): Boolean {
        if (routePhase != RoutePhase.PLAN || event.args.firstOrNull() != "READY") {
            failRoute("PLAN acknowledgement mismatch")
            return true
        }
        val captured = activeRoutePlan?.problem?.capturedSquare
        if (captured != null) {
            if (captured !in routeExpectedOccupancy) {
                failRoute("Capture square was absent from the planned start frame")
                return true
            }
            routeExpectedOccupancy = routeExpectedOccupancy - captured
        }
        cancelRouteTimeout()
        advanceRoute()
        return true
    }

    private fun handleRouteMoved(event: BoardEvent): Boolean {
        if (routePhase != RoutePhase.MOVED) {
            failRoute("Unexpected MOVED acknowledgement")
            return true
        }
        try {
            val drag = Protocol.parseDragCommand(routeCurrentCommand)
            val expectedLabel = routeCurrentCommand.substringAfter(' ')
            require(event.args.size >= 2 && event.args[0] == "PIECE" &&
                event.args[1].equals(expectedLabel, ignoreCase = true)) {
                "MOVED acknowledgement mismatch"
            }
            require(drag.source in routeExpectedOccupancy && drag.target !in routeExpectedOccupancy) {
                "Route occupancy diverged before sensor proof"
            }
            routeExpectedOccupancy = (routeExpectedOccupancy - drag.source) + drag.target
            cancelRouteTimeout()
            advanceRoute()
        } catch (error: Exception) {
            failRoute(error.message ?: "Invalid routed move acknowledgement")
        }
        return true
    }

    private fun handleRouteDone(event: BoardEvent): Boolean {
        if (routePhase != RoutePhase.DONE || routeCurrentCommand != "COMMIT") {
            failRoute("Unexpected DONE acknowledgement")
            return true
        }
        val reported = event.args.firstOrNull()
        if (reported == null) failRoute("DONE did not include a move")
        else {
            cancelRouteTimeout()
            resetRoute()
            completeEngineMove(reported)
        }
        return true
    }

    private fun startRoutePlanning() {
        val move = pendingEngineMove ?: run {
            failRoute("The pending engine move disappeared")
            return
        }
        val sensors = channel.physicalOccupancy
        val sensorUpdatedMs = channel.sensorUpdatedMs
        val expected = logicalOccupancy()
        if (sensorUpdatedMs == null || sensorUpdatedMs < routeSnapshotRequestedMs) {
            failRoute("The board snapshot was stale; request a fresh sensor frame")
            return
        }
        if (sensors == null || sensors != expected) {
            val missing = expected - sensors.orEmpty()
            val unexpected = sensors.orEmpty() - expected
            failRoute(
                "physical/logical mismatch (missing ${formatSquares(missing)}; " +
                    "extra ${formatSquares(unexpected)})",
            )
            return
        }

        val problem = try {
            val position = Board().apply { loadFromFen(board.fen) }
            val immutableMove = Move(move.toString(), position.sideToMove)
            planningProblemFromChess(position, immutableMove, sensors.toSet())
        } catch (error: Exception) {
            failRoute(error.message ?: "Could not construct a route problem")
            return
        }
        channel.beginRouteTransaction().onFailure {
            failRoute(it.message ?: "Could not reserve the board connection")
            return
        }
        routeExclusive = true
        routePhase = RoutePhase.PLANNING
        status = "Planning collision-safe route on this phone…"
        val planningLimit = routeTimeMillis.coerceIn(500L, 30_000L)
        armRouteTimeout(planningLimit + ROUTE_CONTROL_TIMEOUT_MS, "route planning")
        val generation = routeGeneration
        publish()
        worker.execute {
            val result = runCatching {
                RearrangementPlanner(
                    PlannerConfig(
                        timeLimitMillis = planningLimit,
                        maxTemporaryPieces = routeMaxTemporaryPieces.coerceIn(0, 30),
                    ),
                ).plan(problem)
            }
            main.post {
                if (closed || generation != routeGeneration || routePhase != RoutePhase.PLANNING) {
                    return@post
                }
                result.onSuccess(::beginRouteExecution).onFailure {
                    failRoute(it.message ?: "Route planning failed")
                }
                publish()
            }
        }
    }

    private fun beginRouteExecution(plan: MotionPlan) {
        val pending = pendingEngineMove
        if (pending == null || plan.problem.moveUci != pending.toString().lowercase()) {
            failRoute("Stale route plan was discarded")
            return
        }
        try {
            plan.validate()
            val commands = plan.protocolCommands()
            check(routeExclusive) { "The route lost exclusive connection ownership" }
            activeRoutePlan = plan
            routeCommands.clear()
            routeCommands.addAll(commands)
            routeExpectedOccupancy = plan.problem.initialPhysicalOccupancy
                ?: plan.problem.initialOccupancyBeforeCapture
            routeMotionSent = false
            status = "Route ready: ${plan.dragCount} drags, ${plan.temporaryPieceCount} temporary. Keep hands clear."
            advanceRoute()
        } catch (error: Exception) {
            failRoute(error.message ?: "Could not begin route execution")
        }
    }

    private fun advanceRoute() {
        if (!routeExclusive || activeRoutePlan == null || routeCommands.isEmpty()) {
            failRoute("Route command sequence ended before COMMIT")
            return
        }
        val command = routeCommands.removeFirst()
        val verb = command.substringBefore(' ').uppercase()
        routeCurrentCommand = command
        routePhase = when (verb) {
            "PLAN" -> RoutePhase.PLAN
            "BOARD" -> RoutePhase.BOARD
            "DRAG" -> RoutePhase.MOVED
            "COMMIT" -> RoutePhase.DONE
            else -> {
                failRoute("Unknown route command $verb")
                return
            }
        }
        val planHasCapture = verb == "PLAN" && activeRoutePlan?.problem?.capturedSquare != null
        if (verb == "DRAG" || planHasCapture) routeMotionSent = true
        val timeout = if (verb == "DRAG" || planHasCapture) ROUTE_MOTION_TIMEOUT_MS else ROUTE_CONTROL_TIMEOUT_MS
        armRouteTimeout(timeout, verb)
        channel.sendRouteCommand(command).onFailure {
            failRoute(it.message ?: "Could not send route command $verb")
        }
    }

    private fun armRouteTimeout(delayMillis: Long, verb: String) {
        val token = ++routeTimeoutToken
        main.postDelayed({
            if (!closed && token == routeTimeoutToken && routePhase != RoutePhase.NONE) {
                failRoute("Timed out waiting for $verb acknowledgement")
                publish()
            }
        }, delayMillis)
    }

    private fun cancelRouteTimeout() {
        routeTimeoutToken++
    }

    private fun failRoute(detail: String, attemptStop: Boolean = true) {
        val uncertain = routeMotionSent
        cancelRouteTimeout()
        if (routeExclusive) {
            if (attemptStop && channel.connected) channel.abortRouteTransaction()
        } else if (attemptStop && active && channel.connected) {
            channel.sendCommand("STOP")
        }
        resetRoute()
        pendingEngineMove = null
        engineThinking = false
        active = false
        val recovery = if (uncertain) {
            "The last action may have changed the board; inspect every square before recovery."
        } else {
            "No routed magnet movement was acknowledged; verify the position before restarting."
        }
        status = "Collision-safe route stopped: $detail. $recovery"
    }

    private fun resetRoute() {
        if (routeExclusive) channel.finishRouteTransaction()
        routeExclusive = false
        routeGeneration++
        cancelRouteTimeout()
        routePhase = RoutePhase.NONE
        activeRoutePlan = null
        routeCommands.clear()
        routeCurrentCommand = ""
        routeExpectedOccupancy = emptySet()
        routeSnapshotRequestedMs = 0L
        routeMotionSent = false
    }

    fun connectionChanged(isConnected: Boolean) {
        if (!isConnected && routePhase != RoutePhase.NONE) {
            failRoute("Board connection was lost during route execution", attemptStop = false)
            publish()
        }
    }

    private fun logicalOccupancy(): Set<Int> = (0..63).filter {
        board.getPiece(Square.values()[it]) != Piece.NONE
    }.toSet()

    private fun formatSquares(squares: Set<Int>): String =
        squares.sorted().joinToString { squareName(it) }.ifBlank { "none" }

    private fun completeEngineMove(reported: String) {
        val move = pendingEngineMove
        if (move == null || !move.toString().startsWith(reported.lowercase())) {
            status = "Unexpected motion completion: $reported"
            return
        }
        if (!board.doMove(move, true)) {
            status = "Completed board move is illegal in the logical game"
            return
        }
        moveList.add(move)
        awaitingPromotionConfirmation = move.promotion != Piece.NONE
        pendingEngineMove = null
        if (isGameOver() && !awaitingPromotionConfirmation) finishGame()
        else status = "Your move. Press Button A when complete."
    }

    private fun sideToMoveIsHuman(): Boolean = (board.sideToMove == Side.WHITE) == humanWhite

    private fun isGameOver(): Boolean = board.isMated || board.isStaleMate || board.isDraw

    private fun result(): String = when {
        board.isMated -> if (board.sideToMove == Side.WHITE) "0-1" else "1-0"
        board.isStaleMate || board.isDraw -> "1/2-1/2"
        else -> "*"
    }

    private fun finishGame() {
        val result = result()
        channel.sendCommand("GAMEOVER $result")
        status = "Game over: $result"
        active = false
    }

    fun pgn(): String {
        val result = result()
        val engineIdentity = runCatching { engine.identity() }.getOrDefault("Stockfish")
        val white = if (humanWhite) "Human" else engineIdentity
        val black = if (humanWhite) engineIdentity else "Human"
        val moves = runCatching { moveList.toSanWithMoveNumbers() }.getOrElse {
            moveList.mapIndexed { index, move -> if (index % 2 == 0) "${index / 2 + 1}. $move" else move.toString() }.joinToString(" ")
        }
        return """[Event "Open Automatic Chessboard"]
[Site "Android companion"]
[Date "${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))}"]
[Round "-"]
[White "$white"]
[Black "$black"]
[Result "$result"]

$moves $result
"""
    }

    private fun publish() { if (!closed) onChanged(snapshot) }

    override fun close() {
        if (routeExclusive && channel.connected) channel.abortRouteTransaction()
        resetRoute()
        closed = true
        active = false
        engineThinking = false
        pendingEngineMove = null
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        engine.close()
    }

    private enum class RoutePhase { NONE, SNAPSHOT, PLANNING, PLAN, BOARD, MOVED, DONE }

    companion object {
        private const val ROUTE_PLANNING_TIMEOUT_MS = 8_000L
        private const val ROUTE_CONTROL_TIMEOUT_MS = 8_000L
        private const val ROUTE_MOTION_TIMEOUT_MS = 75_000L
    }
}
