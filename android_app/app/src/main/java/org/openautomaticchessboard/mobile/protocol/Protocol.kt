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

enum class CommandRisk { READ_ONLY, CONTROL, MOTION, EMERGENCY, UNKNOWN }

object Protocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

    private val readOnly = setOf("PING", "HELLO", "INFO", "STATUS", "TELEM", "BOARD", "BTTEST", "SWTEST")
    private val control = setOf("STOP", "REJECT", "GAMEOVER")
    private val motion = setOf("START", "PLAY", "ACCEPT", "CALIBRATE", "HEAD", "PIECE", "PATH", "JOG")

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
