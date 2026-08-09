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
import org.openautomaticchessboard.mobile.protocol.BoardEvent
import org.openautomaticchessboard.mobile.protocol.Protocol
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
    private val send: (String) -> Result<Unit>,
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
    var elo = 2000
    var thinkMillis = 800L

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

    fun start(humanPlaysWhite: Boolean): Result<Unit> {
        check(!active) { "Stop the current game first" }
        check(engine.isInstalled) { "Stockfish is unavailable for this Android CPU" }
        board = Board()
        moveList = MoveList()
        active = true
        humanWhite = humanPlaysWhite
        pendingEngineMove = null
        awaitingPromotionConfirmation = false
        status = "Calibration requested. Keep hands clear."
        publish()
        return send(if (humanWhite) "START W" else "START B").onFailure {
            active = false
            status = it.message ?: "Could not start game"
            publish()
        }
    }

    fun stop() {
        send("STOP")
        active = false
        engineThinking = false
        status = "Stop requested"
        publish()
    }

    fun handle(event: BoardEvent) {
        when (event.kind) {
            "SETUP" -> status = "Set all starting pieces, then press physical Button A."
            "SESSION" -> status = "Remote game started"
            "TURN" -> when (event.args.firstOrNull()) {
                "COMPUTER" -> if (active && sideToMoveIsHuman().not()) startEngineThink()
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
            "ESTOP" -> { active = false; status = "REMOTE HALT SENT — inspect locally" }
            "ERR" -> status = "Board error: ${event.args.joinToString(" ")}"
            "STOPPED" -> { active = false; status = "Remote game stopped; standalone mode remains available." }
        }
        publish()
    }

    private fun acceptHumanMove(reported: String) {
        if (!active || !sideToMoveIsHuman()) {
            send("REJECT")
            return
        }
        val matches = board.legalMoves().filter { it.toString().startsWith(reported.lowercase()) }
        if (matches.isEmpty()) {
            send("REJECT")
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
            send("REJECT")
            status = "Move ${move} became invalid; restore the pieces."
            publish()
            return
        }
        moveList.add(move)
        send("ACCEPT")
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
                engineThinking = false
                result.onSuccess(::sendEngineMove).onFailure {
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
        val movingPiece = board.getPiece(move.from)
        val castling = movingPiece.pieceType == PieceType.KING &&
            kotlin.math.abs(move.from.ordinal % 8 - move.to.ordinal % 8) == 2
        val enPassant = movingPiece.pieceType == PieceType.PAWN && board.getPiece(move.to) == Piece.NONE &&
            move.from.ordinal % 8 != move.to.ordinal % 8
        send(Protocol.playCommand(uci, castling, enPassant)).onSuccess {
            status = "Board is moving $uci; keep hands clear."
        }.onFailure { status = it.message ?: "Could not request engine move" }
    }

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
        send("GAMEOVER $result")
        status = "Game over: $result"
        active = false
    }

    fun pgn(): String {
        val result = result()
        val white = if (humanWhite) "Human" else "Stockfish 18"
        val black = if (humanWhite) "Stockfish 18" else "Human"
        val moves = runCatching { moveList.toSanWithMoveNumbers() }.getOrElse {
            moveList.mapIndexed { index, move -> if (index % 2 == 0) "${index / 2 + 1}. $move" else move.toString() }.joinToString(" ")
        }
        return """[Event "Open Automatic Chessboard"]
[Date "${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))}"]
[White "$white"]
[Black "$black"]
[Result "$result"]

$moves $result
"""
    }

    private fun publish() = onChanged(snapshot)

    override fun close() {
        worker.shutdownNow()
        engine.close()
    }
}
