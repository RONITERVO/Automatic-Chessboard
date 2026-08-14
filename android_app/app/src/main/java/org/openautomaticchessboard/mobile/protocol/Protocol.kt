package org.openautomaticchessboard.mobile.protocol

data class BoardEvent(val kind: String, val args: List<String>, val raw: String)

data class FirmwareInfo(
    val protocol: String,
    val firmware: String,
    val capabilities: Set<String>,
)

data class Telemetry(
    val protocol: String,
    val sequence: Int,
    val homed: Boolean,
    val remoteMode: Boolean,
    val motionFault: Boolean,
    val magnetOn: Boolean,
    val trolleyX: Int,
    val trolleyY: Int,
    val buttonAReleased: Boolean,
    val buttonBReleased: Boolean,
    val buttonBRaw: Int,
    val freeRam: Int,
    val uptimeSeconds: Long,
)

data class DragRoute(
    val source: Int,
    val target: Int,
    val path: List<Int>,
)

data class PlanRouteRequest(
    val uci: String,
    val mode: Char,
    val captureSquare: Int?,
)

enum class CommandRisk { READ_ONLY, CONTROL, MOTION, EMERGENCY, UNKNOWN }

object Protocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

    private val readOnly = setOf("PING", "HELLO", "INFO", "STATUS", "TELEM", "BOARD", "BTTEST", "SWTEST")
    private val control = setOf("STOP", "REJECT", "GAMEOVER")
    private val motion = setOf(
        "START", "PLAY", "ACCEPT", "CALIBRATE", "HEAD", "PIECE", "PATH", "JOG",
        "PLAN", "DRAG", "COMMIT",
    )

    fun parseEvent(line: String): BoardEvent {
        val fields = line.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return if (fields.isEmpty()) BoardEvent("EMPTY", emptyList(), line)
        else BoardEvent(fields.first().uppercase(), fields.drop(1), line)
    }

    fun parseInfo(event: BoardEvent): FirmwareInfo {
        require(event.kind == "INFO" && event.args.size >= 2) { "Malformed INFO: ${event.raw}" }
        val capabilities = event.args.drop(2).joinToString(" ").split(',')
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        return FirmwareInfo(event.args[0], event.args[1], capabilities)
    }

    fun parseTelemetry(event: BoardEvent): Telemetry {
        require(event.kind == "TELEM" && event.args.size == 13) { "Malformed TELEM: ${event.raw}" }
        val values = event.args.drop(1).map(String::toLongOrNull)
        require(values.all { it != null }) { "Malformed TELEM: ${event.raw}" }
        val numeric = values.filterNotNull()
        return Telemetry(
            event.args[0], numeric[0].toInt(), numeric[1] != 0L, numeric[2] != 0L,
            numeric[3] != 0L, numeric[4] != 0L, numeric[5].toInt(), numeric[6].toInt(),
            numeric[7] != 0L, numeric[8] != 0L, numeric[9].toInt(), numeric[10].toInt(), numeric[11],
        )
    }

    /** Square indexes use a1=0 through h8=63, matching chesslib's Square ordinal. */
    fun parseBoardHex(value: String): Set<Int> {
        val compact = value.trim().uppercase()
        require(compact.length == 16 && compact.all { it in "0123456789ABCDEF" }) {
            "Invalid board snapshot: $value"
        }
        return buildSet {
            repeat(8) { row ->
                val bits = compact.substring(row * 2, row * 2 + 2).toInt(16)
                repeat(8) { file -> if ((bits and (1 shl file)) != 0) add((7 - row) * 8 + file) }
            }
        }
    }

    fun boardHexFromSquares(squares: Set<Int>): String = buildString {
        repeat(8) { row ->
            val rank = 7 - row
            val bits = (0..7).sumOf { file -> if (rank * 8 + file in squares) 1 shl file else 0 }
            append("%02X".format(bits))
        }
    }

    fun classifyCommand(line: String): CommandRisk {
        val stripped = line.trim()
        if (stripped.startsWith("!")) return CommandRisk.EMERGENCY
        val command = stripped.substringBefore(' ').uppercase()
        return when (command) {
            in readOnly -> CommandRisk.READ_ONLY
            in control -> CommandRisk.CONTROL
            in motion -> CommandRisk.MOTION
            else -> CommandRisk.UNKNOWN
        }
    }

    fun playCommand(uci: String, castling: Boolean = false, enPassant: Boolean = false): String {
        require(uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) { "Invalid UCI move: $uci" }
        val flag = if (castling) " C" else if (enPassant) " E" else ""
        return "PLAY $uci$flag"
    }

    fun headCommand(square: String): String {
        require(square.matches(Regex("[a-h][1-8]"))) { "Invalid square: $square" }
        return "HEAD $square"
    }

    fun pieceCommand(from: String, to: String): String {
        require(from != to && from.matches(Regex("[a-h][1-8]")) && to.matches(Regex("[a-h][1-8]"))) {
            "Invalid manual piece move: $from$to"
        }
        return "PIECE $from$to"
    }

    fun squareIndex(name: String): Int {
        val square = name.trim().lowercase()
        require(square.matches(Regex("[a-h][1-8]"))) { "Invalid square: $name" }
        return square[0] - 'a' + (square[1] - '1') * 8
    }

    fun squareName(square: Int): String {
        require(square in 0..63) { "Square is outside the board: $square" }
        return "${'a' + square % 8}${square / 8 + 1}"
    }

    fun queenAligned(source: Int, target: Int): Boolean {
        squareName(source)
        squareName(target)
        val fileDelta = kotlin.math.abs(source % 8 - target % 8)
        val rankDelta = kotlin.math.abs(source / 8 - target / 8)
        return source != target &&
            (fileDelta == 0 || rankDelta == 0 || fileDelta == rankDelta)
    }

    fun splitRouteRuns(path: List<Int>): List<List<Int>> {
        require(path.size >= 2) { "A drag route needs source and destination" }
        val steps = path.zipWithNext(::routeStep)
        val runs = mutableListOf<List<Int>>()
        var runStart = 0
        for (index in 1 until steps.size) {
            if (steps[index] != steps[index - 1]) {
                runs += path.subList(runStart, index + 1).toList()
                runStart = index
            }
        }
        runs += path.subList(runStart, path.size).toList()
        return runs
    }

    fun dragCommand(path: List<Int>): String {
        val runs = splitRouteRuns(path)
        require(runs.size == 1) { "One DRAG command must be a single straight run" }
        val run = runs.single()
        return "DRAG ${squareName(run.first())}${squareName(run.last())}"
    }

    fun parseDragCommand(line: String): DragRoute {
        val fields = line.trim().split(Regex("\\s+"))
        require(fields.size == 2 && fields[0].equals("DRAG", ignoreCase = true) &&
            fields[1].matches(Regex("[a-h][1-8][a-h][1-8]"))) {
            "Malformed DRAG command: $line"
        }
        val source = squareIndex(fields[1].take(2))
        val target = squareIndex(fields[1].drop(2))
        require(source != target) { "DRAG endpoints must differ" }
        val sameFile = source % 8 == target % 8
        val sameRank = source / 8 == target / 8
        require(sameFile || sameRank) { "DRAG must be orthogonally straight" }
        val step = if (sameFile) if (target > source) 8 else -8 else if (target > source) 1 else -1
        val path = buildList {
            var current = source
            add(current)
            while (current != target) {
                current += step
                add(current)
            }
        }
        return DragRoute(source, target, path)
    }

    fun planCommand(
        uci: String,
        captureSquare: Int? = null,
        castlingSide: String? = null,
    ): String {
        val normalized = normalizeUci(uci)
        val castle = when (castlingSide?.lowercase()?.replace("-", "")?.replace("_", "")) {
            null -> null
            "k", "king", "kingside" -> 'k'
            "c", "q", "queen", "queenside" -> 'c'
            else -> error("Invalid castling side: $castlingSide")
        }
        val mode = if (castle != null) {
            require(normalized.length == 4) { "Castling cannot also be a promotion" }
            val allowed = if (castle == 'k') setOf("e1g1", "e8g8") else setOf("e1c1", "e8c8")
            require(normalized in allowed) { "UCI move does not match $castlingSide castling: $uci" }
            castle
        } else normalized.getOrNull(4) ?: '-'
        val capture = captureSquare?.let(::squareName) ?: "--"
        return "PLAN ${normalized.take(4)}$mode$capture"
    }

    fun parsePlanCommand(line: String): PlanRouteRequest {
        val fields = line.trim().split(Regex("\\s+"))
        require(fields.size == 2 && fields[0].equals("PLAN", ignoreCase = true) &&
            fields[1].length == 7) { "Malformed PLAN command: $line" }
        val payload = fields[1].lowercase()
        val baseUci = normalizeUci(payload.take(4))
        val mode = payload[4]
        require(mode in "-qrbnkc") { "Invalid PLAN mode: $mode" }
        if (mode == 'k') require(baseUci in setOf("e1g1", "e8g8")) {
            "King-side PLAN mode does not match its UCI endpoints"
        }
        if (mode == 'c') require(baseUci in setOf("e1c1", "e8c8")) {
            "Queen-side PLAN mode does not match its UCI endpoints"
        }
        val captureText = payload.takeLast(2)
        val capture = if (captureText == "--") null else squareIndex(captureText)
        val move = baseUci + if (mode in "qrbn") mode else ""
        return PlanRouteRequest(move, mode, capture)
    }

    private fun normalizeUci(uci: String): String {
        val normalized = uci.trim().lowercase()
        require(normalized.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) {
            "Invalid UCI move: $uci"
        }
        require(normalized.take(2) != normalized.substring(2, 4)) { "Invalid UCI move: $uci" }
        return normalized
    }

    private fun routeStep(first: Int, second: Int): Int {
        val delta = second - first
        if (delta == 8 || delta == -8) return delta
        if ((delta == 1 || delta == -1) && first / 8 == second / 8) return delta
        error("Route contains a non-orthogonal step ${squareName(first)}->${squareName(second)}")
    }
}

/** Reassembles arbitrarily split BLE notifications and safely bounds corrupted input. */
class LineBuffer(private val maximum: Int = 256) {
    private val bytes = ArrayList<Byte>()
    private var overflowed = false

    @Synchronized
    fun feed(chunk: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        chunk.forEach { byte ->
            val value = byte.toInt() and 0xff
            when {
                value == 10 || value == 13 -> {
                    if (!overflowed && bytes.isNotEmpty()) {
                        lines += bytes.toByteArray().toString(Charsets.US_ASCII).trim()
                    }
                    bytes.clear()
                    overflowed = false
                }
                value in 32..126 && !overflowed -> if (bytes.size < maximum) bytes += byte else {
                    bytes.clear()
                    overflowed = true
                }
            }
        }
        return lines.filter(String::isNotBlank)
    }
}
