package org.openautomaticchessboard.mobile.transport

import android.os.Handler
import android.os.Looper
import org.openautomaticchessboard.mobile.protocol.Protocol

class SimulatorTransport(private val listener: BoardTransport.Listener) : BoardTransport {
    override val label = "Simulator"
    override var isConnected = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val occupied = MonitorStateDefaults.START_SQUARES.toMutableSet()
    private var trolleyX = 5
    private var trolleyY = 6
    private var homed = false
    private var sequence = 1

    override fun start() {
        isConnected = true
        listener.onStatus("Simulator connected — hardware cannot move", true)
        emit("READY ACB1", 100)
    }

    override fun send(line: String) {
        check(isConnected) { "Simulator is disconnected" }
        when (val command = line.trim()) {
            "!" -> emit("ESTOP REMOTE", 30)
            "PING", "HELLO" -> emit("PONG ACB1", 30)
            "INFO" -> emit("INFO ACB2 SIM BOARD,TELEM,REMOTE,ESTOP,CALIBRATE,MANUAL", 30)
            "STATUS" -> emit("STATUS ACB1 $sequence ${if (homed) 1 else 0} 0", 30)
            "TELEM" -> emit("TELEM ACB2 $sequence ${if (homed) 1 else 0} 0 0 0 $trolleyX $trolleyY 1 1 1023 1536 42", 30)
            "BOARD" -> emit("BOARD ${Protocol.boardHexFromSquares(occupied)}", 30)
            "BTTEST" -> emit("BT SKIP SIMULATOR", 30)
            "STOP" -> emit("STOPPED", 30)
            "REJECT" -> emit("UNDO REQUIRED", 30)
            "ACCEPT" -> emit("TURN COMPUTER", 30)
            "CALIBRATE" -> {
                sequence = 3
                emit("CALIBRATING", 30)
                trolleyX = 5; trolleyY = 6; homed = true; sequence = 1
                emit("CALIBRATED e6", 220)
            }
            else -> when {
                command.startsWith("START ") -> {
                    occupied.clear()
                    occupied.addAll(MonitorStateDefaults.START_SQUARES)
                    val humanSide = command.substringAfter(' ').take(1)
                    emit("SESSION $humanSide", 60)
                    emit(if (humanSide == "W") "TURN HUMAN" else "TURN COMPUTER", 120)
                }
                command.startsWith("PLAY ") -> {
                    val fields = command.split(' ')
                    val move = fields.getOrNull(1).orEmpty()
                    emit("MOVING $move", 50)
                    applyMove(move, fields.getOrNull(2))
                    emit("DONE $move", 250)
                }
                command.matches(Regex("HEAD [a-h][1-8]")) -> {
                    if (!homed) emit("ERR CALIBRATE", 30) else {
                        val square = command.substringAfter(' ')
                        emit("MOVING HEAD $square", 30)
                        trolleyX = square[0] - 'a' + 1; trolleyY = square[1] - '0'
                        emit("MOVED HEAD $square", 220)
                    }
                }
                command.matches(Regex("PIECE [a-h][1-8][a-h][1-8]")) -> {
                    val move = command.substringAfter(' ')
                    val from = square(move, 0); val to = square(move, 2)
                    when {
                        !homed -> emit("ERR CALIBRATE", 30)
                        from !in occupied -> emit("ERR SOURCE EMPTY", 30)
                        to in occupied -> emit("ERR TARGET FULL", 30)
                        else -> {
                            emit("MOVING PIECE $move", 30)
                            occupied.remove(from); occupied.add(to)
                            trolleyX = to % 8 + 1; trolleyY = to / 8 + 1
                            emit("MOVED PIECE $move", 220)
                        }
                    }
                }
                command.startsWith("GAMEOVER ") -> emit("STOPPED", 30)
                command.startsWith("SIMMOVE ") -> {
                    val move = command.substringAfter(' ')
                    applyMove(move, null)
                    emit("MOVE $move", 30)
                }
                else -> emit("ERR UNKNOWN_COMMAND", 30)
            }
        }
    }

    private fun emit(line: String, delay: Long) = handler.postDelayed({
        if (isConnected) listener.onLine(line)
    }, delay)

    private fun applyMove(uci: String, flag: String?) {
        if (!uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) return
        fun square(offset: Int): Int = (uci[offset] - 'a') + (uci[offset + 1] - '1') * 8
        val from = square(0)
        val to = square(2)
        occupied.remove(from)
        occupied.remove(to)
        if (flag == "E") occupied.remove(if (to > from) to - 8 else to + 8)
        occupied.add(to)
        if (flag == "C") {
            val rankBase = (from / 8) * 8
            val kingSide = to % 8 == 6
            val rookFrom = rankBase + if (kingSide) 7 else 0
            val rookTo = rankBase + if (kingSide) 5 else 3
            occupied.remove(rookFrom)
            occupied.add(rookTo)
        }
    }

    private fun square(text: String, offset: Int): Int =
        (text[offset] - 'a') + (text[offset + 1] - '1') * 8

    override fun close() {
        isConnected = false
        handler.removeCallbacksAndMessages(null)
        listener.onStatus("Disconnected", false)
    }
}

private object MonitorStateDefaults {
    val START_SQUARES = ((0..15) + (48..63)).toSet()
}
