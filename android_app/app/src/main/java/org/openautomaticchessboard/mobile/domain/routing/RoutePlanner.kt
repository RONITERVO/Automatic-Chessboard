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
private val CAPTURE_EDGE_EXITS = (0 until BOARD_SQUARES step 8).toSet()
private const val NORTH = 0
private const val EAST = 1
private const val SOUTH = 2
private const val WEST = 3

class PlanningException(message: String) : RuntimeException(message)

data class FeasibilityAnalysis(
    val status: String,
    val reason: String,
    val holesBeforeCapture: Int,
    val holesAfterCapture: Int,
)

private data class Objective(
    val disturbed: Long = 0,
    val pickups: Long = 0,
    val steps: Long = 0,
    val turns: Long = 0,
) : Comparable<Objective> {
    operator fun plus(other: Objective) = Objective(
        disturbed + other.disturbed,
        pickups + other.pickups,
        steps + other.steps,
        turns + other.turns,
    )

    fun weighted(heuristic: Objective, weight: Double) = Objective(
        disturbed + (weight * heuristic.disturbed).toLong(),
        pickups + (weight * heuristic.pickups).toLong(),
        steps + (weight * heuristic.steps).toLong(),
        turns + (weight * heuristic.turns).toLong(),
    )

    override fun compareTo(other: Objective): Int = compareValuesBy(
        this,
        other,
        Objective::disturbed,
        Objective::pickups,
        Objective::steps,
        Objective::turns,
    )
}

data class PieceTask(
    val key: String,
    val start: Int,
    val goal: Int,
    val primary: Boolean = false,
) {
    init {
        require(key.isNotEmpty()) { "Piece key cannot be empty" }
        validateSquare(start)
        validateSquare(goal)
    }
}

