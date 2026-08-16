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
CAPTURE_EDGE_EXITS = frozenset(range(0, BOARD_SQUARES, BOARD_FILES))

# Legacy scalar reporting constants. Search itself uses an explicit ordered
# tuple, so lexicographic priority does not depend on assumed upper bounds.
DISTURBANCE_COST = 10**12
PICKUP_COST = 10**8
STEP_COST = 10**4
TURN_COST = 100

_Objective = tuple[int, int, int, int]
_ZERO_OBJECTIVE: _Objective = (0, 0, 0, 0)


def _add_objectives(left: _Objective, right: _Objective) -> _Objective:
    return (
        left[0] + right[0],
        left[1] + right[1],
        left[2] + right[2],
        left[3] + right[3],
    )


def _weighted_priority(
    cost: _Objective, heuristic: _Objective, weight: float
) -> _Objective:
    return (
        cost[0] + int(weight * heuristic[0]),
        cost[1] + int(weight * heuristic[1]),
        cost[2] + int(weight * heuristic[2]),
        cost[3] + int(weight * heuristic[3]),
    )

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
class FeasibilityAnalysis:
    """Structural reachability result, independent of optimization limits."""

    status: str
    reason: str
    holes_before_capture: int
    holes_after_capture: int


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
    """A complete physical rearrangement problem for one legal chess move."""

    pieces: tuple[PieceTask, ...]
    move_uci: str = ""
    captured_square: int | None = None
    castling_side: str | None = None
    initial_physical_occupancy: frozenset[int] | None = None
    deferred_capture: bool = False
    edge_capture_exit: bool = False

    def __post_init__(self) -> None:
        keys = [piece.key for piece in self.pieces]
        starts = [piece.start for piece in self.pieces]
        goals = [piece.goal for piece in self.pieces]
        if any(not key for key in keys):
            raise ValueError("Piece keys cannot be empty")
        if len(keys) != len(set(keys)):
            raise ValueError("Piece keys must be unique")
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
    """Search limits, branch controls, and optional exact-search mode."""

    time_limit_s: float = 8.0
    max_nodes: int = 250_000
    max_temporary_pieces: int = 10
    heuristic_weight: float = 1.0
    corridor_candidates: int = 4
    parking_candidates: int = 8
    dependency_depth: int = 4
    broad_candidates_per_piece: int = 2
    exact_search: bool = False
    constructive_fallback: bool = True

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
        if self.dependency_depth < 0:
            raise ValueError("dependency_depth cannot be negative")
        if self.broad_candidates_per_piece <= 0:
            raise ValueError("broad_candidates_per_piece must be positive")
        if self.exact_search and self.heuristic_weight != 1.0:
            raise ValueError("exact_search requires heuristic_weight=1.0")


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
    optimal: bool = False


