package org.openautomaticchessboard.mobile.domain

/** Narrow board API used by game and route orchestration. */
interface GameBoardChannel {
    val softwareCompatible: Boolean
    val physicalOccupancy: Set<Int>?
    val sensorUpdatedMs: Long?
    val connected: Boolean

    fun sendCommand(command: String): Result<Unit>
    fun beginRouteTransaction(): Result<Unit>
    fun sendRouteCommand(command: String): Result<Unit>
    fun finishRouteTransaction()
    fun abortRouteTransaction(): Result<Unit>
}
