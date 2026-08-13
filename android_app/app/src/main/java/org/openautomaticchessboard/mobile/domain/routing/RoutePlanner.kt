package org.openautomaticchessboard.mobile.domain.routing

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.openautomaticchessboard.mobile.protocol.Protocol
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

private const val BOARD_SQUARES = 64
private const val DISTURBANCE_COST = 1_000_000_000_000L
private const val PICKUP_COST = 100_000_000L
private const val STEP_COST = 10_000L
private const val TURN_COST = 100L
private const val NORTH = 0
private const val EAST = 1
private const val SOUTH = 2
private const val WEST = 3

class PlanningException(message: String) : RuntimeException(message)

data class PieceTask(
    val key: String,
    val start: Int,
    val goal: Int,
    val primary: Boolean = false,
) {
    init {
        validateSquare(start)
        validateSquare(goal)
    }
}

data class PlanningProblem(
    val pieces: List<PieceTask>,
    val moveUci: String,
    val capturedSquare: Int? = null,
    val castlingSide: String? = null,
    val initialPhysicalOccupancy: Set<Int>? = null,
) {
    init {
        require(pieces.map(PieceTask::start).distinct().size == pieces.size) {
            "Two pieces start on the same square"
        }
        require(pieces.map(PieceTask::goal).distinct().size == pieces.size) {
            "Two pieces have the same final square"
        }
        capturedSquare?.let {
            validateSquare(it)
            require(pieces.none { piece -> piece.start == it }) {
                "Captured piece must be excluded from active pieces"
            }
        }
        require(castlingSide == null || castlingSide == "kingside" || castlingSide == "queenside") {
            "Invalid castling side"
        }
        require(pieces.any { it.primary && it.start != it.goal }) {
            "At least one primary piece must change square"
        }
    }

    val initialPositions: IntArray get() = pieces.map(PieceTask::start).toIntArray()
    val goalPositions: IntArray get() = pieces.map(PieceTask::goal).toIntArray()
    val initialOccupancyAfterCapture: Set<Int> get() = initialPositions.toSet()
    val initialOccupancyBeforeCapture: Set<Int> get() = buildSet {
        addAll(initialOccupancyAfterCapture)
        capturedSquare?.let(::add)
    }
}

data class PlannerConfig(
    val timeLimitMillis: Long = 8_000L,
    val maxNodes: Int = 150_000,
    val maxTemporaryPieces: Int = 10,
    val heuristicWeight: Double = 1.25,
    val corridorCandidates: Int = 4,
    val parkingCandidates: Int = 8,
    val dependencyDepth: Int = 4,
    val broadCandidatesPerPiece: Int = 2,
) {
    init {
        require(timeLimitMillis > 0)
        require(maxNodes > 0)
        require(maxTemporaryPieces >= 0)
        require(heuristicWeight >= 1.0)
        require(corridorCandidates > 0 && parkingCandidates > 0)
    }
}

data class Relocation(
    val pieceKey: String,
    val source: Int,
    val target: Int,
    val path: List<Int>,
    val purpose: String,
) {
    init {
        require(path.size >= 2 && path.first() == source && path.last() == target)
        validateOrthogonalPath(path)
    }

    val steps: Int get() = path.size - 1
}

data class PlanStatistics(
    val expandedNodes: Int,
    val generatedNodes: Int,
    val disturbanceBudget: Int,
    val elapsedMillis: Long,
    val searchMode: String,
)

