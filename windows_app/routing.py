"""Collision-safe rearrangement planning for the physical chessboard.

The planner deliberately uses only orthogonal carried-piece motion. Under the
board's clearance rule every legal diagonal can be replaced by two orthogonal
steps, so this smaller graph preserves reachability while making physical
validation deterministic.

The search operates on labeled piece configurations.  A macro action picks up
one piece, carries it through an empty 4-connected corridor, and releases it on
an empty square.  Exact final positions make evacuation, restoration, main-piece
staging, and recursive obstacle clearing ordinary paths through one state graph.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from heapq import heappop, heappush
from itertools import count
from time import monotonic
from typing import Iterable, Iterator, Sequence

BOARD_FILES = 8
BOARD_SQUARES = 64
NO_SQUARE = -1

# Lexicographic physical priorities encoded in a single Python integer.  The
# ranges are deliberately separated by several orders of magnitude so one new
# temporary piece is always more expensive than every plausible pickup, route,
# turn, and clearance penalty on an 8x8 board.
DISTURBANCE_COST = 10**12
PICKUP_COST = 10**8
STEP_COST = 10**4
TURN_COST = 100

# Direction order and protocol-compatible two-bit codes.
NORTH, EAST, SOUTH, WEST = range(4)
DIRECTION_DELTAS = {
    NORTH: 8,
    EAST: 1,
    SOUTH: -8,
    WEST: -1,
}


class PlanningError(RuntimeError):
    """Raised when a valid plan cannot be found within configured limits."""


@dataclass(frozen=True)
class PieceTask:
    """One labeled physical piece and its exact required final square."""

    key: str
    start: int
    goal: int
    primary: bool = False

    def __post_init__(self) -> None:
        validate_square(self.start)
        validate_square(self.goal)


@dataclass(frozen=True)
class PlanningProblem:
    """A complete rearrangement problem after any captured piece is removed."""

    pieces: tuple[PieceTask, ...]
    move_uci: str = ""
    captured_square: int | None = None
    castling_side: str | None = None
    initial_physical_occupancy: frozenset[int] | None = None

    def __post_init__(self) -> None:
        starts = [piece.start for piece in self.pieces]
        goals = [piece.goal for piece in self.pieces]
        if len(starts) != len(set(starts)):
            raise ValueError("Two pieces start on the same square")
        if len(goals) != len(set(goals)):
            raise ValueError("Two pieces have the same final square")
        if self.captured_square is not None:
            validate_square(self.captured_square)
            if self.captured_square in starts:
                raise ValueError("Captured piece must be excluded from active pieces")
        if self.castling_side not in (None, "kingside", "queenside"):
            raise ValueError("castling_side must be kingside, queenside, or None")
        if not any(piece.primary and piece.start != piece.goal for piece in self.pieces):
            raise ValueError("At least one primary piece must change square")

    @property
    def initial_positions(self) -> tuple[int, ...]:
        return tuple(piece.start for piece in self.pieces)

    @property
    def goal_positions(self) -> tuple[int, ...]:
        return tuple(piece.goal for piece in self.pieces)

    @property
    def initial_occupancy_after_capture(self) -> frozenset[int]:
        return frozenset(self.initial_positions)

    @property
    def initial_occupancy_before_capture(self) -> frozenset[int]:
        occupied = set(self.initial_positions)
        if self.captured_square is not None:
            occupied.add(self.captured_square)
        return frozenset(occupied)

    @property
    def final_occupancy(self) -> frozenset[int]:
        return frozenset(self.goal_positions)


@dataclass(frozen=True)
class PlannerConfig:
    """Search limits and deterministic branch-width controls."""

    time_limit_s: float = 8.0
    max_nodes: int = 250_000
    max_temporary_pieces: int = 10
    heuristic_weight: float = 1.25
    corridor_candidates: int = 4
    parking_candidates: int = 8
    dependency_depth: int = 4
    broad_candidates_per_piece: int = 2

    def __post_init__(self) -> None:
        if self.time_limit_s <= 0:
            raise ValueError("time_limit_s must be positive")
        if self.max_nodes <= 0:
            raise ValueError("max_nodes must be positive")
        if self.max_temporary_pieces < 0:
            raise ValueError("max_temporary_pieces cannot be negative")
        if self.heuristic_weight < 1.0:
            raise ValueError("heuristic_weight must be at least 1.0")
        if self.corridor_candidates <= 0 or self.parking_candidates <= 0:
            raise ValueError("candidate limits must be positive")


@dataclass(frozen=True)
class Relocation:
    """One logical carried path through empty orthogonal squares.

    The wire protocol splits this path at turns.  Each straight run is a
    separate pickup and release so the Nano can validate one simple corridor
    and one sensor transition at a time.
    """

    piece_key: str
    source: int
    target: int
    path: tuple[int, ...]
    purpose: str

    def __post_init__(self) -> None:
        if len(self.path) < 2:
            raise ValueError("Relocation path must contain source and destination")
        if self.path[0] != self.source or self.path[-1] != self.target:
            raise ValueError("Relocation endpoints do not match path")
        validate_orthogonal_path(self.path)

    @property
    def steps(self) -> int:
        return len(self.path) - 1

    @property
    def turns(self) -> int:
        return route_turns(self.path)


@dataclass(frozen=True)
class PlanStatistics:
    expanded_nodes: int
    generated_nodes: int
    disturbance_budget: int
    elapsed_s: float
    search_mode: str


@dataclass(frozen=True)
class MotionPlan:
    problem: PlanningProblem
    relocations: tuple[Relocation, ...]
    statistics: PlanStatistics

    @property
    def temporary_piece_count(self) -> int:
        return len({
            move.piece_key
            for move in self.relocations
            if move.purpose in ("evacuate", "repark", "restore")
        })

    @property
    def pickup_count(self) -> int:
        return self.drag_count + (1 if self.problem.captured_square is not None else 0)

    @property
    def drag_count(self) -> int:
        from protocol import split_route_runs

        return sum(len(split_route_runs(move.path)) for move in self.relocations)

    @property
    def carried_steps(self) -> int:
        return sum(move.steps for move in self.relocations)

    def validate(self) -> None:
        """Replay labels and occupancy; raise if any route or final state is invalid."""

        positions = {piece.key: piece.start for piece in self.problem.pieces}
        occupancy = {piece.start: piece.key for piece in self.problem.pieces}
        for relocation in self.relocations:
            if positions.get(relocation.piece_key) != relocation.source:
                raise PlanningError(
                    f"{relocation.piece_key} is not at {square_name(relocation.source)}"
                )
            if occupancy.get(relocation.source) != relocation.piece_key:
                raise PlanningError("Source occupancy identity mismatch")
            if relocation.target in occupancy:
                raise PlanningError(f"Target {square_name(relocation.target)} is occupied")

            moving_occupancy = set(occupancy)
            moving_occupancy.remove(relocation.source)
            for square in relocation.path[1:]:
                if square in moving_occupancy:
                    raise PlanningError(f"Route crosses occupied {square_name(square)}")

            del occupancy[relocation.source]
            occupancy[relocation.target] = relocation.piece_key
            positions[relocation.piece_key] = relocation.target

        expected = {piece.key: piece.goal for piece in self.problem.pieces}
        if positions != expected:
            mismatches = [
                f"{key}:{square_name(positions[key])}->{square_name(goal)}"
                for key, goal in expected.items()
                if positions[key] != goal
            ]
            raise PlanningError("Plan does not restore the exact goal: " + ", ".join(mismatches))

    def protocol_commands(self) -> tuple[str, ...]:
        """Build the transactional ACB command sequence lazily to avoid cycles."""

        from protocol import (
            commit_plan_command, drag_command, plan_command, split_route_runs,
        )

        # A BOARD proof follows transaction opening and every physical drag.
        # The Nano already validates all 64 switches locally; these snapshots
        # independently let Windows detect stale, reordered, or lost commands.
        commands = [
            plan_command(
                self.problem.move_uci,
                self.problem.captured_square,
                castling_side=self.problem.castling_side,
            ),
            "BOARD",
        ]
        for move in self.relocations:
            for run in split_route_runs(move.path):
                commands.extend((drag_command(run), "BOARD"))
        commands.append(commit_plan_command())
        return tuple(commands)

    def describe(self) -> str:
        moved = self.temporary_piece_count
        capture = " + capture removal" if self.problem.captured_square is not None else ""
        return (
            f"{self.drag_count} verified drags{capture}, {moved} temporary pieces, "
            f"{self.carried_steps} carried squares"
        )


@dataclass(frozen=True)
class _SearchState:
    positions: tuple[int, ...]
    disturbed_mask: int


@dataclass(frozen=True)
class _SearchAction:
    piece_index: int
    target: int
    path: tuple[int, ...]
    purpose: str
    cost: int


@dataclass(order=True)
class _QueueEntry:
    priority: int
    tie: int
    cost: int = field(compare=False)
    state: _SearchState = field(compare=False)


def validate_square(square: int) -> None:
    if square not in range(BOARD_SQUARES):
        raise ValueError(f"Square {square!r} is outside the 8x8 board")


def square_name(square: int) -> str:
    validate_square(square)
    return f"{chr(ord('a') + square % 8)}{square // 8 + 1}"


def parse_square(name: str) -> int:
    text = name.strip().lower()
    if len(text) != 2 or text[0] not in "abcdefgh" or text[1] not in "12345678":
        raise ValueError(f"Invalid square {name!r}")
    return (ord(text[0]) - ord("a")) + (int(text[1]) - 1) * 8


def manhattan(first: int, second: int) -> int:
    return abs(first % 8 - second % 8) + abs(first // 8 - second // 8)


def orthogonal_neighbors(square: int) -> Iterator[tuple[int, int]]:
    file_index = square % 8
    rank_index = square // 8
    if rank_index < 7:
        yield square + 8, NORTH
    if file_index < 7:
        yield square + 1, EAST
    if rank_index > 0:
        yield square - 8, SOUTH
    if file_index > 0:
        yield square - 1, WEST


def direction_between(first: int, second: int) -> int:
    delta = second - first
    if delta == 8:
        return NORTH
    if delta == -8:
        return SOUTH
    if delta == 1 and first // 8 == second // 8:
        return EAST
    if delta == -1 and first // 8 == second // 8:
        return WEST
    raise ValueError(
        f"{square_name(first)}->{square_name(second)} is not one orthogonal step"
    )


def validate_orthogonal_path(path: Sequence[int]) -> None:
    if not path:
        raise ValueError("Path is empty")
    for square in path:
        validate_square(square)
    for first, second in zip(path, path[1:]):
        direction_between(first, second)


def route_turns(path: Sequence[int]) -> int:
    if len(path) < 3:
        return 0
    directions = [direction_between(first, second) for first, second in zip(path, path[1:])]
    return sum(previous != current for previous, current in zip(directions, directions[1:]))


def clearance_risk(path: Sequence[int], occupied_without_mover: frozenset[int]) -> int:
    """Small branch-order penalty for side-adjacent magnetic pieces."""

    risk = 0
    for square in path[1:]:
        risk += sum(neighbor in occupied_without_mover for neighbor, _ in orthogonal_neighbors(square))
    return risk


def _reconstruct_path(
    parent: dict[tuple[int, int], tuple[int, int] | None],
    end_state: tuple[int, int],
) -> tuple[int, ...]:
    reverse: list[int] = []
    state: tuple[int, int] | None = end_state
    while state is not None:
        reverse.append(state[0])
        state = parent[state]
    reverse.reverse()
    return tuple(reverse)


def find_empty_path(start: int, goal: int, occupied: Iterable[int]) -> tuple[int, ...] | None:
    """Heading-aware A* through empty squares, using orthogonal moves only."""

    validate_square(start)
    validate_square(goal)
    if start == goal:
        return (start,)
    blocked = frozenset(occupied) - {start}
    if goal in blocked:
        return None

    start_state = (start, -1)
    queue: list[tuple[int, int, int, tuple[int, int]]] = []
    serial = count()
    heappush(queue, (manhattan(start, goal) * STEP_COST, 0, next(serial), start_state))
    best = {start_state: 0}
    parent: dict[tuple[int, int], tuple[int, int] | None] = {start_state: None}

    while queue:
        _priority, cost_so_far, _tie, state = heappop(queue)
        if cost_so_far != best.get(state):
            continue
        square, previous_direction = state
        if square == goal:
            return _reconstruct_path(parent, state)

        neighbors = list(orthogonal_neighbors(square))
        neighbors.sort(key=lambda item: (manhattan(item[0], goal), item[1]))
        for neighbor, direction in neighbors:
            if neighbor in blocked:
                continue
            turn = int(previous_direction != -1 and previous_direction != direction)
            side_risk = sum(
                adjacent in blocked for adjacent, _ in orthogonal_neighbors(neighbor)
            )
            step_cost = STEP_COST + turn * TURN_COST + side_risk
            candidate = cost_so_far + step_cost
            next_state = (neighbor, direction)
            if candidate >= best.get(next_state, 1 << 62):
                continue
            best[next_state] = candidate
            parent[next_state] = state
            priority = candidate + manhattan(neighbor, goal) * STEP_COST
            heappush(queue, (priority, candidate, next(serial), next_state))
    return None


def reachable_empty_squares(start: int, occupied: Iterable[int]) -> frozenset[int]:
    """All possible release squares for a piece currently at start."""

    blocked = frozenset(occupied) - {start}
    frontier = [start]
    reached = {start}
    while frontier:
        current = frontier.pop()
        for neighbor, _ in orthogonal_neighbors(current):
            if neighbor in blocked or neighbor in reached:
                continue
            reached.add(neighbor)
            frontier.append(neighbor)
    reached.discard(start)
    return frozenset(reached)


def _relaxed_shortest_path(
    start: int,
    goals: frozenset[int],
    occupant_by_square: dict[int, int],
    moving_piece: int,
    banned_edges: frozenset[tuple[int, int]] = frozenset(),
    banned_nodes: frozenset[int] = frozenset(),
) -> tuple[int, ...] | None:
    """Lexicographic Dijkstra: occupied cells, then distance, then turns."""

    if start in goals:
        return (start,)
    start_state = (start, -1)
    serial = count()
    queue: list[tuple[int, int, int, int, tuple[int, int]]] = []
    heappush(queue, (0, 0, 0, next(serial), start_state))
    best: dict[tuple[int, int], tuple[int, int, int]] = {start_state: (0, 0, 0)}
    parent: dict[tuple[int, int], tuple[int, int] | None] = {start_state: None}

    while queue:
        blockers, steps, turns, _tie, state = heappop(queue)
        if (blockers, steps, turns) != best.get(state):
            continue
        square, previous_direction = state
        if square in goals:
            return _reconstruct_path(parent, state)

        for neighbor, direction in orthogonal_neighbors(square):
            if neighbor in banned_nodes or (square, neighbor) in banned_edges:
                continue
            occupant = occupant_by_square.get(neighbor)
            add_blocker = int(occupant is not None and occupant != moving_piece)
            candidate = (
                blockers + add_blocker,
                steps + 1,
                turns + int(previous_direction != -1 and previous_direction != direction),
            )
            next_state = (neighbor, direction)
            if candidate >= best.get(next_state, (1 << 20, 1 << 20, 1 << 20)):
                continue
            best[next_state] = candidate
            parent[next_state] = state
            heappush(queue, (*candidate, next(serial), next_state))
    return None


def relaxed_corridors(
    start: int,
    goal: int,
    occupant_by_square: dict[int, int],
    moving_piece: int,
    limit: int,
) -> tuple[tuple[int, ...], ...]:
    """Small deterministic set of low-blocker alternative corridors.

    This is a compact Yen-style variant: after each accepted path, individual
    directed edges are banned to expose useful alternatives.  On 64 vertices it
    is much cheaper than carrying a full K-shortest-path implementation and is
    sufficient for conflict discovery and branch ordering.
    """

    first = _relaxed_shortest_path(
        start, frozenset({goal}), occupant_by_square, moving_piece
    )
    if first is None:
        return ()
    found: dict[tuple[int, ...], tuple[int, int, int]] = {}

    def score(path: tuple[int, ...]) -> tuple[int, int, int]:
        blockers = sum(
            occupant_by_square.get(square) not in (None, moving_piece)
            for square in path[1:]
        )
        return blockers, len(path) - 1, route_turns(path)

    found[first] = score(first)
    frontier = [first]
    while frontier and len(found) < max(limit * 4, limit + 1):
        base = frontier.pop(0)
        for edge in zip(base, base[1:]):
            candidate = _relaxed_shortest_path(
                start,
                frozenset({goal}),
                occupant_by_square,
                moving_piece,
                banned_edges=frozenset({edge}),
            )
            if candidate is None or candidate in found:
                continue
            found[candidate] = score(candidate)
            frontier.append(candidate)
    return tuple(path for path, _ in sorted(found.items(), key=lambda item: item[1])[:limit])


def _articulation_points(vertices: frozenset[int]) -> frozenset[int]:
    """Tarjan articulation points on the board's free-space graph."""

    discovery: dict[int, int] = {}
    low: dict[int, int] = {}
    parent: dict[int, int | None] = {}
    points: set[int] = set()
    clock = 0

    def visit(vertex: int) -> None:
        nonlocal clock
        discovery[vertex] = low[vertex] = clock
        clock += 1
        children = 0
        for neighbor, _ in orthogonal_neighbors(vertex):
            if neighbor not in vertices:
                continue
            if neighbor not in discovery:
                parent[neighbor] = vertex
                children += 1
                visit(neighbor)
                low[vertex] = min(low[vertex], low[neighbor])
                if parent[vertex] is None and children > 1:
                    points.add(vertex)
                if parent[vertex] is not None and low[neighbor] >= discovery[vertex]:
                    points.add(vertex)
            elif neighbor != parent[vertex]:
                low[vertex] = min(low[vertex], discovery[neighbor])

    for vertex in vertices:
        if vertex in discovery:
            continue
        parent[vertex] = None
        visit(vertex)
    return frozenset(points)


