package org.openautomaticchessboard.mobile.domain

data class BoardOffsetProfile(val blackSteps: Int, val whiteSteps: Int) {
    init {
        require(blackSteps in 200..600 && whiteSteps in 650..1000) {
            "Board offset is outside the firmware's safe range"
        }
    }

    val answer: String get() = "Black offset $blackSteps; white offset $whiteSteps"
    val command: String get() = "CALSET $blackSteps $whiteSteps"

    companion object {
        fun fromEvent(args: List<String>): BoardOffsetProfile {
            require(args.size == 2) { "Malformed CALPROFILE response" }
            return BoardOffsetProfile(args[0].toInt(), args[1].toInt())
        }
    }
}

fun nudgeCommand(axis: Char, positive: Boolean, coarse: Boolean): String {
    val normalized = axis.uppercaseChar()
    require(normalized == 'X' || normalized == 'Y') { "Axis must be X or Y" }
    return "NUDGE $normalized${if (positive) '+' else '-'} ${if (coarse) 5 else 1}"
}