data class MotionPlan(
    val problem: PlanningProblem,
    val relocations: List<Relocation>,
    val statistics: PlanStatistics,
) {
    val temporaryPieceCount: Int get() = relocations
        .filter { it.purpose in setOf("evacuate", "repark", "restore") }
        .map(Relocation::pieceKey).toSet().size
    val dragCount: Int get() = relocations.sumOf { Protocol.splitRouteRuns(it.path).size }
    val pickupCount: Int get() = dragCount + if (problem.capturedSquare != null) 1 else 0
    val carriedSteps: Int get() = relocations.sumOf(Relocation::steps)

    fun validate() {
        val positions = problem.pieces.associate { it.key to it.start }.toMutableMap()
        val occupancy = problem.pieces.associate { it.start to it.key }.toMutableMap()
        relocations.forEach { move ->
            if (positions[move.pieceKey] != move.source || occupancy[move.source] != move.pieceKey) {
                throw PlanningException("Source identity mismatch for ${move.pieceKey}")
            }
            if (move.target in occupancy) throw PlanningException("Target ${squareName(move.target)} is occupied")
            val stationary = occupancy.keys - move.source
            if (move.path.drop(1).any { it in stationary }) {
                throw PlanningException("Route crosses occupied square")
            }
            occupancy.remove(move.source)
            occupancy[move.target] = move.pieceKey
            positions[move.pieceKey] = move.target
        }
        val expected = problem.pieces.associate { it.key to it.goal }
        if (positions != expected) throw PlanningException("Plan does not restore the exact labeled goal")
    }

    fun protocolCommands(): List<String> = buildList {
        add(Protocol.planCommand(problem.moveUci, problem.capturedSquare, problem.castlingSide))
        add("BOARD")
        relocations.forEach { relocation ->
            Protocol.splitRouteRuns(relocation.path).forEach { run ->
                add(Protocol.dragCommand(run))
                add("BOARD")
            }
        }
        add("COMMIT")
    }

    fun describe(): String {
        val capture = if (problem.capturedSquare != null) " + capture removal" else ""
        return "$dragCount verified drags$capture, $temporaryPieceCount temporary pieces, " +
            "$carriedSteps carried squares"
    }
}

private class PositionVector private constructor(
    val values: IntArray,
    private val hash: Int,
) {
    constructor(values: IntArray) : this(values.copyOf(), values.contentHashCode())

    fun moved(index: Int, target: Int): PositionVector {
        val copy = values.copyOf()
        copy[index] = target
        return PositionVector(copy)
    }

    override fun equals(other: Any?): Boolean = other is PositionVector && values.contentEquals(other.values)
    override fun hashCode(): Int = hash
}

private data class SearchState(val positions: PositionVector, val disturbedMask: Long)
private data class SearchAction(
    val pieceIndex: Int,
    val target: Int,
    val path: List<Int>,
    val purpose: String,
    val cost: Long,
)
private data class ParentStep(val previous: SearchState, val action: SearchAction)
private data class SearchResult(val state: SearchState, val parents: Map<SearchState, ParentStep?>)
private data class SearchEntry(val priority: Long, val tie: Long, val cost: Long, val state: SearchState)
private enum class SearchMode { FOCUSED, BROAD, EXHAUSTIVE }

class RearrangementPlanner(private val config: PlannerConfig = PlannerConfig()) {
    private var deadlineNanos = 0L
    private var expandedTotal = 0
    private var generatedTotal = 0
    private val serial = AtomicLong()

