package org.openautomaticchessboard.mobile.domain

import org.openautomaticchessboard.mobile.protocol.Telemetry
import org.openautomaticchessboard.mobile.protocol.Protocol

enum class ManualMoveMode { HEAD_ONLY, MOVE_PIECE }

data class ManualSelection(
    val mode: ManualMoveMode = ManualMoveMode.HEAD_ONLY,
    val source: Int? = null,
    val target: Int? = null,
) {
    val highlighted: Set<Int> get() = setOfNotNull(source, target)

    fun withMode(next: ManualMoveMode): ManualSelection =
        if (next == mode) this else ManualSelection(next)

    fun choose(square: Int, occupied: Set<Int>?): SelectionResult {
        require(square in 0..63) { "Square is outside the board" }
        return when (mode) {
            ManualMoveMode.HEAD_ONLY -> SelectionResult(copy(source = null, target = square), "Head target ${squareName(square)}")
            ManualMoveMode.MOVE_PIECE -> when {
                source == null && occupied == null -> SelectionResult(this, "Refresh board sensors before choosing a piece")
                source == null && square !in occupied.orEmpty() -> SelectionResult(this, "Choose a square that currently contains a piece")
                source == null -> SelectionResult(copy(source = square, target = null), "Piece selected at ${squareName(square)}; choose an empty destination")
                square == source -> SelectionResult(copy(source = null, target = null), "Source cleared; choose a piece")
                occupied != null && square in occupied -> SelectionResult(this, "Destination ${squareName(square)} is occupied")
                !Protocol.queenAligned(source, square) -> SelectionResult(
                    this,
                    "Choose a destination on the same file, rank, or diagonal",
                )
                else -> SelectionResult(copy(target = square), "Move ${squareName(source)} to ${squareName(square)}")
            }
        }
    }

    fun command(): String? = when (mode) {
        ManualMoveMode.HEAD_ONLY -> target?.let { "HEAD ${squareName(it)}" }
        ManualMoveMode.MOVE_PIECE -> if (source != null && target != null) {
            if (Protocol.queenAligned(source, target)) {
                "PIECE ${squareName(source)}${squareName(target)}"
            } else null
        } else null
    }

    companion object {
        fun squareName(square: Int): String = "${('a'.code + square % 8).toChar()}${square / 8 + 1}"
    }
}

data class SelectionResult(val selection: ManualSelection, val message: String)

object ManualVerification {
    const val CALIBRATION_SQUARE = "e6"

    fun positionIsTrusted(telemetry: Telemetry?): Boolean = telemetry?.let {
        it.homed && !it.motionFault && it.trolleyX in 1..8 && it.trolleyY in 1..8
    } == true

    fun trustedPosition(telemetry: Telemetry?): Pair<Int, Int>? = telemetry
        ?.takeIf(::positionIsTrusted)
        ?.let { it.trolleyX - 1 to it.trolleyY - 1 }

    fun calibrationMatches(reportedSquare: String?, telemetry: Telemetry?): Boolean =
        reportedSquare?.lowercase() == CALIBRATION_SQUARE &&
            telemetry?.homed == true && !telemetry.motionFault && !telemetry.magnetOn &&
            telemetry.trolleyX == 5 && telemetry.trolleyY == 6

    fun headMoveMatches(target: Int, telemetry: Telemetry?): Boolean = telemetry?.let {
        positionIsTrusted(it) && !it.magnetOn &&
            it.trolleyX == target % 8 + 1 && it.trolleyY == target / 8 + 1
    } == true

    fun pieceMoveMatches(source: Int, target: Int, sensors: Set<Int>?): Boolean =
        sensors != null && source !in sensors && target in sensors
}