@dataclass(frozen=True)
class MotionPlan:
    problem: PlanningProblem
    relocations: tuple[Relocation, ...]
    statistics: PlanStatistics
    capture_removal_index: int | None = None
    capture_path: tuple[int, ...] = ()

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
        if __package__:
            from .protocol import split_route_runs
        else:  # Top-level import used by the review harness.
            from protocol import split_route_runs

        capture_drags = (
            len(split_route_runs(self.capture_path)) if len(self.capture_path) > 1 else 0
        )
        return capture_drags + sum(
            len(split_route_runs(move.path)) for move in self.relocations
        )

    @property
    def carried_steps(self) -> int:
        return max(0, len(self.capture_path) - 1) + sum(
            move.steps for move in self.relocations
        )

    @property
    def objective(self) -> _Objective:
        capture_turns = route_turns(self.capture_path) if len(self.capture_path) > 1 else 0
        return (
            self.temporary_piece_count,
            self.pickup_count,
            self.carried_steps,
            capture_turns + sum(move.turns for move in self.relocations),
        )

    def validate(self) -> None:
        """Replay labels and occupancy; raise if any route or final state is invalid."""

        positions = {piece.key: piece.start for piece in self.problem.pieces}
        occupancy = {piece.start: piece.key for piece in self.problem.pieces}
        capture = self.problem.captured_square
        capture_pending = capture is not None and self.problem.deferred_capture
        if capture_pending:
            occupancy[capture] = "<captured>"
            if self.capture_removal_index is None:
                raise PlanningError("Deferred capture plan has no removal step")
        elif self.capture_removal_index is not None:
            raise PlanningError("Unexpected capture-removal step")
        elif self.capture_path:
            raise PlanningError("Unexpected capture path")

        def remove_capture() -> None:
            nonlocal capture_pending, capture
            if not capture_pending or capture is None or occupancy.get(capture) != "<captured>":
                raise PlanningError("Capture-removal state is inconsistent")
            if self.problem.edge_capture_exit:
                path = self.capture_path
                if not path or path[0] != capture or path[-1] % BOARD_FILES:
                    raise PlanningError("Captured piece has no valid a-file exit path")
                validate_orthogonal_path(path)
                stationary = set(occupancy) - {capture}
                if any(square in stationary for square in path[1:]):
                    raise PlanningError("Capture route crosses an occupied square")
                del occupancy[capture]
                capture = path[-1]
                occupancy[capture] = "<captured>"
            elif self.capture_path:
                raise PlanningError("Legacy capture plan unexpectedly carries an edge path")
            elif find_capture_exit_rank(capture, occupancy) is None:
                raise PlanningError("Captured piece has no collision-safe exit lane")
            del occupancy[capture]
            capture_pending = False

        for index, relocation in enumerate(self.relocations):
            if index == self.capture_removal_index:
                remove_capture()
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

        if self.capture_removal_index == len(self.relocations):
            remove_capture()
        if capture_pending:
            raise PlanningError("Captured piece was never removed")

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

        if __package__:
            from .protocol import (
                commit_plan_command, drag_command, plan_command, remove_command,
                split_route_runs,
            )
        else:  # Top-level import used by the review harness.
            from protocol import (
                commit_plan_command, drag_command, plan_command, remove_command,
                split_route_runs,
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

        def append_capture() -> None:
            if len(self.capture_path) > 1:
                for run in split_route_runs(self.capture_path):
                    commands.extend((drag_command(run), "BOARD"))
            commands.extend((remove_command(self.problem.captured_square), "BOARD"))

        for index, move in enumerate(self.relocations):
            if index == self.capture_removal_index:
                append_capture()
            for run in split_route_runs(move.path):
                commands.extend((drag_command(run), "BOARD"))
        if self.capture_removal_index == len(self.relocations):
            append_capture()
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
    capture_removed: bool
    last_piece: int = NO_SQUARE


@dataclass(frozen=True)
class _SearchAction:
    piece_index: int
    target: int
    path: tuple[int, ...]
    purpose: str
    cost: _Objective


@dataclass(order=True)
class _QueueEntry:
    priority: _Objective
    tie: int
    cost: _Objective = field(compare=False)
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


def capture_clearance_squares(capture_square: int, exit_rank: int) -> frozenset[int]:
    """Squares that must be empty for the Nano's left-bin capture corridor."""

    validate_square(capture_square)
    if exit_rank not in range(1, 9):
        raise ValueError("Capture exit rank must be 1..8")
    file_index = capture_square % 8
    source_rank = capture_square // 8 + 1
    required: set[int] = set()

    for rank in range(min(source_rank, exit_rank), max(source_rank, exit_rank) + 1):
        if rank != source_rank:
            required.add(file_index + (rank - 1) * 8)

    # Reproduce the legacy 4.x clearance contract used when
    # edge_capture_exit=False: the exit row to the left and, except at rank 1,
    # the row immediately below the boundary lane must be clear.
    for column in range(file_index + 1):
        if column < file_index:
            required.add(column + (exit_rank - 1) * 8)
        if exit_rank > 1 and not (
            exit_rank - 1 == source_rank and column == file_index
        ):
            required.add(column + (exit_rank - 2) * 8)
    required.discard(capture_square)
    return frozenset(required)


def capture_exit_ranks(capture_square: int) -> tuple[int, ...]:
    """Firmware-compatible preference order for capture exit lanes."""

    validate_square(capture_square)
    source_rank = capture_square // 8 + 1
    return tuple(range(source_rank, 0, -1)) + tuple(range(source_rank + 1, 9))


def find_capture_exit_rank(
    capture_square: int, occupied: Iterable[int]
) -> int | None:
    occupied_without_capture = frozenset(occupied) - {capture_square}
    return next(
        (
            rank
            for rank in capture_exit_ranks(capture_square)
            if not (capture_clearance_squares(capture_square, rank) & occupied_without_capture)
        ),
        None,
    )


def find_centerline_capture_exit_rank(
    capture_square: int, occupied: Iterable[int]
) -> int | None:
    """Firmware 4.8 standalone-compatible vertical-then-left exit."""

    occupied_without_capture = frozenset(occupied) - {capture_square}
    file_index = capture_square % BOARD_FILES
    source_rank = capture_square // BOARD_FILES + 1
    for exit_rank in capture_exit_ranks(capture_square):
        vertical = {
            file_index + (rank - 1) * BOARD_FILES
            for rank in range(
                min(source_rank, exit_rank), max(source_rank, exit_rank) + 1
            )
            if rank != source_rank
        }
        horizontal = {
            column + (exit_rank - 1) * BOARD_FILES
            for column in range(file_index)
        }
        if not ((vertical | horizontal) & occupied_without_capture):
            return exit_rank
    return None


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
    """Find the lexicographically best empty orthogonal carry path.

    Turns are minimized before steps because the protocol releases and
    reacquires the piece at every corner. The first straight run is a
    path-independent pickup, so the local objective is exactly (turns, steps).
    """

    validate_square(start)
    validate_square(goal)
    if start == goal:
        return (start,)
    blocked = frozenset(occupied) - {start}
    if goal in blocked:
        return None

    start_state = (start, -1)
    queue: list[tuple[int, int, int, int, int, tuple[int, int]]] = []
    serial = count()
    heappush(queue, (0, manhattan(start, goal), 0, 0, next(serial), start_state))
    best = {start_state: (0, 0)}
    parent: dict[tuple[int, int], tuple[int, int] | None] = {start_state: None}

    while queue:
        _estimated_turns, _estimated_steps, turns, steps, _tie, state = heappop(queue)
        if (turns, steps) != best.get(state):
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
            candidate = (turns + turn, steps + 1)
            next_state = (neighbor, direction)
            if candidate >= best.get(next_state, (1 << 20, 1 << 20)):
                continue
            best[next_state] = candidate
            parent[next_state] = state
            heappush(
                queue,
                (
                    candidate[0],
                    candidate[1] + manhattan(neighbor, goal),
                    candidate[0],
                    candidate[1],
                    next(serial),
                    next_state,
                ),
            )
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
    """Lexicographic Dijkstra: occupied cells, then turns, then distance."""

    if start in goals:
        return (start,)
    start_state = (start, -1)
    serial = count()
    queue: list[tuple[int, int, int, int, tuple[int, int]]] = []
    heappush(queue, (0, 0, 0, next(serial), start_state))
    best: dict[tuple[int, int], tuple[int, int, int]] = {start_state: (0, 0, 0)}
    parent: dict[tuple[int, int], tuple[int, int] | None] = {start_state: None}

    while queue:
        blockers, turns, steps, _tie, state = heappop(queue)
        if (blockers, turns, steps) != best.get(state):
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
                turns + int(previous_direction != -1 and previous_direction != direction),
                steps + 1,
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
    goal: int | frozenset[int],
    occupant_by_square: dict[int, int],
    moving_piece: int,
    limit: int,
) -> tuple[tuple[int, ...], ...]:
    """Small deterministic set of low-blocker alternative corridors.

    This is a compact Yen-style variant: after each accepted path, individual
    directed edges are banned to expose useful alternatives.  On 64 vertices it
    is much cheaper than carrying a full K-shortest-path implementation and is
    sufficient for conflict discovery and branch ordering.  Multi-goal calls
    retain one best path to every reachable endpoint even when that exceeds
    ``limit``; capture-bin completeness is more important than branch trimming.
    """

    goals = frozenset({goal}) if isinstance(goal, int) else goal
    first = _relaxed_shortest_path(start, goals, occupant_by_square, moving_piece)
    if first is None:
        return ()
    found: dict[tuple[int, ...], tuple[int, int, int]] = {}

    def score(path: tuple[int, ...]) -> tuple[int, int, int]:
        blockers = sum(
            occupant_by_square.get(square) not in (None, moving_piece)
            for square in path[1:]
        )
        return blockers, route_turns(path), len(path) - 1

    # A multi-goal shortest path can repeatedly rediscover alternatives ending
    # at only one attractive goal. Seed every endpoint so capture construction
    # cannot silently omit an otherwise clear a-file exit.
    seeds = [first]
    endpoint_seeds: set[tuple[int, ...]] = set()
    if len(goals) > 1:
        for endpoint in sorted(goals):
            candidate = _relaxed_shortest_path(
                start, frozenset({endpoint}), occupant_by_square, moving_piece
            )
            if candidate is not None:
                seeds.append(candidate)
                endpoint_seeds.add(candidate)
    for seed in seeds:
        found[seed] = score(seed)
    frontier = list(found)
    while frontier and len(found) < max(limit * 4, limit + 1):
        base = frontier.pop(0)
        for edge in zip(base, base[1:]):
            candidate = _relaxed_shortest_path(
                start,
                goals,
                occupant_by_square,
                moving_piece,
                banned_edges=frozenset({edge}),
            )
            if candidate is None or candidate in found:
                continue
            found[candidate] = score(candidate)
            frontier.append(candidate)
    ranked = [path for path, _ in sorted(found.items(), key=lambda item: item[1])]
    selected = set(endpoint_seeds)
    target_count = max(limit, len(endpoint_seeds))
    for path in ranked:
        if len(selected) >= target_count:
            break
        selected.add(path)
    return tuple(path for path in ranked if path in selected)


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


