package org.openautomaticchessboard.mobile.domain

import org.openautomaticchessboard.mobile.protocol.GeometrySettings

data class AlignmentPoint(val square: String, val offsetX: Int, val offsetY: Int) {
    val file: Int get() = square[0].lowercaseChar() - 'a' + 1
    val rank: Int get() = square[1] - '0'
}

data class GeometrySourceValues(
    val filePitch: Int,
    val rankPitch: Int,
    val blackPark: Int,
    val whitePark: Int,
) {
    fun firmwareLines(): String = listOf(
        "FILE_PITCH_STEPS = ${filePitch}U * MOTOR_MICROSTEPS",
        "RANK_PITCH_STEPS = ${rankPitch}U * MOTOR_MICROSTEPS",
        "CALIBRATION_PARK_BLACK_STEPS = ${blackPark}U * MOTOR_MICROSTEPS",
        "CALIBRATION_PARK_WHITE_STEPS = ${whitePark}U * MOTOR_MICROSTEPS",
    ).joinToString("\n")
}

fun roundedDivide(numerator: Int, denominator: Int): Int {
    require(denominator != 0) { "Measurement coordinates must differ" }
    val sign = if (numerator.toLong() * denominator < 0) -1 else 1
    return sign * ((kotlin.math.abs(numerator) + kotlin.math.abs(denominator) / 2) /
        kotlin.math.abs(denominator))
}

fun calculateGeometry(
    first: AlignmentPoint,
    second: AlignmentPoint,
    current: GeometrySettings,
): GeometrySourceValues {
    require(first.file != second.file && first.rank != second.rank) {
        "Choose points with different files and different ranks"
    }
    val fileChange = roundedDivide(second.offsetX - first.offsetX, second.file - first.file)
    val rankChange = roundedDivide(second.offsetY - first.offsetY, second.rank - first.rank)
    val originX = first.offsetX - (first.file - 5) * fileChange
    val originY = first.offsetY - (first.rank - 6) * rankChange
    return GeometrySourceValues(
        roundedDivide(current.filePitch + fileChange, current.microsteps),
        roundedDivide(current.rankPitch + rankChange, current.microsteps),
        roundedDivide(current.blackPark - originY, current.microsteps),
        roundedDivide(current.whitePark + originX, current.microsteps),
    )
}
