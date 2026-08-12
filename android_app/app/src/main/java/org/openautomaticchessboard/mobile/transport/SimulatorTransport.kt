package org.openautomaticchessboard.mobile.transport

import android.os.Handler
import android.os.Looper
import org.openautomaticchessboard.mobile.protocol.Protocol
import org.openautomaticchessboard.mobile.protocol.PlanRouteRequest

class SimulatorTransport(private val listener: BoardTransport.Listener) : BoardTransport {
    override val label = "Simulator"
    override var isConnected = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val occupied = MonitorStateDefaults.START_SQUARES.toMutableSet()
    private var trolleyX = 5
    private var trolleyY = 6
    private var homed = false
    private var fault = false
    private var sequence = 1
    private var plan: PlanRouteRequest? = null
    private var planInitial: Set<Int>? = null
    private var planExpected: Set<Int>? = null

    override fun start() {
        isConnected = true
        listener.onStatus("Simulator connected — hardware cannot move", true)
        emit("READY ACB1", 100)
    }

    override fun send(line: String) {
        check(isConnected) { "Simulator is disconnected" }
        when (val command = line.trim()) {
            "!" -> {
                fault = true
                homed = false
                trolleyX = 0
                trolleyY = 0
                sequence = 10
                emit("ESTOP REMOTE", 30)
            }
            "PING", "HELLO" -> emit("PONG ACB1", 30)
            "INFO" -> emit("INFO ACB2 4.1.0-SIM BOARD,TELEM,REMOTE,ESTOP,CALIBRATE,MANUAL,SENSORFRAME,PLANROUTE", 30)
            "STATUS" -> emit("STATUS ACB1 $sequence ${if (homed) 1 else 0} ${if (sequence >= 15) 1 else 0}", 30)
            "TELEM" -> emit("TELEM ACB2 $sequence ${if (homed) 1 else 0} ${if (sequence >= 15) 1 else 0} ${if (fault) 1 else 0} 0 $trolleyX $trolleyY 1 1 1023 1536 42", 30)
            "BOARD" -> emit("BOARD ${Protocol.boardHexFromSquares(occupied)}", 30)
            "BTTEST" -> emit("BT SKIP SIMULATOR", 30)
            "STOP" -> {
                clearPlan()
                sequence = 1
                emit("STOPPED", 30)
            }
            "REJECT" -> emit("UNDO REQUIRED", 30)
            "ACCEPT" -> emit("TURN COMPUTER", 30)
            "CALIBRATE" -> {
                if (fault) emit("ERR FAULT", 30) else {
                    sequence = 3
                    emit("CALIBRATING", 30)
                    trolleyX = 5; trolleyY = 6; homed = true; sequence = 1
                    emit("CALIBRATED e6", 220)
                }
            }
            else -> when {
                fault && command.startsWith("START ") -> emit("ERR FAULT", 30)
                fault && command.startsWith("PLAY ") -> emit("ERR FAULT", 30)
                command.startsWith("START ") -> {
                    occupied.clear()
                    occupied.addAll(MonitorStateDefaults.START_SQUARES)
                    clearPlan()
                    val humanSide = command.substringAfter(' ').take(1)
                    homed = true
                    trolleyX = 5
                    trolleyY = 6
                    sequence = if (humanSide == "W") 16 else 17
                    emit("SETUP PRESS A", 30)
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
                command.startsWith("PLAN ") -> beginPlan(command)
                command.startsWith("DRAG ") -> runDrag(command)
                command == "COMMIT" -> commitPlan()
                command.matches(Regex("HEAD [a-h][1-8]")) -> {
                    if (fault) emit("ERR FAULT", 30) else if (!homed) emit("ERR CALIBRATE", 30) else {
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
                        fault -> emit("ERR FAULT", 30)
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

    private fun beginPlan(command: String) {
        runCatching {
            check(!fault) { "FAULT" }
            check(homed && sequence == 17 && plan == null) { "NOT READY" }
            val request = Protocol.parsePlanCommand(command)
            val from = Protocol.squareIndex(request.uci.take(2))
            val to = Protocol.squareIndex(request.uci.substring(2, 4))
            check(from in occupied) { "SOURCE EMPTY" }
            request.captureSquare?.let { check(it in occupied) { "PLAN STATE" } }
            val initial = occupied.toSet()
            val expected = initial.toMutableSet().apply {
                remove(from)
                request.captureSquare?.let(::remove)
                add(to)
                if (request.mode == 'k' || request.mode == 'c') {
                    val rankBase = from / 8 * 8
                    val rookFrom = rankBase + if (request.mode == 'k') 7 else 0
                    val rookTo = rankBase + if (request.mode == 'k') 5 else 3
                    check(remove(rookFrom)) { "PLAN STATE" }
                    add(rookTo)
                }
            }
            plan = request
            planInitial = initial
            planExpected = expected
            request.captureSquare?.let(occupied::remove)
            sequence = 22
            emit("PLAN READY", if (request.captureSquare == null) 30 else 180)
        }.onFailure { emit("ERR ${it.message ?: "BAD PLAN"}", 30) }
    }

    private fun runDrag(command: String) {
        runCatching {
            check(plan != null) { "NO PLAN" }
            val route = Protocol.parseDragCommand(command)
            check(route.source in occupied) { "SOURCE EMPTY" }
            check(route.target !in occupied) { "TARGET FULL" }
            check(route.path.drop(1).none { it in occupied }) { "ROUTE BLOCKED" }
            val label = "${Protocol.squareName(route.source)}${Protocol.squareName(route.target)}"
            emit("MOVING PIECE $label", 30)
            occupied.remove(route.source)
            occupied.add(route.target)
            trolleyX = route.target % 8 + 1
            trolleyY = route.target / 8 + 1
            emit("MOVED PIECE $label", 180)
        }.onFailure { emit("ERR ${it.message ?: "BAD ROUTE"}", 30) }
    }

    private fun commitPlan() {
        runCatching {
            val request = checkNotNull(plan) { "NO PLAN" }
            when (occupied.toSet()) {
                planInitial -> {
                    clearPlan()
                    sequence = 17
                    emit("PLAN CANCELLED", 30)
                }
                planExpected -> {
                    clearPlan()
                    sequence = 16
                    emit("DONE ${request.uci.take(4)}", 30)
                    if (request.mode in "qrbn") emit("PROMOTE ${request.mode}", 80)
                }
                else -> error("PLAN INCOMPLETE")
            }
        }.onFailure { emit("ERR ${it.message ?: "PLAN INCOMPLETE"}", 30) }
    }

    private fun clearPlan() {
        plan = null
        planInitial = null
        planExpected = null
    }

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