def _permutation_parity(values: Sequence[int]) -> int:
    parity = 0
    for index, value in enumerate(values):
        parity ^= sum(other > value for other in values[:index]) & 1
    return parity


def _one_hole_reachable(
    initial_positions: Sequence[int], goal_positions: Sequence[int]
) -> bool:
    """Exact labeled reachability for the one-vacancy 8x8 sliding puzzle."""

    initial_holes = set(range(BOARD_SQUARES)) - set(initial_positions)
    goal_holes = set(range(BOARD_SQUARES)) - set(goal_positions)
    if len(initial_holes) != 1 or len(goal_holes) != 1:
        raise ValueError("One-hole parity requires exactly one vacancy")
    blank_start = next(iter(initial_holes))
    blank_goal = next(iter(goal_holes))
    blank = len(initial_positions)
    initial_item = {square: index for index, square in enumerate(initial_positions)}
    goal_square = {index: square for index, square in enumerate(goal_positions)}
    initial_item[blank_start] = blank
    goal_square[blank] = blank_goal
    permutation = [goal_square[initial_item[square]] for square in range(BOARD_SQUARES)]
    return _permutation_parity(permutation) == manhattan(blank_start, blank_goal) % 2


def analyze_feasibility(problem: PlanningProblem) -> FeasibilityAnalysis:
    """Classify physical reachability where the 8x8-grid theorem is decisive.

    With at least two vacancies, labeled pebble motion on the non-cycle,
    biconnected 8x8 grid is connected.  With one vacancy, legal macros reduce
    to ordinary adjacent blank swaps and the standard permutation/checkerboard
    parity is necessary and sufficient.  Deferred edge capture first requires
    enough existing vacancies to fill every post-source vertex of a shortest
    route to the a-file.
    """

    before = problem.initial_occupancy_before_capture
    holes_before = BOARD_SQUARES - len(before)
    holes_after = BOARD_SQUARES - len(problem.initial_positions)

    if problem.captured_square is not None and problem.deferred_capture:
        if not problem.edge_capture_exit:
            return FeasibilityAnalysis(
                "unknown",
                "legacy capture clearance is stricter than grid reachability",
                holes_before,
                holes_after,
            )
        distance_to_edge = problem.captured_square % BOARD_FILES
        if holes_before < distance_to_edge:
            return FeasibilityAnalysis(
                "proven_impossible",
                f"capture on file {distance_to_edge + 1} needs at least "
                f"{distance_to_edge} pre-removal vacancies, but only "
                f"{holes_before} exist",
                holes_before,
                holes_after,
            )

    if holes_after >= 2:
        return FeasibilityAnalysis(
            "proven_solvable",
            "at least two vacancies make all labeled configurations reachable "
            "on the biconnected 8x8 grid",
            holes_before,
            holes_after,
        )
    if holes_after == 0:
        if tuple(problem.initial_positions) == tuple(problem.goal_positions):
            status = "proven_solvable"
            reason = "the full-board labeling already equals its goal"
        else:
            status = "proven_impossible"
            reason = "a full board has no legal release square for any pickup"
        return FeasibilityAnalysis(status, reason, holes_before, holes_after)

    reachable = _one_hole_reachable(problem.initial_positions, problem.goal_positions)
    return FeasibilityAnalysis(
        "proven_solvable" if reachable else "proven_impossible",
        "the one-vacancy permutation/checkerboard parity "
        + ("matches" if reachable else "does not match"),
        holes_before,
        holes_after,
    )