class RearrangementPlanner:
    """Weighted A* with iterative disturbance budgets and conflict-directed moves."""

    def __init__(self, config: PlannerConfig | None = None) -> None:
        self.config = config or PlannerConfig()
        self._deadline = 0.0
        self._expanded_total = 0
        self._generated_total = 0
        self._parking_environment_cache: dict[
            tuple[frozenset[int], int], tuple[frozenset[int], frozenset[int]]
        ] = {}
        self._parking_path_cache: dict[
            tuple[frozenset[int], int], dict[int, tuple[int, ...] | None]
        ] = {}

    def plan(self, problem: PlanningProblem) -> MotionPlan:
        started = monotonic()
        self._deadline = started + self.config.time_limit_s
        self._expanded_total = 0
        self._generated_total = 0
        self._parking_environment_cache.clear()
        self._parking_path_cache.clear()

        if problem.initial_physical_occupancy is not None:
            expected_before_capture = set(problem.initial_occupancy_before_capture)
            if frozenset(expected_before_capture) != problem.initial_physical_occupancy:
                missing = expected_before_capture - set(problem.initial_physical_occupancy)
                unexpected = set(problem.initial_physical_occupancy) - expected_before_capture
                raise PlanningError(
                    "Physical occupancy does not match the logical position "
                    f"(missing={sorted(map(square_name, missing))}, "
                    f"unexpected={sorted(map(square_name, unexpected))})"
                )

        initial_disturbed = sum(
            1 << index
            for index, piece in enumerate(problem.pieces)
            if not piece.primary and piece.start != piece.goal
        )
        initial = _SearchState(problem.initial_positions, initial_disturbed)
        if initial.positions == problem.goal_positions:
            statistics = PlanStatistics(0, 0, 0, monotonic() - started, "direct")
            return MotionPlan(problem, (), statistics)

        temporary_count = sum(not piece.primary for piece in problem.pieces)
        maximum_budget = min(self.config.max_temporary_pieces, temporary_count)
        # Budget 0 proves direct and primary-only staging cases before any
        # secondary piece can be touched.  Subsequent searches guarantee that
        # the first returned plan minimizes the number of disturbed pieces.
        minimum_budget = initial_disturbed.bit_count()
        for budget in range(minimum_budget, maximum_budget + 1):
            # Focused and broad passes produce mechanically economical plans.
            # The exhaustive pass restores completeness for the bounded search:
            # every legal one-cell slide is represented, so repeated actions span
            # the complete labeled-pebble configuration graph.
            for mode in ("focused", "broad", "exhaustive"):
                result = self._search(problem, initial, budget, mode)
                if result is None:
                    continue
                relocations = self._reconstruct(problem, result[0], result[1])
                statistics = PlanStatistics(
                    expanded_nodes=self._expanded_total,
                    generated_nodes=self._generated_total,
                    disturbance_budget=budget,
                    elapsed_s=monotonic() - started,
                    search_mode=mode,
                )
                plan = MotionPlan(problem, relocations, statistics)
                plan.validate()
                return plan

        elapsed = monotonic() - started
        reason = "time limit" if monotonic() >= self._deadline else "search limits"
        raise PlanningError(
            f"No collision-safe plan found within {reason}: "
            f"{self._expanded_total} states, {elapsed:.2f}s, "
            f"at most {maximum_budget} temporary pieces"
        )

    def _search(
        self,
        problem: PlanningProblem,
        initial: _SearchState,
        disturbance_budget: int,
        mode: str,
    ) -> tuple[
        _SearchState,
        dict[_SearchState, tuple[_SearchState, _SearchAction] | None],
    ] | None:
        serial = count()
        start_h = self._heuristic(problem, initial)
        queue = [
            _QueueEntry(
                int(self.config.heuristic_weight * start_h),
                next(serial),
                0,
                initial,
            )
        ]
        best_cost = {initial: 0}
        parent: dict[_SearchState, tuple[_SearchState, _SearchAction] | None] = {initial: None}

        while queue:
            if monotonic() >= self._deadline or self._expanded_total >= self.config.max_nodes:
                return None
            entry = heappop(queue)
            if entry.cost != best_cost.get(entry.state):
                continue
            if entry.state.positions == problem.goal_positions:
                return entry.state, parent

            self._expanded_total += 1
            actions = self._successors(problem, entry.state, disturbance_budget, mode)
            for action in actions:
                positions = list(entry.state.positions)
                positions[action.piece_index] = action.target
                piece_bit = 1 << action.piece_index
                disturbed = entry.state.disturbed_mask
                if not problem.pieces[action.piece_index].primary:
                    disturbed |= piece_bit
                if disturbed.bit_count() > disturbance_budget:
                    continue
                next_state = _SearchState(tuple(positions), disturbed)
                candidate_cost = entry.cost + action.cost
                if candidate_cost >= best_cost.get(next_state, 1 << 120):
                    continue
                best_cost[next_state] = candidate_cost
                parent[next_state] = (entry.state, action)
                heuristic = self._heuristic(problem, next_state)
                priority = candidate_cost + int(self.config.heuristic_weight * heuristic)
                heappush(
                    queue,
                    _QueueEntry(priority, next(serial), candidate_cost, next_state),
                )
                self._generated_total += 1
        return None

    def _heuristic(self, problem: PlanningProblem, state: _SearchState) -> int:
        mismatched = [
            index
            for index, (current, goal) in enumerate(zip(state.positions, problem.goal_positions))
            if current != goal
        ]
        if not mismatched:
            return 0
        estimate = len(mismatched) * PICKUP_COST
        estimate += sum(
            manhattan(state.positions[index], problem.goal_positions[index]) * STEP_COST
            for index in mismatched
        )

        occupant = {square: index for index, square in enumerate(state.positions)}
        unavoidable_new = 0
        for index in mismatched:
            if not problem.pieces[index].primary:
                continue
            corridor = _relaxed_shortest_path(
                state.positions[index],
                frozenset({problem.goal_positions[index]}),
                occupant,
                index,
            )
            if corridor is None:
                continue
            blockers = {
                occupant[square]
                for square in corridor[1:]
                if square in occupant and occupant[square] != index
            }
            new_secondary = sum(
                not problem.pieces[blocker].primary
                and not (state.disturbed_mask & (1 << blocker))
                for blocker in blockers
            )
            unavoidable_new = max(unavoidable_new, new_secondary)
        return estimate + unavoidable_new * DISTURBANCE_COST

    def _successors(
        self,
        problem: PlanningProblem,
        state: _SearchState,
        disturbance_budget: int,
        mode: str,
    ) -> tuple[_SearchAction, ...]:
        occupant = {square: index for index, square in enumerate(state.positions)}
        occupied = frozenset(occupant)
        mismatched_primary = [
            index
            for index, piece in enumerate(problem.pieces)
            if piece.primary and state.positions[index] != piece.goal
        ]
        mismatched_secondary = [
            index
            for index, piece in enumerate(problem.pieces)
            if not piece.primary and state.positions[index] != piece.goal
        ]

        actions: dict[tuple[int, int], _SearchAction] = {}

        def add(action: _SearchAction) -> None:
            key = (action.piece_index, action.target)
            previous = actions.get(key)
            if previous is None or action.cost < previous.cost:
                actions[key] = action

        # Move a primary to its exact goal as soon as the route is clear.  This
        # is the normal Plan A transition after blockers have been parked.
        direct_primary = False
        for index in mismatched_primary:
            path = find_empty_path(state.positions[index], problem.pieces[index].goal, occupied)
            if path and len(path) > 1:
                add(self._make_action(problem, state, index, path, "primary"))
                direct_primary = True

        # Restore directly reachable temporary pieces even while a primary is
        # staged away from its goal.  That is the essential Plan B ordering:
        # stage main -> restore blockers -> return main.  Restricting restoration
        # to states where every primary was already home made overshoot plans
        # impossible because the search would immediately undo the staging move.
        blocked_secondary: list[int] = []
        for index in mismatched_secondary:
            path = find_empty_path(state.positions[index], problem.pieces[index].goal, occupied)
            if path and len(path) > 1:
                add(self._make_action(problem, state, index, path, "restore"))
            else:
                blocked_secondary.append(index)

        blocked_obligations: list[int] = []
        if mismatched_primary and not direct_primary:
            blocked_obligations.extend(mismatched_primary)
        blocked_obligations.extend(blocked_secondary)

        for obligation in blocked_obligations[:2]:
            corridors = relaxed_corridors(
                state.positions[obligation],
                problem.pieces[obligation].goal,
                occupant,
                obligation,
                self.config.corridor_candidates,
            )
            for corridor in corridors:
                forbidden = frozenset(corridor)
                blockers = [
                    occupant[square]
                    for square in corridor[1:]
                    if square in occupant and occupant[square] != obligation
                ]
                self._add_dependency_actions(
                    problem,
                    state,
                    occupant,
                    blockers,
                    forbidden,
                    actions,
                    disturbance_budget,
                    mode,
                )

            # A displaced obstacle may need a different parking square before
            # it can eventually reach home.
            if not problem.pieces[obligation].primary:
                for action in self._parking_actions(
                    problem,
                    state,
                    occupant,
                    obligation,
                    frozenset(),
                    max(2, self.config.parking_candidates // 2),
                    allow_reserved=(mode != "focused"),
                ):
                    add(action)

        # Plan B alternatives: while temporary pieces remain, permit either
        # primary to leave its final square for a safe staging square.  The exact
        # goal test forces it back after restoration.
        if mismatched_secondary:
            for index, piece in enumerate(problem.pieces):
                if not piece.primary or state.positions[index] != piece.goal:
                    continue
                for action in self._parking_actions(
                    problem,
                    state,
                    occupant,
                    index,
                    frozenset(piece_task.goal for piece_task in problem.pieces if not piece_task.primary),
                    max(3, self.config.parking_candidates // 2),
                    allow_reserved=(mode != "focused"),
                    purpose="stage",
                ):
                    add(action)

        # Broad mode is a bounded completeness expansion: every movable piece
        # receives a few least-constraining parking options, even when it was not
        # named by the current relaxed corridor.  This is what breaks recursive
        # traps and dependency cycles without hard-coded recursion scripts.
        if mode in ("broad", "exhaustive"):
            for index in range(len(problem.pieces)):
                if (
                    not problem.pieces[index].primary
                    and not (state.disturbed_mask & (1 << index))
                    and state.disturbed_mask.bit_count() >= disturbance_budget
                ):
                    continue
                for action in self._parking_actions(
                    problem,
                    state,
                    occupant,
                    index,
                    frozenset(),
                    self.config.broad_candidates_per_piece,
                    allow_reserved=True,
                ):
                    add(action)

        if mode == "exhaustive" or not actions:
            # Completeness fallback: every legal one-cell slide is represented.
            # Repeated slides span the same state graph as arbitrary macro paths.
            for index, source in enumerate(state.positions):
                if (
                    not problem.pieces[index].primary
                    and not (state.disturbed_mask & (1 << index))
                    and state.disturbed_mask.bit_count() >= disturbance_budget
                ):
                    continue
                for target, _ in orthogonal_neighbors(source):
                    if target in occupied:
                        continue
                    purpose = self._purpose(problem, state, index, target)
                    add(self._make_action(problem, state, index, (source, target), purpose))

        return tuple(sorted(actions.values(), key=lambda action: (action.cost, action.piece_index, action.target)))

    def _add_dependency_actions(
        self,
        problem: PlanningProblem,
        state: _SearchState,
        occupant: dict[int, int],
        initial_blockers: Sequence[int],
        forbidden: frozenset[int],
        output: dict[tuple[int, int], _SearchAction],
        disturbance_budget: int,
        mode: str,
    ) -> None:
        queue: list[tuple[int, frozenset[int], int]] = [
            (blocker, forbidden, 0) for blocker in initial_blockers
        ]
        seen: set[int] = set()
        while queue:
            blocker, reserved, depth = queue.pop(0)
            if blocker in seen:
                continue
            seen.add(blocker)
            if (
                not problem.pieces[blocker].primary
                and not (state.disturbed_mask & (1 << blocker))
                and state.disturbed_mask.bit_count() >= disturbance_budget
            ):
                continue

            parking = self._parking_actions(
                problem,
                state,
                occupant,
                blocker,
                reserved,
                self.config.parking_candidates,
                allow_reserved=(mode != "focused"),
            )
            for action in parking:
                key = (action.piece_index, action.target)
                previous = output.get(key)
                if previous is None or action.cost < previous.cost:
                    output[key] = action

            if parking or depth >= self.config.dependency_depth:
                continue

            # The blocker has no adjacent route to a safe empty square.  Search
            # through occupied cells to discover the next pieces that must be
            # evacuated first (Plan C).
            available_targets = frozenset(
                square
                for square in range(BOARD_SQUARES)
                if square not in occupant and square not in reserved
            )
            if not available_targets:
                continue
            escape = _relaxed_shortest_path(
                state.positions[blocker], available_targets, occupant, blocker
            )
            if escape is None:
                continue
            next_forbidden = reserved | frozenset(escape)
            for square in escape[1:]:
                dependent = occupant.get(square)
                if dependent is None or dependent == blocker or dependent in seen:
                    continue
                queue.append((dependent, next_forbidden, depth + 1))

    def _parking_actions(
        self,
        problem: PlanningProblem,
        state: _SearchState,
        occupant: dict[int, int],
        piece_index: int,
        forbidden: frozenset[int],
        limit: int,
        allow_reserved: bool,
        purpose: str | None = None,
    ) -> tuple[_SearchAction, ...]:
        source = state.positions[piece_index]
        occupied = frozenset(occupant)
        cache_key = (occupied, source)
        environment = self._parking_environment_cache.get(cache_key)
        if environment is None:
            free_after_lift = frozenset(range(BOARD_SQUARES)) - (occupied - {source})
            environment = (
                reachable_empty_squares(source, occupied),
                _articulation_points(free_after_lift),
            )
            self._parking_environment_cache[cache_key] = environment
        reachable, articulation = environment
        if not reachable:
            return ()

        goals_of_others = {
            piece.goal for index, piece in enumerate(problem.pieces) if index != piece_index
        }
        scored: list[tuple[tuple[int, ...], int]] = []
        for target in reachable:
            reserved = target in forbidden or target in goals_of_others
            if reserved and not allow_reserved:
                continue
            mobility = sum(
                neighbor not in (occupied - {source}) and neighbor != target
                for neighbor, _ in orthogonal_neighbors(target)
            )
            # Prefer non-reserved, non-articulation, short, reversible parking;
            # use deterministic square index as the final tie-breaker.
            score = (
                int(reserved),
                int(target in articulation),
                manhattan(source, target),
                manhattan(target, problem.pieces[piece_index].start),
                -mobility,
                target,
            )
            scored.append((score, target))

        # A focused pass can become over-restrictive if every reachable square
        # lies on a reserved corridor.  Retain a few penalized choices rather
        # than incorrectly declaring a dead end.
        if not scored and not allow_reserved:
            return self._parking_actions(
                problem,
                state,
                occupant,
                piece_index,
                forbidden,
                limit,
                allow_reserved=True,
                purpose=purpose,
            )

        actions = []
        paths = self._parking_path_cache.setdefault(cache_key, {})
        for _score, target in sorted(scored):
            if target not in paths:
                paths[target] = find_empty_path(source, target, occupied)
            path = paths[target]
            if path is None or len(path) < 2:
                continue
            actual_purpose = purpose or self._purpose(problem, state, piece_index, target)
            actions.append(
                self._make_action(problem, state, piece_index, path, actual_purpose)
            )
            if len(actions) >= limit:
                break
        return tuple(actions)

    @staticmethod
    def _purpose(
        problem: PlanningProblem,
        state: _SearchState,
        piece_index: int,
        target: int,
    ) -> str:
        piece = problem.pieces[piece_index]
        if piece.primary:
            return "primary" if target == piece.goal else "stage"
        if target == piece.goal:
            return "restore"
        if state.positions[piece_index] == piece.start and not (
            state.disturbed_mask & (1 << piece_index)
        ):
            return "evacuate"
        return "repark"

    @staticmethod
    def _make_action(
        problem: PlanningProblem,
        state: _SearchState,
        piece_index: int,
        path: tuple[int, ...],
        purpose: str,
    ) -> _SearchAction:
        piece = problem.pieces[piece_index]
        newly_disturbed = (
            not piece.primary and not (state.disturbed_mask & (1 << piece_index))
        )
        occupied_without_mover = frozenset(state.positions) - {state.positions[piece_index]}
        cost = (
            int(newly_disturbed) * DISTURBANCE_COST
            + (route_turns(path) + 1) * PICKUP_COST
            + (len(path) - 1) * STEP_COST
            + route_turns(path) * TURN_COST
            + clearance_risk(path, occupied_without_mover)
        )
        return _SearchAction(piece_index, path[-1], path, purpose, cost)

    @staticmethod
    def _reconstruct(
        problem: PlanningProblem,
        final_state: _SearchState,
        parent: dict[_SearchState, tuple[_SearchState, _SearchAction] | None],
    ) -> tuple[Relocation, ...]:
        reverse: list[Relocation] = []
        state = final_state
        while parent[state] is not None:
            previous, action = parent[state]  # type: ignore[misc]
            reverse.append(
                Relocation(
                    piece_key=problem.pieces[action.piece_index].key,
                    source=action.path[0],
                    target=action.path[-1],
                    path=action.path,
                    purpose=action.purpose,
                )
            )
            state = previous
        reverse.reverse()
        return tuple(reverse)


def planning_problem_from_chess(
    board,
    move,
    *,
    physical_occupancy: frozenset[int] | None = None,
) -> PlanningProblem:
    """Adapt a legal python-chess move to the physical rearrangement model.

    The import remains local so the core planner and its algorithmic tests do not
    require python-chess merely to manipulate generic board configurations.
    """

    import chess

    if move not in board.legal_moves:
        raise PlanningError(f"Illegal chess move: {move.uci()}")
    if getattr(board, "chess960", False):
        raise PlanningError("Physical route planning currently expects standard castling squares")

    capture_square: int | None = None
    if board.is_en_passant(move):
        capture_square = chess.square(chess.square_file(move.to_square), chess.square_rank(move.from_square))
    elif board.is_capture(move):
        capture_square = move.to_square

    piece_map = board.piece_map()
    if move.from_square not in piece_map:
        raise PlanningError("Main piece is missing from its logical source")

    goals = {square: square for square in piece_map if square != capture_square}
    primary_squares = {move.from_square}
    goals[move.from_square] = move.to_square
    castling_side: str | None = None

    if board.is_castling(move):
        rank = chess.square_rank(move.from_square)
        if chess.square_file(move.to_square) > chess.square_file(move.from_square):
            rook_source = chess.square(7, rank)
            rook_target = chess.square(5, rank)
            castling_side = "kingside"
        else:
            rook_source = chess.square(0, rank)
            rook_target = chess.square(3, rank)
            castling_side = "queenside"
        if rook_source not in piece_map:
            raise PlanningError("Castling rook is missing from its logical source")
        goals[rook_source] = rook_target
        primary_squares.add(rook_source)

    pieces: list[PieceTask] = []
    for square in sorted(goals):
        piece = piece_map[square]
        key = f"{piece.symbol()}@{chess.square_name(square)}"
        pieces.append(
            PieceTask(
                key=key,
                start=square,
                goal=goals[square],
                primary=square in primary_squares,
            )
        )

    return PlanningProblem(
        pieces=tuple(pieces),
        move_uci=move.uci(),
        captured_square=capture_square,
        castling_side=castling_side,
        initial_physical_occupancy=physical_occupancy,
    )


def plan_chess_move(
    board,
    move,
    *,
    physical_occupancy: frozenset[int] | None = None,
    config: PlannerConfig | None = None,
) -> MotionPlan:
    problem = planning_problem_from_chess(
        board, move, physical_occupancy=physical_occupancy
    )
    return RearrangementPlanner(config).plan(problem)
