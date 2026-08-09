package org.openautomaticchessboard.mobile.domain

import org.openautomaticchessboard.mobile.protocol.FirmwareInfo
import org.openautomaticchessboard.mobile.protocol.Telemetry

data class MonitorState(
    val connected: Boolean = false,
    val connectionText: String = "Disconnected",
    val lastSeenMs: Long? = null,
    val firmware: FirmwareInfo? = null,
    val telemetry: Telemetry? = null,
    val sensorSquares: Set<Int>? = null,
    val sensorHex: String = "",
    val sensorUpdatedMs: Long? = null,
    val expectedSquares: Set<Int> = initialOccupancy,
    val lastError: String = "",
    val motionExpected: Boolean = false,
) {
    fun ageSeconds(nowMs: Long = System.currentTimeMillis()): Double? =
        lastSeenMs?.let { ((nowMs - it).coerceAtLeast(0L)) / 1000.0 }

    fun missingSquares(): Set<Int> = sensorSquares?.let { expectedSquares - it } ?: emptySet()
    fun unexpectedSquares(): Set<Int> = sensorSquares?.let { it - expectedSquares } ?: emptySet()

    fun sequenceName(): String = telemetry?.let { sequenceNames[it.sequence] ?: "Unknown state ${it.sequence}" } ?: "Unknown"

    fun health(nowMs: Long = System.currentTimeMillis()): Pair<String, HealthLevel> {
        val age = ageSeconds(nowMs)
        return when {
            !connected -> "Disconnected" to HealthLevel.BAD
            age == null || age > 12 -> "Connection stale" to HealthLevel.BAD
            telemetry?.motionFault == true -> "Motion fault" to HealthLevel.BAD
            telemetry?.let { !it.buttonAReleased || !it.buttonBReleased } == true -> "Limit/button active" to HealthLevel.WARN
            sensorSquares != null && (missingSquares().isNotEmpty() || unexpectedSquares().isNotEmpty()) ->
                "Physical/logical position differs" to HealthLevel.WARN
            firmware == null -> "Firmware identity unavailable" to HealthLevel.WARN
            "TELEM" in firmware.capabilities && telemetry == null -> "Waiting for telemetry" to HealthLevel.WARN
            "BOARD" in firmware.capabilities && sensorSquares == null -> "Waiting for sensors" to HealthLevel.WARN
            age > 5 -> "Updates delayed" to HealthLevel.WARN
            else -> "Ready" to HealthLevel.GOOD
        }
    }

    fun guidance(): String = when {
        !connected -> "Reconnect to refresh the board. Shown values are last known, not live."
        telemetry?.motionFault == true -> "Cut physical motor power and inspect the mechanism locally."
        telemetry?.let { !it.buttonAReleased || !it.buttonBReleased } == true ->
            "A limit or button is active. Inspect it before calibration or movement."
        sensorSquares != null && (missingSquares().isNotEmpty() || unexpectedSquares().isNotEmpty()) ->
            "Physical sensors differ from the game. Correct or synchronize the position before play."
        telemetry == null -> "Connect and refresh safely to read the board state."
        else -> sequenceGuidance[telemetry.sequence]
            ?: "Follow the board LCD and keep the mechanism in view before motion."
    }

    companion object {
        val initialOccupancy = ((0..15) + (48..63)).toSet()
        val sequenceNames = mapOf(
            0 to "Starting", 1 to "Main menu / idle", 2 to "Position recovery", 3 to "Calibrating",
            4 to "Checking start position", 5 to "Human playing White", 6 to "Human playing Black",
            7 to "Waiting for undo", 8 to "Checking computer move", 9 to "Game over",
            10 to "Motion fault", 11 to "Service menu", 12 to "Sensor service",
            13 to "Service: select file", 14 to "Service: select rank", 15 to "Remote setup check",
            16 to "Remote human turn", 17 to "Waiting for computer move", 18 to "Remote undo required",
            19 to "Checking remote move", 20 to "Waiting for promotion piece",
        )
        private val sequenceGuidance = mapOf(
            1 to "Board is idle and ready for safe diagnostics or a new game.",
            2 to "Carriage position is uncertain. Recalibrate locally before movement.",
            3 to "Calibration is moving. Keep the board clear.",
            4 to "Arrange all pieces in their starting squares and follow the LCD.",
            7 to "Restore the prior physical position, then confirm on the board.",
            10 to "Motion stopped. Inspect locally before fault recovery.",
            15 to "Arrange starting pieces and press physical Button A.",
            16 to "Make your move, then press physical Button A.",
            17 to "The phone may send the next legal computer move.",
            18 to "Invalid move: restore the physical position.",
            20 to "Replace the promoted pawn, then press physical Button A.",
        )
    }
}

enum class HealthLevel { GOOD, WARN, BAD }