    fun plan(problem: PlanningProblem): MotionPlan {
        val started = System.nanoTime()
        deadlineNanos = started + config.timeLimitMillis * 1_000_000L
        expandedTotal = 0
        generatedTotal = 0

        problem.initialPhysicalOccupancy?.let { physical ->
            val expected = problem.initialOccupancyBeforeCapture
            if (physical != expected) {
                val missing = (expected - physical).sorted().joinToString { squareName(it) }
                val unexpected = (physical - expected).sorted().joinToString { squareName(it) }
                throw PlanningException(
                    "Physical occupancy does not match the logical position " +
                        "(missing=[$missing], unexpected=[$unexpected])",
                )
            }
        }

        var initialDisturbed = 0L
        problem.pieces.forEachIndexed { index, piece ->
            if (!piece.primary && piece.start != piece.goal) initialDisturbed = initialDisturbed or (1L shl index)
        }
        val initial = SearchState(PositionVector(problem.initialPositions), initialDisturbed)
        val goal = PositionVector(problem.goalPositions)
        if (initial.positions == goal) {
            return MotionPlan(problem, emptyList(), PlanStatistics(0, 0, 0, 0, "direct"))
        }

        val maximumBudget = min(config.maxTemporaryPieces, problem.pieces.count { !it.primary })
        val minimumBudget = java.lang.Long.bitCount(initialDisturbed)
        for (budget in minimumBudget..maximumBudget) {
            for (mode in SearchMode.entries) {
                val result = search(problem, initial, goal, budget, mode) ?: continue
                val plan = MotionPlan(
                    problem,
                    reconstruct(problem, result),
                    PlanStatistics(
                        expandedTotal,
                        generatedTotal,
                        budget,
                        (System.nanoTime() - started) / 1_000_000L,
                        mode.name.lowercase(),
                    ),
                )
                plan.validate()
                return plan
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        val reason = if (System.nanoTime() >= deadlineNanos) "time limit" else "search limits"
        throw PlanningException(
            "No collision-safe plan found within $reason: $expandedTotal states, " +
                "${elapsed}ms, at most $maximumBudget temporary pieces",
        )
    }

    private fun search(
        problem: PlanningProblem,
        initial: SearchState,
        goal: PositionVector,
        budget: Int,
        mode: SearchMode,
    ): SearchResult? {
        val queue = PriorityQueue<SearchEntry>(compareBy<SearchEntry> { it.priority }.thenBy { it.tie })
        queue += SearchEntry((config.heuristicWeight * heuristic(problem, initial)).toLong(), serial.getAndIncrement(), 0, initial)
        val best = mutableMapOf(initial to 0L)
        val parents = mutableMapOf<SearchState, ParentStep?>(initial to null)
        while (queue.isNotEmpty()) {
            if (System.nanoTime() >= deadlineNanos || expandedTotal >= config.maxNodes) return null
            val entry = queue.remove()
            if (best[entry.state] != entry.cost) continue
            if (entry.state.positions == goal) return SearchResult(entry.state, parents)
            expandedTotal++
            successors(problem, entry.state, budget, mode).forEach { action ->
                var disturbed = entry.state.disturbedMask
                if (!problem.pieces[action.pieceIndex].primary) disturbed = disturbed or (1L shl action.pieceIndex)
                if (java.lang.Long.bitCount(disturbed) > budget) return@forEach
                val next = SearchState(entry.state.positions.moved(action.pieceIndex, action.target), disturbed)
                val cost = entry.cost + action.cost
                if (cost >= (best[next] ?: Long.MAX_VALUE)) return@forEach
                best[next] = cost
                parents[next] = ParentStep(entry.state, action)
                val priority = cost + (config.heuristicWeight * heuristic(problem, next)).toLong()
                queue += SearchEntry(priority, serial.getAndIncrement(), cost, next)
                generatedTotal++
            }
        }
        return null
    }

    private fun heuristic(problem: PlanningProblem, state: SearchState): Long {
        val mismatched = problem.pieces.indices.filter {
            state.positions.values[it] != problem.pieces[it].goal
        }
        if (mismatched.isEmpty()) return 0
        var estimate = mismatched.size * PICKUP_COST
        estimate += mismatched.sumOf {
            manhattan(state.positions.values[it], problem.pieces[it].goal) * STEP_COST
        }
        val occupant = state.positions.values.mapIndexed { index, square -> square to index }.toMap()
        var unavoidable = 0
        mismatched.filter { problem.pieces[it].primary }.forEach { index ->
            val corridor = relaxedShortestPath(
                state.positions.values[index],
                setOf(problem.pieces[index].goal),
                occupant,
                index,
            ) ?: return@forEach
            val blockers = corridor.drop(1).mapNotNull(occupant::get).filter { it != index }.toSet()
            val newSecondary = blockers.count {
                !problem.pieces[it].primary && state.disturbedMask and (1L shl it) == 0L
            }
            unavoidable = maxOf(unavoidable, newSecondary)
        }
        return estimate + unavoidable * DISTURBANCE_COST
    }

    private fun successors(
        problem: PlanningProblem,
        state: SearchState,
        budget: Int,
        mode: SearchMode,
    ): List<SearchAction> {
        val occupant = state.positions.values.mapIndexed { index, square -> square to index }.toMap()
        val occupied = occupant.keys
        val mismatchedPrimary = problem.pieces.indices.filter {
            problem.pieces[it].primary && state.positions.values[it] != problem.pieces[it].goal
        }
        val mismatchedSecondary = problem.pieces.indices.filter {
            !problem.pieces[it].primary && state.positions.values[it] != problem.pieces[it].goal
        }
        val actions = mutableMapOf<Pair<Int, Int>, SearchAction>()
        fun add(action: SearchAction) {
            val key = action.pieceIndex to action.target
            if (action.cost < (actions[key]?.cost ?: Long.MAX_VALUE)) actions[key] = action
        }

        var directPrimary = false
        mismatchedPrimary.forEach { index ->
            findEmptyPath(state.positions.values[index], problem.pieces[index].goal, occupied)
                ?.takeIf { it.size > 1 }?.let {
                    add(makeAction(problem, state, index, it, "primary"))
                    directPrimary = true
                }
        }

        val blockedSecondary = mutableListOf<Int>()
        mismatchedSecondary.forEach { index ->
            val path = findEmptyPath(state.positions.values[index], problem.pieces[index].goal, occupied)
            if (path != null && path.size > 1) add(makeAction(problem, state, index, path, "restore"))
            else blockedSecondary += index
        }

        val obligations = mutableListOf<Int>()
        if (mismatchedPrimary.isNotEmpty() && !directPrimary) obligations += mismatchedPrimary
        obligations += blockedSecondary
        obligations.take(2).forEach { obligation ->
            relaxedCorridors(
                state.positions.values[obligation],
                problem.pieces[obligation].goal,
                occupant,
                obligation,
                config.corridorCandidates,
            ).forEach { corridor ->
                val blockers = corridor.drop(1).mapNotNull(occupant::get).filter { it != obligation }
                addDependencyActions(
                    problem, state, occupant, blockers, corridor.toSet(), actions, budget, mode,
                )
            }
            if (!problem.pieces[obligation].primary) {
                parkingActions(
                    problem, state, occupant, obligation, emptySet(),
                    maxOf(2, config.parkingCandidates / 2), mode != SearchMode.FOCUSED,
                ).forEach(::add)
            }
        }

        if (mismatchedSecondary.isNotEmpty()) {
            problem.pieces.indices.filter {
                problem.pieces[it].primary && state.positions.values[it] == problem.pieces[it].goal
            }.forEach { index ->
                parkingActions(
                    problem,
                    state,
                    occupant,
                    index,
                    problem.pieces.filter { !it.primary }.map(PieceTask::goal).toSet(),
                    maxOf(3, config.parkingCandidates / 2),
                    mode != SearchMode.FOCUSED,
                    "stage",
                ).forEach(::add)
            }
        }

        if (mode == SearchMode.BROAD || mode == SearchMode.EXHAUSTIVE) {
            problem.pieces.indices.forEach { index ->
                if (!problem.pieces[index].primary && state.disturbedMask and (1L shl index) == 0L &&
                    java.lang.Long.bitCount(state.disturbedMask) >= budget
                ) return@forEach
                parkingActions(
                    problem, state, occupant, index, emptySet(),
                    config.broadCandidatesPerPiece, true,
                ).forEach(::add)
            }
        }

        if (mode == SearchMode.EXHAUSTIVE || actions.isEmpty()) {
            state.positions.values.forEachIndexed { index, source ->
                if (!problem.pieces[index].primary && state.disturbedMask and (1L shl index) == 0L &&
                    java.lang.Long.bitCount(state.disturbedMask) >= budget
                ) return@forEachIndexed
                neighbors(source).filter { it.square !in occupied }.forEach { neighbor ->
                    val path = listOf(source, neighbor.square)
                    add(makeAction(problem, state, index, path, purpose(problem, state, index, neighbor.square)))
                }
            }
        }
        return actions.values.sortedWith(compareBy<SearchAction> { it.cost }.thenBy { it.pieceIndex }.thenBy { it.target })
    }

    private fun addDependencyActions(
        problem: PlanningProblem,
        state: SearchState,
        occupant: Map<Int, Int>,
        initialBlockers: List<Int>,
        forbidden: Set<Int>,
        output: MutableMap<Pair<Int, Int>, SearchAction>,
        budget: Int,
        mode: SearchMode,
    ) {
        data class Dependency(val blocker: Int, val reserved: Set<Int>, val depth: Int)
        val queue = ArrayDeque(initialBlockers.map { Dependency(it, forbidden, 0) })
        val seen = mutableSetOf<Int>()
        while (queue.isNotEmpty()) {
            val dependency = queue.removeFirst()
            val blocker = dependency.blocker
            if (!seen.add(blocker)) continue
            if (!problem.pieces[blocker].primary && state.disturbedMask and (1L shl blocker) == 0L &&
                java.lang.Long.bitCount(state.disturbedMask) >= budget
            ) continue
            val parking = parkingActions(
                problem, state, occupant, blocker, dependency.reserved,
                config.parkingCandidates, mode != SearchMode.FOCUSED,
            )
            parking.forEach { action ->
                val key = action.pieceIndex to action.target
                if (action.cost < (output[key]?.cost ?: Long.MAX_VALUE)) output[key] = action
            }
            if (parking.isNotEmpty() || dependency.depth >= config.dependencyDepth) continue
            val targets = (0 until BOARD_SQUARES).filter {
                it !in occupant && it !in dependency.reserved
            }.toSet()
            if (targets.isEmpty()) continue
            val escape = relaxedShortestPath(
                state.positions.values[blocker], targets, occupant, blocker,
            ) ?: continue
            val nextForbidden = dependency.reserved + escape
            escape.drop(1).mapNotNull(occupant::get).filter { it != blocker && it !in seen }.forEach {
                queue.add(Dependency(it, nextForbidden, dependency.depth + 1))
            }
        }
    }

    private fun parkingActions(
        problem: PlanningProblem,
        state: SearchState,
        occupant: Map<Int, Int>,
        pieceIndex: Int,
        forbidden: Set<Int>,
        limit: Int,
        allowReserved: Boolean,
        requestedPurpose: String? = null,
    ): List<SearchAction> {
        val source = state.positions.values[pieceIndex]
        val occupied = occupant.keys
        val reachable = reachableEmptySquares(source, occupied)
        if (reachable.isEmpty()) return emptyList()
        val goalsOfOthers = problem.pieces.indices.filter { it != pieceIndex }.map { problem.pieces[it].goal }.toSet()
        val freeAfterLift = (0 until BOARD_SQUARES).toSet() - (occupied - source)
        val articulation = articulationPoints(freeAfterLift)
        data class Candidate(
            val reserved: Int,
            val articulation: Int,
            val steps: Int,
            val distanceFromStart: Int,
            val negativeMobility: Int,
            val turns: Int,
            val target: Int,
            val path: List<Int>,
        )
        val candidates = reachable.mapNotNull { target ->
            val reserved = target in forbidden || target in goalsOfOthers
            if (reserved && !allowReserved) return@mapNotNull null
            val path = findEmptyPath(source, target, occupied) ?: return@mapNotNull null
            if (path.size < 2) return@mapNotNull null
            val mobility = neighbors(target).count { it.square !in (occupied - source) && it.square != target }
            Candidate(
                if (reserved) 1 else 0,
                if (target in articulation) 1 else 0,
                path.size - 1,
                manhattan(target, problem.pieces[pieceIndex].start).toInt(),
                -mobility,
                routeTurns(path),
                target,
                path,
            )
        }
        if (candidates.isEmpty() && !allowReserved) {
            return parkingActions(
                problem, state, occupant, pieceIndex, forbidden, limit, true, requestedPurpose,
            )
        }
        val comparator = compareBy<Candidate> { it.reserved }.thenBy { it.articulation }
            .thenBy { it.steps }.thenBy { it.distanceFromStart }.thenBy { it.negativeMobility }
            .thenBy { it.turns }.thenBy { it.target }
        return candidates.sortedWith(comparator).take(limit).map { candidate ->
            makeAction(
                problem,
                state,
                pieceIndex,
                candidate.path,
                requestedPurpose ?: purpose(problem, state, pieceIndex, candidate.target),
            )
        }
    }

    private fun purpose(problem: PlanningProblem, state: SearchState, pieceIndex: Int, target: Int): String {
        val piece = problem.pieces[pieceIndex]
        if (piece.primary) return if (target == piece.goal) "primary" else "stage"
        if (target == piece.goal) return "restore"
        return if (state.positions.values[pieceIndex] == piece.start &&
            state.disturbedMask and (1L shl pieceIndex) == 0L
        ) "evacuate" else "repark"
    }

    private fun makeAction(
        problem: PlanningProblem,
        state: SearchState,
        pieceIndex: Int,
        path: List<Int>,
        purpose: String,
    ): SearchAction {
        val piece = problem.pieces[pieceIndex]
        val newlyDisturbed = !piece.primary && state.disturbedMask and (1L shl pieceIndex) == 0L
        val stationary = state.positions.values.toSet() - state.positions.values[pieceIndex]
        val turns = routeTurns(path)
        val cost = (if (newlyDisturbed) DISTURBANCE_COST else 0L) +
            (turns + 1) * PICKUP_COST + (path.size - 1) * STEP_COST +
            turns * TURN_COST + clearanceRisk(path, stationary)
        return SearchAction(pieceIndex, path.last(), path, purpose, cost)
    }

    private fun reconstruct(problem: PlanningProblem, result: SearchResult): List<Relocation> {
        val reverse = mutableListOf<Relocation>()
        var state = result.state
        while (true) {
            val step = result.parents[state] ?: break
            reverse += Relocation(
                problem.pieces[step.action.pieceIndex].key,
                step.action.path.first(),
                step.action.path.last(),
                step.action.path,
                step.action.purpose,
            )
            state = step.previous
        }
        return reverse.asReversed()
    }
}

fun planningProblemFromChess(
    board: Board,
    move: Move,
    physicalOccupancy: Set<Int>? = null,
): PlanningProblem {
    if (!board.isMoveLegal(move, true)) throw PlanningException("Illegal chess move: $move")
    val from = move.from.ordinal
    val to = move.to.ordinal
    val movingPiece = board.getPiece(move.from)
    if (movingPiece == Piece.NONE) throw PlanningException("Main piece is missing from its logical source")
    val fromFile = from % 8
    val toFile = to % 8
    val capturedSquare = when {
        movingPiece.pieceType == PieceType.PAWN && board.getPiece(move.to) == Piece.NONE && fromFile != toFile ->
            toFile + (from / 8) * 8
        board.getPiece(move.to) != Piece.NONE -> to
        else -> null
    }
    val pieceBySquare = (0 until BOARD_SQUARES).mapNotNull { square ->
        val piece = board.getPiece(Square.values()[square])
        if (piece == Piece.NONE) null else square to piece
    }.toMap()
    val goals = pieceBySquare.keys.filter { it != capturedSquare }.associateWith { it }.toMutableMap()
    goals[from] = to
    val primary = mutableSetOf(from)
    var castlingSide: String? = null
    val castling = movingPiece.pieceType == PieceType.KING && abs(fromFile - toFile) == 2
    if (castling) {
        val rankBase = (from / 8) * 8
        val kingSide = toFile == 6
        val rookSource = rankBase + if (kingSide) 7 else 0
        val rookTarget = rankBase + if (kingSide) 5 else 3
        if (rookSource !in pieceBySquare) throw PlanningException("Castling rook is missing")
        goals[rookSource] = rookTarget
        primary += rookSource
        castlingSide = if (kingSide) "kingside" else "queenside"
    }
    val pieces = goals.keys.sorted().map { square ->
        PieceTask(
            "${pieceBySquare.getValue(square).name}@${squareName(square)}",
            square,
            goals.getValue(square),
            square in primary,
        )
    }
    return PlanningProblem(
        pieces,
        move.toString().lowercase(),
        capturedSquare,
        castlingSide,
        physicalOccupancy,
    )
}

fun validateSquare(square: Int) {
    require(square in 0 until BOARD_SQUARES) { "Square is outside the board: $square" }
}

fun squareName(square: Int): String = Protocol.squareName(square)

fun parseSquare(name: String): Int = Protocol.squareIndex(name)

private data class Neighbor(val square: Int, val direction: Int)

private fun neighbors(square: Int): List<Neighbor> = buildList {
    val file = square % 8
    val rank = square / 8
    if (rank < 7) add(Neighbor(square + 8, NORTH))
    if (file < 7) add(Neighbor(square + 1, EAST))
    if (rank > 0) add(Neighbor(square - 8, SOUTH))
    if (file > 0) add(Neighbor(square - 1, WEST))
}

private fun directionBetween(first: Int, second: Int): Int = when {
    second - first == 8 -> NORTH
    second - first == -8 -> SOUTH
    second - first == 1 && first / 8 == second / 8 -> EAST
    second - first == -1 && first / 8 == second / 8 -> WEST
    else -> error("${squareName(first)}->${squareName(second)} is not orthogonal")
}

private fun validateOrthogonalPath(path: List<Int>) {
    require(path.isNotEmpty())
    path.forEach(::validateSquare)
    path.zipWithNext().forEach { (first, second) -> directionBetween(first, second) }
}

private fun manhattan(first: Int, second: Int): Long =
    (abs(first % 8 - second % 8) + abs(first / 8 - second / 8)).toLong()

private fun routeTurns(path: List<Int>): Int {
    val directions = path.zipWithNext(::directionBetween)
    return directions.zipWithNext().count { (first, second) -> first != second }
}

private fun clearanceRisk(path: List<Int>, stationary: Set<Int>): Long = path.drop(1).sumOf { square ->
    neighbors(square).count { it.square in stationary }.toLong()
}

private data class Heading(val square: Int, val direction: Int)
private data class PathEntry(val priority: Long, val cost: Long, val tie: Long, val state: Heading)

private fun reconstructPath(parent: Map<Heading, Heading?>, end: Heading): List<Int> {
    val reverse = mutableListOf<Int>()
    var state: Heading? = end
    while (state != null) {
        reverse += state.square
        state = parent[state]
    }
    return reverse.asReversed()
}

private fun findEmptyPath(start: Int, goal: Int, occupied: Set<Int>): List<Int>? {
    if (start == goal) return listOf(start)
    val blocked = occupied - start
    if (goal in blocked) return null
    val serial = AtomicLong()
    val initial = Heading(start, -1)
    val queue = PriorityQueue<PathEntry>(compareBy<PathEntry> { it.priority }.thenBy { it.cost }.thenBy { it.tie })
    queue += PathEntry(manhattan(start, goal) * STEP_COST, 0, serial.getAndIncrement(), initial)
    val best = mutableMapOf(initial to 0L)
    val parent = mutableMapOf<Heading, Heading?>(initial to null)
    while (queue.isNotEmpty()) {
        val entry = queue.remove()
        if (best[entry.state] != entry.cost) continue
        if (entry.state.square == goal) return reconstructPath(parent, entry.state)
        neighbors(entry.state.square).sortedWith(compareBy<Neighbor> { manhattan(it.square, goal) }.thenBy { it.direction })
            .forEach { neighbor ->
                if (neighbor.square in blocked) return@forEach
                val turn = if (entry.state.direction != -1 && entry.state.direction != neighbor.direction) 1 else 0
                val sideRisk = neighbors(neighbor.square).count { it.square in blocked }
                val cost = entry.cost + STEP_COST + turn * TURN_COST + sideRisk
                val next = Heading(neighbor.square, neighbor.direction)
                if (cost >= (best[next] ?: Long.MAX_VALUE)) return@forEach
                best[next] = cost
                parent[next] = entry.state
                queue += PathEntry(
                    cost + manhattan(neighbor.square, goal) * STEP_COST,
                    cost,
                    serial.getAndIncrement(),
                    next,
                )
            }
    }
    return null
}

private fun reachableEmptySquares(start: Int, occupied: Set<Int>): Set<Int> {
    val blocked = occupied - start
    val reached = mutableSetOf(start)
    val frontier = ArrayDeque(listOf(start))
    while (frontier.isNotEmpty()) {
        neighbors(frontier.removeLast()).forEach { neighbor ->
            if (neighbor.square !in blocked && reached.add(neighbor.square)) frontier.add(neighbor.square)
        }
    }
    reached.remove(start)
    return reached
}

private data class RelaxedCost(val blockers: Int, val steps: Int, val turns: Int) : Comparable<RelaxedCost> {
    override fun compareTo(other: RelaxedCost): Int = compareValuesBy(
        this, other, RelaxedCost::blockers, RelaxedCost::steps, RelaxedCost::turns,
    )
}
private data class RelaxedEntry(val cost: RelaxedCost, val tie: Long, val state: Heading)

private fun relaxedShortestPath(
    start: Int,
    goals: Set<Int>,
    occupant: Map<Int, Int>,
    movingPiece: Int,
    bannedEdges: Set<Pair<Int, Int>> = emptySet(),
    bannedNodes: Set<Int> = emptySet(),
): List<Int>? {
    if (start in goals) return listOf(start)
    val serial = AtomicLong()
    val initial = Heading(start, -1)
    val queue = PriorityQueue<RelaxedEntry>(compareBy<RelaxedEntry> { it.cost }.thenBy { it.tie })
    queue += RelaxedEntry(RelaxedCost(0, 0, 0), serial.getAndIncrement(), initial)
    val best = mutableMapOf(initial to RelaxedCost(0, 0, 0))
    val parent = mutableMapOf<Heading, Heading?>(initial to null)
    while (queue.isNotEmpty()) {
        val entry = queue.remove()
        if (best[entry.state] != entry.cost) continue
        if (entry.state.square in goals) return reconstructPath(parent, entry.state)
        neighbors(entry.state.square).forEach { neighbor ->
            if (neighbor.square in bannedNodes || entry.state.square to neighbor.square in bannedEdges) return@forEach
            val occupiedByOther = occupant[neighbor.square]?.let { it != movingPiece } == true
            val cost = RelaxedCost(
                entry.cost.blockers + if (occupiedByOther) 1 else 0,
                entry.cost.steps + 1,
                entry.cost.turns + if (entry.state.direction != -1 && entry.state.direction != neighbor.direction) 1 else 0,
            )
            val next = Heading(neighbor.square, neighbor.direction)
            if (cost >= (best[next] ?: RelaxedCost(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))) return@forEach
            best[next] = cost
            parent[next] = entry.state
            queue += RelaxedEntry(cost, serial.getAndIncrement(), next)
        }
    }
    return null
}

private fun relaxedCorridors(
    start: Int,
    goal: Int,
    occupant: Map<Int, Int>,
    movingPiece: Int,
    limit: Int,
): List<List<Int>> {
    val first = relaxedShortestPath(start, setOf(goal), occupant, movingPiece) ?: return emptyList()
    fun score(path: List<Int>) = RelaxedCost(
        path.drop(1).count { occupant[it]?.let { piece -> piece != movingPiece } == true },
        path.size - 1,
        routeTurns(path),
    )
    val found = linkedMapOf(first to score(first))
    val frontier = ArrayDeque(listOf(first))
    while (frontier.isNotEmpty() && found.size < maxOf(limit * 4, limit + 1)) {
        val base = frontier.removeFirst()
        base.zipWithNext().forEach { edge ->
            val candidate = relaxedShortestPath(
                start, setOf(goal), occupant, movingPiece, setOf(edge),
            ) ?: return@forEach
            if (candidate !in found) {
                found[candidate] = score(candidate)
                frontier.add(candidate)
            }
        }
    }
    return found.entries.sortedWith(compareBy<Map.Entry<List<Int>, RelaxedCost>> { it.value }.thenBy { it.key.joinToString(",") })
        .take(limit).map { it.key }
}

private fun articulationPoints(vertices: Set<Int>): Set<Int> {
    val discovery = mutableMapOf<Int, Int>()
    val low = mutableMapOf<Int, Int>()
    val parent = mutableMapOf<Int, Int?>()
    val points = mutableSetOf<Int>()
    var clock = 0
    fun visit(vertex: Int) {
        discovery[vertex] = clock
        low[vertex] = clock++
        var children = 0
        neighbors(vertex).filter { it.square in vertices }.forEach { neighbor ->
            val next = neighbor.square
            if (next !in discovery) {
                parent[next] = vertex
                children++
                visit(next)
                low[vertex] = min(low.getValue(vertex), low.getValue(next))
                if (parent[vertex] == null && children > 1) points += vertex
                if (parent[vertex] != null && low.getValue(next) >= discovery.getValue(vertex)) points += vertex
            } else if (next != parent[vertex]) {
                low[vertex] = min(low.getValue(vertex), discovery.getValue(next))
            }
        }
    }
    vertices.forEach { vertex ->
        if (vertex !in discovery) {
            parent[vertex] = null
            visit(vertex)
        }
    }
    return points
}