class RearrangementPlanner:
    """A* with iterative disturbance budgets and conflict-directed moves."""

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

        feasibility = analyze_feasibility(problem)
        if feasibility.status == "proven_impossible":
            raise PlanningError("Proven physically impossible: " + feasibility.reason)

        initial_disturbed = sum(
            1 << index
            for index, piece in enumerate(problem.pieces)
            if not piece.primary and piece.start != piece.goal
        )
        initial = _SearchState(
            problem.initial_positions,
            initial_disturbed,
            problem.captured_square is None or not problem.deferred_capture,
        )
        if initial.positions == problem.goal_positions and initial.capture_removed:
            statistics = PlanStatistics(
                0, 0, 0, monotonic() - started, "direct", optimal=True
            )
            return MotionPlan(problem, (), statistics)

        # Dense positions can have an easy constructive solution but an
        # enormous proof tree.  Build a reversible incumbent before A*: if the
        # bounded optimizer times out, production still receives a validated
        # plan.  Exact mode never returns this unproved incumbent.
        incumbent = (
            self._constructive_plan(problem, started)
            if self.config.constructive_fallback and not self.config.exact_search
            else None
        )

        temporary_count = sum(not piece.primary for piece in problem.pieces)
        maximum_budget = min(self.config.max_temporary_pieces, temporary_count)
        if incumbent is not None:
            # No plan at a larger disturbance budget can beat the incumbent.
            maximum_budget = min(maximum_budget, incumbent.temporary_piece_count)
        # Budget 0 proves direct and primary-only staging cases before any
        # secondary piece can be touched.  Subsequent searches guarantee that
        # the first returned plan minimizes the number of disturbed pieces.
        minimum_budget = initial_disturbed.bit_count()
        modes = ("exhaustive",) if self.config.exact_search else (
            "focused", "broad", "exhaustive"
        )
        for budget in range(minimum_budget, maximum_budget + 1):
            # Focused and broad passes produce mechanically economical plans.
            # Exact mode uses all cost-equivalent macro actions; heuristic mode
            # retains focused and broad passes for production latency.
            for mode in modes:
                result = self._search(problem, initial, budget, mode)
                if result is None:
                    continue
                relocations, capture_index, capture_path = self._reconstruct(
                    problem, result[0], result[1]
                )
                statistics = PlanStatistics(
                    expanded_nodes=self._expanded_total,
                    generated_nodes=self._generated_total,
                    disturbance_budget=budget,
                    elapsed_s=monotonic() - started,
                    search_mode=mode,
                    optimal=self.config.exact_search,
                )
                plan = MotionPlan(
                    problem, relocations, statistics, capture_index, capture_path
                )
                plan.validate()
                if incumbent is not None and incumbent.objective < plan.objective:
                    incumbent_statistics = PlanStatistics(
                        expanded_nodes=self._expanded_total,
                        generated_nodes=self._generated_total,
                        disturbance_budget=incumbent.temporary_piece_count,
                        elapsed_s=monotonic() - started,
                        search_mode="constructive-incumbent",
                        optimal=False,
                    )
                    return MotionPlan(
                        problem,
                        incumbent.relocations,
                        incumbent_statistics,
                        incumbent.capture_removal_index,
                        incumbent.capture_path,
                    )
                return plan

        elapsed = monotonic() - started
        if incumbent is not None:
            statistics = PlanStatistics(
                expanded_nodes=self._expanded_total,
                generated_nodes=self._generated_total,
                disturbance_budget=incumbent.temporary_piece_count,
                elapsed_s=elapsed,
                search_mode="constructive-fallback",
                optimal=False,
            )
            plan = MotionPlan(
                problem,
                incumbent.relocations,
                statistics,
                incumbent.capture_removal_index,
                incumbent.capture_path,
            )
            plan.validate()
            return plan
        reason = "time limit" if monotonic() >= self._deadline else "search limits"
        reachability = (
            "; structural reachability is proven, so this is a bounded-search "
            "or disturbance-policy failure"
            if feasibility.status == "proven_solvable"
            else ""
        )
        raise PlanningError(
            f"No collision-safe plan found within {reason}: "
            f"{self._expanded_total} states, {elapsed:.2f}s, "
            f"at most {maximum_budget} temporary pieces{reachability}"
        )

    def _constructive_plan(
        self,
        problem: PlanningProblem,
        started: float,
        *,
        _delay_capture_restoration: bool = True,
    ) -> MotionPlan | None:
        """Construct a reversible dense-board plan for one moving primary.

        The construction is deliberately narrower than A*: it handles the
        common chess-move shape (one primary changes square; every secondary
        must finish where it began).  It is complete for the corridor choices
        it succeeds in clearing, and every candidate is replay-validated before
        it can become an incumbent.

        Capture blockers are peeled from the board edge inward. Safe blockers
        remain parked through the primary move and are then reversed; an
        immediate-restoration variant is retried when needed. For the main
        move, the primary first opens an escape tunnel and parks in free space.
        The source is then an open end from which the goal corridor can be
        peeled. Blocker moves are reversed after the primary reaches its goal.
        """

        moving = [
            index
            for index, piece in enumerate(problem.pieces)
            if piece.primary and piece.start != piece.goal
        ]
        if len(moving) != 1 or any(
            not piece.primary and piece.start != piece.goal
            for piece in problem.pieces
        ):
            return None
        if problem.deferred_capture and not problem.edge_capture_exit:
            return None
        if monotonic() >= self._deadline:
            return None

        primary = moving[0]
        positions = list(problem.initial_positions)
        occupant = {square: index for index, square in enumerate(positions)}
        if problem.captured_square is not None and problem.deferred_capture:
            occupant[problem.captured_square] = NO_SQUARE

        relocations: list[Relocation] = []
        capture_index: int | None = None
        capture_path: tuple[int, ...] = ()
        disturbed: set[int] = set()
        delayed_capture_forward: list[tuple[int, tuple[int, ...]]] = []

        def clone_state() -> tuple[list[int], dict[int, int], list[Relocation], set[int]]:
            return positions.copy(), occupant.copy(), relocations.copy(), disturbed.copy()

        def restore_state(
            snapshot: tuple[list[int], dict[int, int], list[Relocation], set[int]]
        ) -> None:
            nonlocal positions, occupant, relocations, disturbed
            # Attempts reuse the same baseline snapshot.  Copy on restore so a
            # failed candidate cannot mutate the saved rollback state.
            positions = snapshot[0].copy()
            occupant = snapshot[1].copy()
            relocations = snapshot[2].copy()
            disturbed = snapshot[3].copy()

        def append_move(index: int, path: tuple[int, ...], purpose: str) -> bool:
            if len(path) < 2 or positions[index] != path[0]:
                return False
            moving_occupancy = set(occupant) - {path[0]}
            if any(square in moving_occupancy for square in path[1:]):
                return False
            if path[-1] in moving_occupancy:
                return False
            newly_disturbed = (
                not problem.pieces[index].primary and index not in disturbed
            )
            if len(disturbed) + int(newly_disturbed) > self.config.max_temporary_pieces:
                return False
            del occupant[path[0]]
            occupant[path[-1]] = index
            positions[index] = path[-1]
            if not problem.pieces[index].primary:
                disturbed.add(index)
            relocations.append(
                Relocation(
                    problem.pieces[index].key,
                    path[0],
                    path[-1],
                    path,
                    purpose,
                )
            )
            return True

        def parking_path(
            index: int,
            protected_targets: frozenset[int],
            banned_path_nodes: frozenset[int],
        ) -> tuple[int, ...] | None:
            if monotonic() >= self._deadline:
                return None
            source = positions[index]
            extra_blocked = banned_path_nodes - {source}
            blocked = frozenset(occupant) | extra_blocked
            reachable = reachable_empty_squares(source, blocked)
            targets = reachable - protected_targets - extra_blocked
            if not targets:
                return None

            free_after_lift = (
                frozenset(range(BOARD_SQUARES))
                - ((frozenset(occupant) - {source}) | extra_blocked)
            )
            articulation = _articulation_points(free_after_lift)
            scored: list[tuple[tuple[int, ...], int]] = []
            occupied_without_source = frozenset(occupant) - {source}
            for target in targets:
                mobility = sum(
                    neighbor not in occupied_without_source
                    and neighbor not in extra_blocked
                    and neighbor != target
                    for neighbor, _ in orthogonal_neighbors(target)
                )
                scored.append(
                    (
                        (
                            int(target in articulation),
                            manhattan(source, target),
                            -mobility,
                            -min(
                                (manhattan(target, square) for square in protected_targets),
                                default=0,
                            ),
                            target,
                        ),
                        target,
                    )
                )
            for _score, target in sorted(scored):
                path = find_empty_path(source, target, blocked)
                if path is not None:
                    return path
            return None

        def clear_corridor(
            corridor: tuple[int, ...],
            *,
            from_open_end: bool,
            protected_targets: frozenset[int],
            banned_path_nodes: frozenset[int],
        ) -> list[tuple[int, tuple[int, ...]]] | None:
            forward: list[tuple[int, tuple[int, ...]]] = []
            squares = list(corridor[1:])
            if from_open_end:
                squares.reverse()
            for square in squares:
                if monotonic() >= self._deadline:
                    return None
                index = occupant.get(square)
                if index is None:
                    continue
                if index == NO_SQUARE:
                    return None
                path = parking_path(index, protected_targets, banned_path_nodes)
                if path is None:
                    return None
                purpose = "stage" if problem.pieces[index].primary else "evacuate"
                if not append_move(index, path, purpose):
                    return None
                forward.append((index, path))
            return forward

        def reverse_moves(forward: Sequence[tuple[int, tuple[int, ...]]]) -> bool:
            for index, path in reversed(forward):
                purpose = "stage" if problem.pieces[index].primary else "restore"
                if not append_move(index, tuple(reversed(path)), purpose):
                    return False
            return True

        # Deferred edge capture: try several low-blocker corridors.  The exact
        # reverse of every evacuation is legal after the captured piece leaves
        # the board, because reverse order recreates each forward occupancy.
        if problem.captured_square is not None and problem.deferred_capture:
            capture = problem.captured_square
            capture_options = relaxed_corridors(
                capture,
                CAPTURE_EDGE_EXITS,
                occupant,
                NO_SQUARE,
                max(12, self.config.corridor_candidates),
            )
            captured = False
            for corridor in capture_options:
                if monotonic() >= self._deadline:
                    return None
                snapshot = clone_state()
                protected = frozenset(corridor)
                forward = clear_corridor(
                    corridor,
                    from_open_end=True,
                    protected_targets=protected,
                    banned_path_nodes=frozenset(),
                )
                if forward is None:
                    restore_state(snapshot)
                    continue
                if any(square in occupant for square in corridor[1:]):
                    restore_state(snapshot)
                    continue
                capture_index = len(relocations)
                capture_path = corridor
                if occupant.get(capture) != NO_SQUARE:
                    restore_state(snapshot)
                    continue
                del occupant[capture]
                can_delay = _delay_capture_restoration and all(
                    index != primary and problem.pieces[primary].goal not in path
                    for index, path in forward
                )
                if can_delay:
                    delayed_capture_forward = forward
                elif not reverse_moves(forward):
                    restore_state(snapshot)
                    continue
                captured = True
                break
            if not captured:
                return None

        source = positions[primary]
        goal = problem.pieces[primary].goal
        direct = find_empty_path(source, goal, occupant)
        if direct is not None:
            if not append_move(primary, direct, "primary"):
                return None
        else:
            base = clone_state()
            empty = frozenset(range(BOARD_SQUARES)) - frozenset(occupant)
            components: list[frozenset[int]] = []
            unseen = set(empty)
            while unseen:
                seed = min(unseen)
                component = {seed}
                frontier = [seed]
                unseen.remove(seed)
                while frontier:
                    current = frontier.pop()
                    for neighbor, _ in orthogonal_neighbors(current):
                        if neighbor in unseen:
                            unseen.remove(neighbor)
                            component.add(neighbor)
                            frontier.append(neighbor)
                components.append(frozenset(component))
            components.sort(key=lambda item: (-len(item), min(item)))

            solved = False
            for component in components:
                if solved:
                    break
                articulation = _articulation_points(component)
                stage_candidates = sorted(
                    component - {goal},
                    key=lambda square: (
                        int(square in articulation),
                        -sum(neighbor in component for neighbor, _ in orthogonal_neighbors(square)),
                        -manhattan(square, goal),
                        manhattan(source, square),
                        square,
                    ),
                )[:16]
                for stage in stage_candidates:
                    if solved:
                        break
                    if monotonic() >= self._deadline:
                        return None
                    restore_state(base)
                    corridors = relaxed_corridors(
                        source,
                        stage,
                        occupant,
                        primary,
                        max(4, self.config.corridor_candidates),
                    )
                    for escape in corridors:
                        if goal in escape or len(escape) < 2:
                            continue
                        restore_state(base)
                        protected_escape = frozenset(escape) | {goal}
                        escape_forward = clear_corridor(
                            escape,
                            from_open_end=True,
                            protected_targets=protected_escape,
                            banned_path_nodes=frozenset({goal}),
                        )
                        if escape_forward is None:
                            continue
                        if not append_move(primary, escape, "stage"):
                            continue

                        # Plan the goal corridor without reusing the escape
                        # tunnel.  Its blockers can therefore be carried back
                        # through the open source and tunnel into free space.
                        corridor_occupant = dict(occupant)
                        corridor_occupant.pop(stage, None)
                        main = _relaxed_shortest_path(
                            source,
                            frozenset({goal}),
                            corridor_occupant,
                            NO_SQUARE,
                            banned_nodes=frozenset(escape[1:]),
                        )
                        if main is None:
                            continue
                        protected_main = frozenset(main) | frozenset(escape)
                        main_forward = clear_corridor(
                            main,
                            from_open_end=False,
                            protected_targets=protected_main,
                            banned_path_nodes=frozenset({goal}),
                        )
                        if main_forward is None:
                            continue
                        final_path = find_empty_path(stage, goal, occupant)
                        if final_path is None or not append_move(
                            primary, final_path, "primary"
                        ):
                            continue
                        if not reverse_moves(main_forward):
                            continue
                        if not reverse_moves(escape_forward):
                            continue
                        solved = positions == list(problem.goal_positions)
                        if solved:
                            break
            if not solved:
                restore_state(base)
                if delayed_capture_forward and monotonic() < self._deadline:
                    return self._constructive_plan(
                        problem,
                        started,
                        _delay_capture_restoration=False,
                    )
                return None

        if delayed_capture_forward and not reverse_moves(delayed_capture_forward):
            if monotonic() < self._deadline:
                return self._constructive_plan(
                    problem,
                    started,
                    _delay_capture_restoration=False,
                )
            return None

        statistics = PlanStatistics(
            0,
            0,
            len(disturbed),
            monotonic() - started,
            "constructive-incumbent",
            optimal=False,
        )
        plan = MotionPlan(
            problem,
            tuple(relocations),
            statistics,
            capture_index,
            capture_path,
        )
        try:
            plan.validate()
        except (PlanningError, ValueError):
            return None
        return plan

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
                _weighted_priority(
                    _ZERO_OBJECTIVE, start_h, self.config.heuristic_weight
                ),
                next(serial),
                _ZERO_OBJECTIVE,
                initial,
            )
        ]
        best_cost = {initial: _ZERO_OBJECTIVE}
        parent: dict[_SearchState, tuple[_SearchState, _SearchAction] | None] = {initial: None}

        while queue:
            if monotonic() >= self._deadline or self._expanded_total >= self.config.max_nodes:
                return None
            entry = heappop(queue)
            if entry.cost != best_cost.get(entry.state):
                continue
            if entry.state.positions == problem.goal_positions and entry.state.capture_removed:
                return entry.state, parent

            self._expanded_total += 1
            actions = self._successors(problem, entry.state, disturbance_budget, mode)
            for action in actions:
                if action.piece_index == NO_SQUARE:
                    next_state = _SearchState(
                        entry.state.positions,
                        entry.state.disturbed_mask,
                        True,
                        NO_SQUARE,
                    )
                    candidate_cost = _add_objectives(entry.cost, action.cost)
                    previous_cost = best_cost.get(next_state)
                    if previous_cost is not None and candidate_cost >= previous_cost:
                        continue
                    best_cost[next_state] = candidate_cost
                    parent[next_state] = (entry.state, action)
                    heuristic = self._heuristic(problem, next_state)
                    priority = _weighted_priority(
                        candidate_cost, heuristic, self.config.heuristic_weight
                    )
                    heappush(
                        queue,
                        _QueueEntry(priority, next(serial), candidate_cost, next_state),
                    )
                    self._generated_total += 1
                    continue
                positions = list(entry.state.positions)
                positions[action.piece_index] = action.target
                piece_bit = 1 << action.piece_index
                disturbed = entry.state.disturbed_mask
                if not problem.pieces[action.piece_index].primary:
                    disturbed |= piece_bit
                if disturbed.bit_count() > disturbance_budget:
                    continue
                next_state = _SearchState(
                    tuple(positions),
                    disturbed,
                    entry.state.capture_removed,
                    action.piece_index if mode == "exhaustive" else NO_SQUARE,
                )
                candidate_cost = _add_objectives(entry.cost, action.cost)
                previous_cost = best_cost.get(next_state)
                if previous_cost is not None and candidate_cost >= previous_cost:
                    continue
                best_cost[next_state] = candidate_cost
                parent[next_state] = (entry.state, action)
                heuristic = self._heuristic(problem, next_state)
                priority = _weighted_priority(
                    candidate_cost, heuristic, self.config.heuristic_weight
                )
                heappush(
                    queue,
                    _QueueEntry(priority, next(serial), candidate_cost, next_state),
                )
                self._generated_total += 1
        return None

    def _heuristic(self, problem: PlanningProblem, state: _SearchState) -> _Objective:
        if not state.capture_removed:
            # Capture removal itself is mandatory. Other components stay zero
            # so the bound remains admissible when blockers must move first.
            return (0, 1, 0, 0)
        mismatched = [
            index
            for index, (current, goal) in enumerate(zip(state.positions, problem.goal_positions))
            if current != goal
        ]
        if not mismatched:
            return _ZERO_OBJECTIVE
        # Every mismatched label needs at least one pickup and at least its
        # Manhattan displacement in total carried steps. The previous relaxed
        # blocker term was not admissible because it evaluated only one tied
        # corridor and could miss a route through already-disturbed pieces.
        return (
            0,
            len(mismatched),
            sum(
                manhattan(state.positions[index], problem.goal_positions[index])
                for index in mismatched
            ),
            0,
        )

    def _successors(
        self,
        problem: PlanningProblem,
        state: _SearchState,
        disturbance_budget: int,
        mode: str,
    ) -> tuple[_SearchAction, ...]:
        if not state.capture_removed:
            return self._capture_successors(
                problem, state, disturbance_budget, mode
            )
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
        blocked_primary: list[int] = []
        for index in mismatched_primary:
            path = find_empty_path(state.positions[index], problem.pieces[index].goal, occupied)
            if path and len(path) > 1:
                add(self._make_action(problem, state, index, path, "primary"))
            else:
                blocked_primary.append(index)

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
        blocked_obligations.extend(blocked_primary)
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

        if mode == "exhaustive":
            # Cost-equivalent completeness: every reachable release square is
            # represented by its best (turns, steps) path. Consecutive moves of
            # one piece are omitted because their concatenation is one legal,
            # non-worse macro action already present from the previous state.
            for index, source in enumerate(state.positions):
                if (
                    not problem.pieces[index].primary
                    and not (state.disturbed_mask & (1 << index))
                    and state.disturbed_mask.bit_count() >= disturbance_budget
                ):
                    continue
                if index == state.last_piece:
                    continue
                cache_key = (occupied, source)
                paths = self._parking_path_cache.setdefault(cache_key, {})
                for target in sorted(reachable_empty_squares(source, occupied)):
                    if target not in paths:
                        paths[target] = find_empty_path(source, target, occupied)
                    path = paths[target]
                    if path is None:
                        continue
                    purpose = self._purpose(problem, state, index, target)
                    add(self._make_action(problem, state, index, path, purpose))
        elif not actions:
            # Keep a cheap reachability fallback in heuristic modes.
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

        if mode == "exhaustive":
            actions = {
                key: action
                for key, action in actions.items()
                if action.piece_index != state.last_piece
            }
        return tuple(sorted(actions.values(), key=lambda action: (action.cost, action.piece_index, action.target)))

    def _capture_successors(
        self,
        problem: PlanningProblem,
        state: _SearchState,
        disturbance_budget: int,
        mode: str,
    ) -> tuple[_SearchAction, ...]:
        """Clear and, when supported, route the capture to any a-file exit."""

        capture = problem.captured_square
        if capture is None:
            return (
                _SearchAction(
                    NO_SQUARE, NO_SQUARE, (), "capture", (0, 1, 0, 0)
                ),
            )
        occupant = {square: index for index, square in enumerate(state.positions)}
        occupant[capture] = NO_SQUARE
        occupied = frozenset(occupant)
        lane_options: list[tuple[int, int, frozenset[int], list[int]]] = []
        if problem.edge_capture_exit:
            corridors = relaxed_corridors(
                capture,
                CAPTURE_EDGE_EXITS,
                occupant,
                NO_SQUARE,
                self.config.corridor_candidates,
            )
            for preference, corridor in enumerate(corridors):
                blockers = sorted({
                    occupant[square]
                    for square in corridor[1:]
                    if square in occupant and occupant[square] != NO_SQUARE
                })
                if not blockers:
                    turns = route_turns(corridor)
                    drags = turns + 1 if len(corridor) > 1 else 0
                    cost = (0, drags + 1, len(corridor) - 1, turns)
                    return (
                        _SearchAction(
                            NO_SQUARE, corridor[-1], corridor, "capture", cost
                        ),
                    )
                lane_options.append(
                    (len(blockers), preference, frozenset(corridor), blockers)
                )
        else:
            for preference, rank in enumerate(capture_exit_ranks(capture)):
                clearance = capture_clearance_squares(capture, rank)
                blockers = sorted({
                    occupant[square]
                    for square in clearance
                    if square in occupant and occupant[square] != NO_SQUARE
                })
                if not blockers:
                    return (
                        _SearchAction(
                            NO_SQUARE, capture, (), "capture", (0, 1, 0, 0)
                        ),
                    )
                lane_options.append(
                    (len(blockers), preference, clearance | {capture}, blockers)
                )

        actions: dict[tuple[int, int], _SearchAction] = {}
        for _count, _preference, forbidden, blockers in sorted(lane_options)[
            : self.config.corridor_candidates
        ]:
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

        def add(action: _SearchAction) -> None:
            key = (action.piece_index, action.target)
            previous = actions.get(key)
            if previous is None or action.cost < previous.cost:
                actions[key] = action

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
                    frozenset({capture}),
                    self.config.broad_candidates_per_piece,
                    allow_reserved=True,
                ):
                    add(action)

        if mode == "exhaustive":
            for index, source in enumerate(state.positions):
                if (
                    not problem.pieces[index].primary
                    and not (state.disturbed_mask & (1 << index))
                    and state.disturbed_mask.bit_count() >= disturbance_budget
                ):
                    continue
                if index == state.last_piece:
                    continue
                cache_key = (occupied, source)
                paths = self._parking_path_cache.setdefault(cache_key, {})
                for target in sorted(reachable_empty_squares(source, occupied)):
                    if target not in paths:
                        paths[target] = find_empty_path(source, target, occupied)
                    path = paths[target]
                    if path is None:
                        continue
                    purpose = self._purpose(problem, state, index, target)
                    add(self._make_action(problem, state, index, path, purpose))
        elif not actions:
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

        if mode == "exhaustive":
            actions = {
                key: action
                for key, action in actions.items()
                if action.piece_index != state.last_piece
            }
        return tuple(
            sorted(actions.values(), key=lambda action: (action.cost, action.piece_index, action.target))
        )

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
                if (
                    dependent is None
                    or dependent == NO_SQUARE
                    or dependent == blocker
                    or dependent in seen
                ):
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
        turns = route_turns(path)
        cost = (int(newly_disturbed), turns + 1, len(path) - 1, turns)
        return _SearchAction(piece_index, path[-1], path, purpose, cost)

    @staticmethod
    def _reconstruct(
        problem: PlanningProblem,
        final_state: _SearchState,
        parent: dict[_SearchState, tuple[_SearchState, _SearchAction] | None],
    ) -> tuple[tuple[Relocation, ...], int | None, tuple[int, ...]]:
        reverse: list[_SearchAction] = []
        state = final_state
        while parent[state] is not None:
            previous, action = parent[state]  # type: ignore[misc]
            reverse.append(action)
            state = previous
        reverse.reverse()
        relocations: list[Relocation] = []
        capture_index: int | None = None
        capture_path: tuple[int, ...] = ()
        for action in reverse:
            if action.piece_index == NO_SQUARE:
                capture_index = len(relocations)
                capture_path = action.path
                continue
            relocations.append(
                Relocation(
                    piece_key=problem.pieces[action.piece_index].key,
                    source=action.path[0],
                    target=action.path[-1],
                    path=action.path,
                    purpose=action.purpose,
                )
            )
        return tuple(relocations), capture_index, capture_path


def planning_problem_from_chess(
    board,
    move,
    *,
    physical_occupancy: frozenset[int] | None = None,
    deferred_capture: bool = False,
    edge_capture_exit: bool = False,
) -> PlanningProblem:
    """Adapt a legal python-chess move to the physical rearrangement model.

    The import remains local so the core planner and its algorithmic tests do not
    require python-chess merely to manipulate generic board configurations.
    """

    import chess

    if not board.is_valid():
        raise PlanningError(f"Invalid chess position (status={board.status()})")
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
        deferred_capture=deferred_capture,
        edge_capture_exit=edge_capture_exit,
    )


def plan_chess_move(
    board,
    move,
    *,
    physical_occupancy: frozenset[int] | None = None,
    deferred_capture: bool = False,
    edge_capture_exit: bool = False,
    config: PlannerConfig | None = None,
) -> MotionPlan:
    problem = planning_problem_from_chess(
        board,
        move,
        physical_occupancy=physical_occupancy,
        deferred_capture=deferred_capture,
        edge_capture_exit=edge_capture_exit,
    )
    return RearrangementPlanner(config).plan(problem)