data class PlanningProblem(
    val pieces: List<PieceTask>,
    val moveUci: String = "",
    val capturedSquare: Int? = null,
    val castlingSide: String? = null,
    val initialPhysicalOccupancy: Set<Int>? = null,
    val deferredCapture: Boolean = false,
    val edgeCaptureExit: Boolean = false,
) {
    init {
        require(pieces.map(PieceTask::key).distinct().size == pieces.size) {
            "Piece keys must be unique"
        }
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
    val heuristicWeight: Double = 1.0,
    val corridorCandidates: Int = 4,
    val parkingCandidates: Int = 8,
    val dependencyDepth: Int = 4,
    val broadCandidatesPerPiece: Int = 2,
    val exactSearch: Boolean = false,
    val constructiveFallback: Boolean = true,
) {
    init {
        require(timeLimitMillis > 0)
        require(maxNodes > 0)
        require(maxTemporaryPieces >= 0)
        require(heuristicWeight >= 1.0)
        require(corridorCandidates > 0 && parkingCandidates > 0)
        require(dependencyDepth >= 0)
        require(broadCandidatesPerPiece > 0)
        require(!exactSearch || heuristicWeight == 1.0) {
            "exactSearch requires heuristicWeight=1.0"
        }
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
    val turns: Int get() = routeTurns(path)
}

data class PlanStatistics(
    val expandedNodes: Int,
    val generatedNodes: Int,
    val disturbanceBudget: Int,
    val elapsedMillis: Long,
    val searchMode: String,
    val optimal: Boolean = false,
)

data class MotionPlan(
    val problem: PlanningProblem,
    val relocations: List<Relocation>,
    val statistics: PlanStatistics,
    val captureRemovalIndex: Int? = null,
    val capturePath: List<Int> = emptyList(),
) {
    val temporaryPieceCount: Int get() = relocations
        .filter { it.purpose in setOf("evacuate", "repark", "restore") }
        .map(Relocation::pieceKey).toSet().size
    val dragCount: Int get() =
        (if (capturePath.size > 1) Protocol.splitRouteRuns(capturePath).size else 0) +
            relocations.sumOf { Protocol.splitRouteRuns(it.path).size }
    val pickupCount: Int get() = dragCount + if (problem.capturedSquare != null) 1 else 0
    val carriedSteps: Int get() = maxOf(0, capturePath.size - 1) + relocations.sumOf(Relocation::steps)
    val objective: List<Int> get() = listOf(
        temporaryPieceCount,
        pickupCount,
        carriedSteps,
        (if (capturePath.size > 1) routeTurns(capturePath) else 0) + relocations.sumOf(Relocation::turns),
    )

    fun validate() {
        val positions = problem.pieces.associate { it.key to it.start }.toMutableMap()
        val occupancy = problem.pieces.associate { it.start to it.key }.toMutableMap()
        var capture = problem.capturedSquare
        var capturePending = capture != null && problem.deferredCapture
        if (capturePending) {
            occupancy[checkNotNull(capture)] = "<captured>"
            if (captureRemovalIndex == null) throw PlanningException("Deferred capture has no removal step")
        } else if (captureRemovalIndex != null) {
            throw PlanningException("Unexpected capture-removal step")
        } else if (capturePath.isNotEmpty()) {
            throw PlanningException("Unexpected capture path")
        }
        fun removeCapture() {
            val square = capture
            if (!capturePending || square == null || occupancy[square] != "<captured>") {
                throw PlanningException("Capture-removal state is inconsistent")
            }
            if (problem.edgeCaptureExit) {
                if (capturePath.isEmpty() || capturePath.first() != square || capturePath.last() % 8 != 0) {
                    throw PlanningException("Captured piece has no valid a-file exit path")
                }
                validateOrthogonalPath(capturePath)
                val stationary = occupancy.keys - square
                if (capturePath.drop(1).any { it in stationary }) {
                    throw PlanningException("Capture route crosses an occupied square")
                }
                occupancy.remove(square)
                capture = capturePath.last()
                occupancy[checkNotNull(capture)] = "<captured>"
            } else if (capturePath.isNotEmpty()) {
                throw PlanningException("Legacy capture plan unexpectedly carries an edge path")
            } else if (findCaptureExitRank(square, occupancy.keys) == null) {
                throw PlanningException("Captured piece has no collision-safe exit lane")
            }
            occupancy.remove(checkNotNull(capture))
            capturePending = false
        }
        relocations.forEachIndexed { index, move ->
            if (index == captureRemovalIndex) removeCapture()
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
        if (captureRemovalIndex == relocations.size) removeCapture()
        if (capturePending) throw PlanningException("Captured piece was never removed")
        val expected = problem.pieces.associate { it.key to it.goal }
        if (positions != expected) throw PlanningException("Plan does not restore the exact labeled goal")
    }

    fun protocolCommands(): List<String> = buildList {
        add(Protocol.planCommand(problem.moveUci, problem.capturedSquare, problem.castlingSide))
        add("BOARD")
        fun appendCapture() {
            if (capturePath.size > 1) {
                Protocol.splitRouteRuns(capturePath).forEach { run ->
                    add(Protocol.dragCommand(run))
                    add("BOARD")
                }
            }
            add(Protocol.removeCommand(problem.capturedSquare))
            add("BOARD")
        }
        relocations.forEachIndexed { index, relocation ->
            if (index == captureRemovalIndex) {
                appendCapture()
            }
            Protocol.splitRouteRuns(relocation.path).forEach { run ->
                add(Protocol.dragCommand(run))
                add("BOARD")
            }
        }
        if (captureRemovalIndex == relocations.size) {
            appendCapture()
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

private data class SearchState(
    val positions: PositionVector,
    val disturbedMask: Long,
    val captureRemoved: Boolean,
    val lastPiece: Int = -1,
)
private data class SearchAction(
    val pieceIndex: Int,
    val target: Int,
    val path: List<Int>,
    val purpose: String,
    val cost: Objective,
)
private data class ParentStep(val previous: SearchState, val action: SearchAction)
private data class SearchResult(val state: SearchState, val parents: Map<SearchState, ParentStep?>)
private data class ReconstructedPlan(
    val relocations: List<Relocation>,
    val captureIndex: Int?,
    val capturePath: List<Int>,
)
private data class SearchEntry(
    val priority: Objective,
    val tie: Long,
    val cost: Objective,
    val state: SearchState,
)
private data class CaptureLane(
    val blockerCount: Int,
    val preference: Int,
    val forbidden: Set<Int>,
    val blockers: List<Int>,
)
private data class CaptureLaneSelection(
    val immediate: SearchAction? = null,
    val lanes: List<CaptureLane> = emptyList(),
)
private enum class SearchMode { FOCUSED, BROAD, EXHAUSTIVE }

private fun planObjective(plan: MotionPlan) = Objective(
    plan.temporaryPieceCount.toLong(),
    plan.pickupCount.toLong(),
    plan.carriedSteps.toLong(),
    plan.objective[3].toLong(),
)

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

        val feasibility = analyzeFeasibility(problem)
        if (feasibility.status == "proven_impossible") {
            throw PlanningException("Proven physically impossible: ${feasibility.reason}")
        }

        var initialDisturbed = 0L
        problem.pieces.forEachIndexed { index, piece ->
            if (!piece.primary && piece.start != piece.goal) initialDisturbed = initialDisturbed or (1L shl index)
        }
        val initial = SearchState(
            PositionVector(problem.initialPositions),
            initialDisturbed,
            problem.capturedSquare == null || !problem.deferredCapture,
        )
        val goal = PositionVector(problem.goalPositions)
        if (initial.positions == goal && initial.captureRemoved) {
            return MotionPlan(problem, emptyList(), PlanStatistics(0, 0, 0, 0, "direct", true))
        }

        val incumbent = if (config.constructiveFallback && !config.exactSearch) {
            constructivePlan(problem, started)
        } else {
            null
        }
        var maximumBudget = min(config.maxTemporaryPieces, problem.pieces.count { !it.primary })
        if (incumbent != null) {
            maximumBudget = min(maximumBudget, incumbent.temporaryPieceCount)
        }
        val minimumBudget = java.lang.Long.bitCount(initialDisturbed)
        val modes = if (config.exactSearch) listOf(SearchMode.EXHAUSTIVE) else SearchMode.entries
        for (budget in minimumBudget..maximumBudget) {
            for (mode in modes) {
                val result = search(problem, initial, goal, budget, mode) ?: continue
                val reconstructed = reconstruct(problem, result)
                val plan = MotionPlan(
                    problem,
                    reconstructed.relocations,
                    PlanStatistics(
                        expandedTotal,
                        generatedTotal,
                        budget,
                        (System.nanoTime() - started) / 1_000_000L,
                        mode.name.lowercase(),
                        config.exactSearch,
                    ),
                    reconstructed.captureIndex,
                    reconstructed.capturePath,
                )
                plan.validate()
                if (incumbent != null && planObjective(incumbent) < planObjective(plan)) {
                    return incumbent.copy(
                        statistics = PlanStatistics(
                            expandedTotal,
                            generatedTotal,
                            incumbent.temporaryPieceCount,
                            (System.nanoTime() - started) / 1_000_000L,
                            "constructive-incumbent",
                        ),
                    )
                }
                return plan
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        if (incumbent != null) {
            return incumbent.copy(
                statistics = PlanStatistics(
                    expandedTotal,
                    generatedTotal,
                    incumbent.temporaryPieceCount,
                    elapsed,
                    "constructive-fallback",
                ),
            ).also(MotionPlan::validate)
        }
        val reason = if (System.nanoTime() >= deadlineNanos) "time limit" else "search limits"
        val reachability = if (feasibility.status == "proven_solvable") {
            "; structural reachability is proven, so this is a bounded-search or disturbance-policy failure"
        } else {
            ""
        }
        throw PlanningException(
            "No collision-safe plan found within $reason: $expandedTotal states, " +
                "${elapsed}ms, at most $maximumBudget temporary pieces$reachability",
        )
    }

    private fun constructivePlan(
        problem: PlanningProblem,
        started: Long,
        delayCaptureRestoration: Boolean = true,
    ): MotionPlan? {
        val moving = problem.pieces.indices.filter {
            problem.pieces[it].primary && problem.pieces[it].start != problem.pieces[it].goal
        }
        if (moving.size != 1 || problem.pieces.any {
                !it.primary && it.start != it.goal
            }
        ) return null
        if (problem.deferredCapture && !problem.edgeCaptureExit) return null
        if (System.nanoTime() >= deadlineNanos) return null

        val primary = moving.single()
        var positions = problem.initialPositions
        var occupant = positions.mapIndexed { index, square -> square to index }.toMap().toMutableMap()
        if (problem.capturedSquare != null && problem.deferredCapture) {
            occupant[problem.capturedSquare] = -1
        }
        var relocations = mutableListOf<Relocation>()
        var captureIndex: Int? = null
        var capturePath = emptyList<Int>()
        var disturbed = mutableSetOf<Int>()
        var delayedCaptureForward = emptyList<Pair<Int, List<Int>>>()

        data class Snapshot(
            val positions: IntArray,
            val occupant: MutableMap<Int, Int>,
            val relocations: MutableList<Relocation>,
            val disturbed: MutableSet<Int>,
        )

        fun snapshot() = Snapshot(
            positions.copyOf(),
            occupant.toMutableMap(),
            relocations.toMutableList(),
            disturbed.toMutableSet(),
        )

        fun restore(value: Snapshot) {
            positions = value.positions.copyOf()
            occupant = value.occupant.toMutableMap()
            relocations = value.relocations.toMutableList()
            disturbed = value.disturbed.toMutableSet()
        }

        fun appendMove(index: Int, path: List<Int>, purpose: String): Boolean {
            if (path.size < 2 || positions[index] != path.first()) return false
            val movingOccupancy = occupant.keys - path.first()
            if (path.drop(1).any { it in movingOccupancy }) return false
            val newlyDisturbed = !problem.pieces[index].primary && index !in disturbed
            if (disturbed.size + (if (newlyDisturbed) 1 else 0) > config.maxTemporaryPieces) {
                return false
            }
            occupant.remove(path.first())
            occupant[path.last()] = index
            positions[index] = path.last()
            if (!problem.pieces[index].primary) disturbed += index
            relocations += Relocation(
                problem.pieces[index].key,
                path.first(),
                path.last(),
                path,
                purpose,
            )
            return true
        }

        fun parkingPath(
            index: Int,
            protectedTargets: Set<Int>,
            bannedPathNodes: Set<Int>,
        ): List<Int>? {
            if (System.nanoTime() >= deadlineNanos) return null
            val source = positions[index]
            val extraBlocked = bannedPathNodes - source
            val blocked = occupant.keys + extraBlocked
            val targets = reachableEmptySquares(source, blocked) - protectedTargets - extraBlocked
            if (targets.isEmpty()) return null
            val freeAfterLift = (0 until BOARD_SQUARES).toSet() -
                ((occupant.keys - source) + extraBlocked)
            val articulation = articulationPoints(freeAfterLift)
            val occupiedWithoutSource = occupant.keys - source
            data class Candidate(
                val articulation: Int,
                val steps: Long,
                val negativeMobility: Int,
                val negativeProtectedDistance: Long,
                val target: Int,
            )
            val candidates = targets.map { target ->
                val mobility = neighbors(target).count {
                    it.square !in occupiedWithoutSource &&
                        it.square !in extraBlocked &&
                        it.square != target
                }
                Candidate(
                    if (target in articulation) 1 else 0,
                    manhattan(source, target),
                    -mobility,
                    -(protectedTargets.minOfOrNull { manhattan(target, it) } ?: 0L),
                    target,
                )
            }.sortedWith(
                compareBy<Candidate> { it.articulation }
                    .thenBy { it.steps }
                    .thenBy { it.negativeMobility }
                    .thenBy { it.negativeProtectedDistance }
                    .thenBy { it.target },
            )
            candidates.forEach { candidate ->
                findEmptyPath(source, candidate.target, blocked)?.let { return it }
            }
            return null
        }

        fun clearCorridor(
            corridor: List<Int>,
            fromOpenEnd: Boolean,
            protectedTargets: Set<Int>,
            bannedPathNodes: Set<Int>,
        ): List<Pair<Int, List<Int>>>? {
            val forward = mutableListOf<Pair<Int, List<Int>>>()
            val squares = corridor.drop(1).toMutableList()
            if (fromOpenEnd) squares.reverse()
            squares.forEach { square ->
                if (System.nanoTime() >= deadlineNanos) return null
                val index = occupant[square] ?: return@forEach
                if (index == -1) return null
                val path = parkingPath(index, protectedTargets, bannedPathNodes) ?: return null
                val purpose = if (problem.pieces[index].primary) "stage" else "evacuate"
                if (!appendMove(index, path, purpose)) return null
                forward += index to path
            }
            return forward
        }

        fun reverseMoves(forward: List<Pair<Int, List<Int>>>): Boolean {
            forward.asReversed().forEach { (index, path) ->
                val purpose = if (problem.pieces[index].primary) "stage" else "restore"
                if (!appendMove(index, path.asReversed(), purpose)) return false
            }
            return true
        }

        if (problem.capturedSquare != null && problem.deferredCapture) {
            val capture = problem.capturedSquare
            val captureOptions = relaxedCorridors(
                capture,
                CAPTURE_EDGE_EXITS,
                occupant,
                -1,
                maxOf(12, config.corridorCandidates),
            )
            var captured = false
            for (corridor in captureOptions) {
                if (System.nanoTime() >= deadlineNanos) return null
                val before = snapshot()
                val protected = corridor.toSet()
                val forward = clearCorridor(
                    corridor,
                    true,
                    protected,
                    emptySet(),
                )
                if (forward == null || corridor.drop(1).any { it in occupant }) {
                    restore(before)
                    continue
                }
                captureIndex = relocations.size
                capturePath = corridor
                if (occupant[capture] != -1) {
                    restore(before)
                    continue
                }
                occupant.remove(capture)
                val canDelay = delayCaptureRestoration && forward.all { (index, path) ->
                    index != primary && problem.pieces[primary].goal !in path
                }
                if (canDelay) {
                    delayedCaptureForward = forward
                } else if (!reverseMoves(forward)) {
                    restore(before)
                    continue
                }
                captured = true
                break
            }
            if (!captured) return null
        }

        val source = positions[primary]
        val goal = problem.pieces[primary].goal
        val direct = findEmptyPath(source, goal, occupant.keys)
        if (direct != null) {
            if (!appendMove(primary, direct, "primary")) return null
        } else {
            val base = snapshot()
            val empty = (0 until BOARD_SQUARES).toSet() - occupant.keys
            val components = mutableListOf<Set<Int>>()
            val unseen = empty.toMutableSet()
            while (unseen.isNotEmpty()) {
                val seed = unseen.min()
                val component = mutableSetOf(seed)
                val frontier = ArrayDeque(listOf(seed))
                unseen.remove(seed)
                while (frontier.isNotEmpty()) {
                    neighbors(frontier.removeLast()).forEach { neighbor ->
                        if (unseen.remove(neighbor.square)) {
                            component += neighbor.square
                            frontier += neighbor.square
                        }
                    }
                }
                components += component
            }
            components.sortWith(compareByDescending<Set<Int>> { it.size }.thenBy { it.min() })

            var solved = false
            for (component in components) {
                if (solved) break
                val articulation = articulationPoints(component)
                val stageCandidates = (component - goal).sortedWith(
                    compareBy<Int> { if (it in articulation) 1 else 0 }
                        .thenByDescending { square ->
                            neighbors(square).count { it.square in component }
                        }
                        .thenByDescending { manhattan(it, goal) }
                        .thenBy { manhattan(source, it) }
                        .thenBy { it },
                ).take(16)
                for (stage in stageCandidates) {
                    if (solved) break
                    if (System.nanoTime() >= deadlineNanos) return null
                    restore(base)
                    val corridors = relaxedCorridors(
                        source,
                        setOf(stage),
                        occupant,
                        primary,
                        maxOf(4, config.corridorCandidates),
                    )
                    for (escape in corridors) {
                        if (goal in escape || escape.size < 2) continue
                        restore(base)
                        val protectedEscape = escape.toSet() + goal
                        val escapeForward = clearCorridor(
                            escape,
                            true,
                            protectedEscape,
                            setOf(goal),
                        ) ?: continue
                        if (!appendMove(primary, escape, "stage")) continue

                        val corridorOccupant = occupant.toMutableMap()
                        corridorOccupant.remove(stage)
                        val main = relaxedShortestPath(
                            source,
                            setOf(goal),
                            corridorOccupant,
                            -1,
                            bannedNodes = escape.drop(1).toSet(),
                        ) ?: continue
                        val protectedMain = main.toSet() + escape
                        val mainForward = clearCorridor(
                            main,
                            false,
                            protectedMain,
                            setOf(goal),
                        ) ?: continue
                        val finalPath = findEmptyPath(stage, goal, occupant.keys)
                        if (finalPath == null || !appendMove(primary, finalPath, "primary")) {
                            continue
                        }
                        if (!reverseMoves(mainForward)) continue
                        if (!reverseMoves(escapeForward)) continue
                        solved = positions.contentEquals(problem.goalPositions)
                        if (solved) break
                    }
                }
            }
            if (!solved) {
                restore(base)
                if (delayedCaptureForward.isNotEmpty() && System.nanoTime() < deadlineNanos) {
                    return constructivePlan(problem, started, false)
                }
                return null
            }
        }

        if (delayedCaptureForward.isNotEmpty() && !reverseMoves(delayedCaptureForward)) {
            return if (System.nanoTime() < deadlineNanos) {
                constructivePlan(problem, started, false)
            } else {
                null
            }
        }
        val plan = MotionPlan(
            problem,
            relocations,
            PlanStatistics(
                0,
                0,
                disturbed.size,
                (System.nanoTime() - started) / 1_000_000L,
                "constructive-incumbent",
            ),
            captureIndex,
            capturePath,
        )
        return try {
            plan.validate()
            plan
        } catch (_: PlanningException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun search(
        problem: PlanningProblem,
        initial: SearchState,
        goal: PositionVector,
        budget: Int,
        mode: SearchMode,
    ): SearchResult? {
        val queue = PriorityQueue<SearchEntry>(compareBy<SearchEntry> { it.priority }.thenBy { it.tie })
        val zero = Objective()
        queue += SearchEntry(
            zero.weighted(heuristic(problem, initial), config.heuristicWeight),
            serial.getAndIncrement(),
            zero,
            initial,
        )
        val best = mutableMapOf(initial to zero)
        val parents = mutableMapOf<SearchState, ParentStep?>(initial to null)
        while (queue.isNotEmpty()) {
            if (System.nanoTime() >= deadlineNanos || expandedTotal >= config.maxNodes) return null
            val entry = queue.remove()
            if (best[entry.state] != entry.cost) continue
            if (entry.state.positions == goal && entry.state.captureRemoved) {
                return SearchResult(entry.state, parents)
            }
            expandedTotal++
            successors(problem, entry.state, budget, mode).forEach { action ->
                if (action.pieceIndex == -1) {
                    val next = SearchState(entry.state.positions, entry.state.disturbedMask, true)
                    val cost = entry.cost + action.cost
                    if (best[next]?.let { cost >= it } == true) return@forEach
                    best[next] = cost
                    parents[next] = ParentStep(entry.state, action)
                    val priority = cost.weighted(heuristic(problem, next), config.heuristicWeight)
                    queue += SearchEntry(priority, serial.getAndIncrement(), cost, next)
                    generatedTotal++
                    return@forEach
                }
                var disturbed = entry.state.disturbedMask
                if (!problem.pieces[action.pieceIndex].primary) disturbed = disturbed or (1L shl action.pieceIndex)
                if (java.lang.Long.bitCount(disturbed) > budget) return@forEach
                val next = SearchState(
                    entry.state.positions.moved(action.pieceIndex, action.target),
                    disturbed,
                    entry.state.captureRemoved,
                    if (mode == SearchMode.EXHAUSTIVE) action.pieceIndex else -1,
                )
                val cost = entry.cost + action.cost
                if (best[next]?.let { cost >= it } == true) return@forEach
                best[next] = cost
                parents[next] = ParentStep(entry.state, action)
                val priority = cost.weighted(heuristic(problem, next), config.heuristicWeight)
                queue += SearchEntry(priority, serial.getAndIncrement(), cost, next)
                generatedTotal++
            }
        }
        return null
    }

    private fun heuristic(problem: PlanningProblem, state: SearchState): Objective {
        if (!state.captureRemoved) {
            return Objective(pickups = 1)
        }
        val mismatched = problem.pieces.indices.filter {
            state.positions.values[it] != problem.pieces[it].goal
        }
        if (mismatched.isEmpty()) return Objective()
        return Objective(
            pickups = mismatched.size.toLong(),
            steps = mismatched.sumOf {
                manhattan(state.positions.values[it], problem.pieces[it].goal)
            },
        )
    }

    private fun successors(
        problem: PlanningProblem,
        state: SearchState,
        budget: Int,
        mode: SearchMode,
    ): List<SearchAction> {
        if (!state.captureRemoved) return captureSuccessors(problem, state, budget, mode)
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
            if (actions[key]?.let { action.cost < it.cost } != false) actions[key] = action
        }

        val blockedPrimary = mutableListOf<Int>()
        mismatchedPrimary.forEach { index ->
            val path = findEmptyPath(state.positions.values[index], problem.pieces[index].goal, occupied)
            if (path != null && path.size > 1) {
                add(makeAction(problem, state, index, path, "primary"))
            } else {
                blockedPrimary += index
            }
        }

        val blockedSecondary = mutableListOf<Int>()
        mismatchedSecondary.forEach { index ->
            val path = findEmptyPath(state.positions.values[index], problem.pieces[index].goal, occupied)
            if (path != null && path.size > 1) add(makeAction(problem, state, index, path, "restore"))
            else blockedSecondary += index
        }

        val obligations = mutableListOf<Int>()
        obligations += blockedPrimary
        obligations += blockedSecondary
        obligations.take(2).forEach { obligation ->
            relaxedCorridors(
                state.positions.values[obligation],
                setOf(problem.pieces[obligation].goal),
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

        if (mode == SearchMode.EXHAUSTIVE) {
            state.positions.values.forEachIndexed { index, source ->
                if (!problem.pieces[index].primary && state.disturbedMask and (1L shl index) == 0L &&
                    java.lang.Long.bitCount(state.disturbedMask) >= budget
                ) return@forEachIndexed
                if (index == state.lastPiece) return@forEachIndexed
                reachableEmptySquares(source, occupied).sorted().forEach { target ->
                    val path = findEmptyPath(source, target, occupied) ?: return@forEach
                    add(makeAction(problem, state, index, path, purpose(problem, state, index, target)))
                }
            }
        } else if (actions.isEmpty()) {
            state.positions.values.forEachIndexed { index, source ->
                if (!problem.pieces[index].primary && state.disturbedMask and (1L shl index) == 0L &&
                    java.lang.Long.bitCount(state.disturbedMask) >= budget
                ) return@forEachIndexed
                neighbors(source).filter { it.square !in occupied }.forEach { neighbor ->
                    add(makeAction(
                        problem,
                        state,
                        index,
                        listOf(source, neighbor.square),
                        purpose(problem, state, index, neighbor.square),
                    ))
                }
            }
        }
        if (mode == SearchMode.EXHAUSTIVE) {
            actions.entries.removeIf { it.value.pieceIndex == state.lastPiece }
        }
        return actions.values.sortedWith(compareBy<SearchAction> { it.cost }.thenBy { it.pieceIndex }.thenBy { it.target })
    }

    private fun captureSuccessors(
        problem: PlanningProblem,
        state: SearchState,
        budget: Int,
        mode: SearchMode,
    ): List<SearchAction> {
        val capture = problem.capturedSquare
            ?: return listOf(SearchAction(-1, -1, emptyList(), "capture", Objective(pickups = 1)))
        val occupant = state.positions.values.mapIndexed { index, square -> square to index }
            .toMap().toMutableMap()
        occupant[capture] = -1
        val occupied = occupant.keys.toSet()
        val selection = if (problem.edgeCaptureExit) {
            edgeCaptureLanes(capture, occupant)
        } else {
            legacyCaptureLanes(capture, occupant)
        }
        selection.immediate?.let { return listOf(it) }
        val actions = mutableMapOf<Pair<Int, Int>, SearchAction>()
        selection.lanes.sortedWith(compareBy<CaptureLane> { it.blockerCount }.thenBy { it.preference })
            .take(config.corridorCandidates)
            .forEach { lane ->
                addDependencyActions(
                    problem, state, occupant, lane.blockers, lane.forbidden,
                    actions, budget, mode,
                )
            }
        fun add(action: SearchAction) {
            val key = action.pieceIndex to action.target
            if (actions[key]?.let { action.cost < it.cost } != false) actions[key] = action
        }
        if (mode == SearchMode.BROAD || mode == SearchMode.EXHAUSTIVE) {
            problem.pieces.indices.forEach { index ->
                if (!problem.pieces[index].primary && state.disturbedMask and (1L shl index) == 0L &&
                    java.lang.Long.bitCount(state.disturbedMask) >= budget
                ) return@forEach
                parkingActions(
                    problem, state, occupant, index, setOf(capture),
                    config.broadCandidatesPerPiece, true,
                ).forEach(::add)
            }
        }
        if (mode == SearchMode.EXHAUSTIVE) {
            state.positions.values.forEachIndexed { index, source ->
                if (!problem.pieces[index].primary && state.disturbedMask and (1L shl index) == 0L &&
                    java.lang.Long.bitCount(state.disturbedMask) >= budget
                ) return@forEachIndexed
                if (index == state.lastPiece) return@forEachIndexed
                reachableEmptySquares(source, occupied).sorted().forEach { target ->
                    val path = findEmptyPath(source, target, occupied) ?: return@forEach
                    add(makeAction(problem, state, index, path, purpose(problem, state, index, target)))
                }
            }
        } else if (actions.isEmpty()) {
            state.positions.values.forEachIndexed { index, source ->
                if (!problem.pieces[index].primary && state.disturbedMask and (1L shl index) == 0L &&
                    java.lang.Long.bitCount(state.disturbedMask) >= budget
                ) return@forEachIndexed
                neighbors(source).filter { it.square !in occupied }.forEach { neighbor ->
                    add(makeAction(
                        problem,
                        state,
                        index,
                        listOf(source, neighbor.square),
                        purpose(problem, state, index, neighbor.square),
                    ))
                }
            }
        }
        if (mode == SearchMode.EXHAUSTIVE) {
            actions.entries.removeIf { it.value.pieceIndex == state.lastPiece }
        }
        return actions.values.sortedWith(
            compareBy<SearchAction> { it.cost }.thenBy { it.pieceIndex }.thenBy { it.target },
        )
    }

    private fun edgeCaptureLanes(
        capture: Int,
        occupant: Map<Int, Int>,
    ): CaptureLaneSelection {
        val lanes = mutableListOf<CaptureLane>()
        relaxedCorridors(
            capture, CAPTURE_EDGE_EXITS, occupant, -1, config.corridorCandidates,
        ).forEachIndexed { preference, corridor ->
            val blockers = corridor.drop(1).mapNotNull(occupant::get)
                .filter { it >= 0 }.distinct().sorted()
            if (blockers.isEmpty()) {
                val turns = routeTurns(corridor)
                val drags = if (corridor.size > 1) turns + 1 else 0
                val cost = Objective(
                    pickups = (drags + 1).toLong(),
                    steps = (corridor.size - 1).toLong(),
                    turns = turns.toLong(),
                )
                return CaptureLaneSelection(
                    SearchAction(-1, corridor.last(), corridor, "capture", cost),
                )
            }
            lanes += CaptureLane(blockers.size, preference, corridor.toSet(), blockers)
        }
        return CaptureLaneSelection(lanes = lanes)
    }

    private fun legacyCaptureLanes(
        capture: Int,
        occupant: Map<Int, Int>,
    ): CaptureLaneSelection {
        val lanes = mutableListOf<CaptureLane>()
        captureExitRanks(capture).forEachIndexed { preference, rank ->
            val clearance = captureClearanceSquares(capture, rank)
            val blockers = clearance.mapNotNull(occupant::get).filter { it >= 0 }.distinct().sorted()
            if (blockers.isEmpty()) {
                return CaptureLaneSelection(
                    SearchAction(-1, capture, emptyList(), "capture", Objective(pickups = 1)),
                )
            }
            lanes += CaptureLane(blockers.size, preference, clearance + capture, blockers)
        }
        return CaptureLaneSelection(lanes = lanes)
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
                if (output[key]?.let { action.cost < it.cost } != false) output[key] = action
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
            escape.drop(1).mapNotNull(occupant::get)
                .filter { it >= 0 && it != blocker && it !in seen }.forEach {
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
        val turns = routeTurns(path)
        val cost = Objective(
            disturbed = if (newlyDisturbed) 1 else 0,
            pickups = (turns + 1).toLong(),
            steps = (path.size - 1).toLong(),
            turns = turns.toLong(),
        )
        return SearchAction(pieceIndex, path.last(), path, purpose, cost)
    }

    private fun reconstruct(
        problem: PlanningProblem,
        result: SearchResult,
    ): ReconstructedPlan {
        val reverse = mutableListOf<SearchAction>()
        var state = result.state
        while (true) {
            val step = result.parents[state] ?: break
            reverse += step.action
            state = step.previous
        }
        val relocations = mutableListOf<Relocation>()
        var captureIndex: Int? = null
        var capturePath = emptyList<Int>()
        reverse.asReversed().forEach { action ->
            if (action.pieceIndex == -1) {
                captureIndex = relocations.size
                capturePath = action.path
            } else {
                relocations += Relocation(
                    problem.pieces[action.pieceIndex].key,
                    action.path.first(),
                    action.path.last(),
                    action.path,
                    action.purpose,
                )
            }
        }
        return ReconstructedPlan(relocations, captureIndex, capturePath)
    }
}

fun planningProblemFromChess(
    board: Board,
    move: Move,
    physicalOccupancy: Set<Int>? = null,
    deferredCapture: Boolean = false,
    edgeCaptureExit: Boolean = false,
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
        deferredCapture,
        edgeCaptureExit,
    )
}

fun captureClearanceSquares(captureSquare: Int, exitRank: Int): Set<Int> {
    validateSquare(captureSquare)
    require(exitRank in 1..8) { "Capture exit rank must be 1..8" }
    val file = captureSquare % 8
    val sourceRank = captureSquare / 8 + 1
    return buildSet {
        for (rank in minOf(sourceRank, exitRank)..maxOf(sourceRank, exitRank)) {
            if (rank != sourceRank) add(file + (rank - 1) * 8)
        }
        for (column in 0..file) {
            if (column < file) add(column + (exitRank - 1) * 8)
            if (exitRank > 1 && !(exitRank - 1 == sourceRank && column == file)) {
                add(column + (exitRank - 2) * 8)
            }
        }
        remove(captureSquare)
    }
}

fun captureExitRanks(captureSquare: Int): List<Int> {
    validateSquare(captureSquare)
    val sourceRank = captureSquare / 8 + 1
    return (sourceRank downTo 1).toList() + ((sourceRank + 1)..8).toList()
}

fun findCaptureExitRank(captureSquare: Int, occupied: Collection<Int>): Int? {
    val withoutCapture = occupied.toSet() - captureSquare
    return captureExitRanks(captureSquare).firstOrNull { rank ->
        captureClearanceSquares(captureSquare, rank).none { it in withoutCapture }
    }
}

fun validateSquare(square: Int) {
    require(square in 0 until BOARD_SQUARES) { "Square is outside the board: $square" }
}

fun squareName(square: Int): String = Protocol.squareName(square)

fun parseSquare(name: String): Int = Protocol.squareIndex(name)

private fun permutationParity(values: IntArray): Int {
    var parity = 0
    values.indices.forEach { index ->
        var larger = 0
        for (previous in 0 until index) {
            if (values[previous] > values[index]) larger++
        }
        parity = parity xor (larger and 1)
    }
    return parity
}

private fun oneHoleReachable(initialPositions: IntArray, goalPositions: IntArray): Boolean {
    val initialHoles = (0 until BOARD_SQUARES).toSet() - initialPositions.toSet()
    val goalHoles = (0 until BOARD_SQUARES).toSet() - goalPositions.toSet()
    require(initialHoles.size == 1 && goalHoles.size == 1) {
        "One-hole parity requires exactly one vacancy"
    }
    val blankStart = initialHoles.single()
    val blankGoal = goalHoles.single()
    val blank = initialPositions.size
    val initialItem = IntArray(BOARD_SQUARES) { -1 }
    val goalSquare = IntArray(initialPositions.size + 1)
    initialPositions.forEachIndexed { index, square -> initialItem[square] = index }
    goalPositions.forEachIndexed { index, square -> goalSquare[index] = square }
    initialItem[blankStart] = blank
    goalSquare[blank] = blankGoal
    val permutation = IntArray(BOARD_SQUARES) { square -> goalSquare[initialItem[square]] }
    return permutationParity(permutation) == (manhattan(blankStart, blankGoal) % 2L).toInt()
}

fun analyzeFeasibility(problem: PlanningProblem): FeasibilityAnalysis {
    val holesBefore = BOARD_SQUARES - problem.initialOccupancyBeforeCapture.size
    val holesAfter = BOARD_SQUARES - problem.initialPositions.size
    if (problem.capturedSquare != null && problem.deferredCapture) {
        if (!problem.edgeCaptureExit) {
            return FeasibilityAnalysis(
                "unknown",
                "legacy capture clearance is stricter than grid reachability",
                holesBefore,
                holesAfter,
            )
        }
        val distanceToEdge = problem.capturedSquare % 8
        if (holesBefore < distanceToEdge) {
            return FeasibilityAnalysis(
                "proven_impossible",
                "capture on file ${distanceToEdge + 1} needs at least $distanceToEdge " +
                    "pre-removal vacancies, but only $holesBefore exist",
                holesBefore,
                holesAfter,
            )
        }
    }
    if (holesAfter >= 2) {
        return FeasibilityAnalysis(
            "proven_solvable",
            "at least two vacancies make all labeled configurations reachable " +
                "on the biconnected 8x8 grid",
            holesBefore,
            holesAfter,
        )
    }
    if (holesAfter == 0) {
        val solved = problem.initialPositions.contentEquals(problem.goalPositions)
        return FeasibilityAnalysis(
            if (solved) "proven_solvable" else "proven_impossible",
            if (solved) {
                "the full-board labeling already equals its goal"
            } else {
                "a full board has no legal release square for any pickup"
            },
            holesBefore,
            holesAfter,
        )
    }
    val reachable = oneHoleReachable(problem.initialPositions, problem.goalPositions)
    return FeasibilityAnalysis(
        if (reachable) "proven_solvable" else "proven_impossible",
        "the one-vacancy permutation/checkerboard parity " +
            if (reachable) "matches" else "does not match",
        holesBefore,
        holesAfter,
    )
}

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

private data class Heading(val square: Int, val direction: Int)
private data class PathCost(val turns: Int, val steps: Int) : Comparable<PathCost> {
    override fun compareTo(other: PathCost): Int = compareValuesBy(
        this,
        other,
        PathCost::turns,
        PathCost::steps,
    )
}
private data class PathEntry(val priority: PathCost, val cost: PathCost, val tie: Long, val state: Heading)

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
    queue += PathEntry(PathCost(0, manhattan(start, goal).toInt()), PathCost(0, 0), serial.getAndIncrement(), initial)
    val best = mutableMapOf(initial to PathCost(0, 0))
    val parent = mutableMapOf<Heading, Heading?>(initial to null)
    while (queue.isNotEmpty()) {
        val entry = queue.remove()
        if (best[entry.state] != entry.cost) continue
        if (entry.state.square == goal) return reconstructPath(parent, entry.state)
        neighbors(entry.state.square).sortedWith(compareBy<Neighbor> { manhattan(it.square, goal) }.thenBy { it.direction })
            .forEach { neighbor ->
                if (neighbor.square in blocked) return@forEach
                val turn = if (entry.state.direction != -1 && entry.state.direction != neighbor.direction) 1 else 0
                val cost = PathCost(entry.cost.turns + turn, entry.cost.steps + 1)
                val next = Heading(neighbor.square, neighbor.direction)
                if (best[next]?.let { cost >= it } == true) return@forEach
                best[next] = cost
                parent[next] = entry.state
                queue += PathEntry(
                    PathCost(cost.turns, cost.steps + manhattan(neighbor.square, goal).toInt()),
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

private data class RelaxedCost(val blockers: Int, val turns: Int, val steps: Int) : Comparable<RelaxedCost> {
    override fun compareTo(other: RelaxedCost): Int = compareValuesBy(
        this, other, RelaxedCost::blockers, RelaxedCost::turns, RelaxedCost::steps,
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
                entry.cost.turns + if (entry.state.direction != -1 && entry.state.direction != neighbor.direction) 1 else 0,
                entry.cost.steps + 1,
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

internal fun relaxedCorridors(
    start: Int,
    goals: Set<Int>,
    occupant: Map<Int, Int>,
    movingPiece: Int,
    limit: Int,
): List<List<Int>> {
    val first = relaxedShortestPath(start, goals, occupant, movingPiece) ?: return emptyList()
    fun score(path: List<Int>) = RelaxedCost(
        path.drop(1).count { occupant[it]?.let { piece -> piece != movingPiece } == true },
        routeTurns(path),
        path.size - 1,
    )
    val found = linkedMapOf<List<Int>, RelaxedCost>()
    val seeds = mutableListOf(first)
    val endpointSeeds = linkedSetOf<List<Int>>()
    if (goals.size > 1) {
        goals.sorted().forEach { endpoint ->
            relaxedShortestPath(start, setOf(endpoint), occupant, movingPiece)?.let {
                seeds += it
                endpointSeeds += it
            }
        }
    }
    seeds.forEach { found[it] = score(it) }
    val frontier = ArrayDeque(found.keys)
    while (frontier.isNotEmpty() && found.size < maxOf(limit * 4, limit + 1)) {
        val base = frontier.removeFirst()
        base.zipWithNext().forEach { edge ->
            val candidate = relaxedShortestPath(
                start, goals, occupant, movingPiece, setOf(edge),
            ) ?: return@forEach
            if (candidate !in found) {
                found[candidate] = score(candidate)
                frontier.add(candidate)
            }
        }
    }
    val ranked = found.entries
        .sortedWith(compareBy<Map.Entry<List<Int>, RelaxedCost>> { it.value }.thenBy { it.key.joinToString(",") })
        .map { it.key }
    // A-file bin exits are separate physical escape choices. Keep one route
    // to every endpoint even when the generic branch limit is smaller.
    val selected = endpointSeeds.toMutableSet()
    val targetCount = maxOf(limit, endpointSeeds.size)
    ranked.forEach { path ->
        if (selected.size < targetCount) selected += path
    }
    return ranked.filter { it in selected }
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
