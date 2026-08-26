import { BOARD_LAYOUT } from "../model.js";

export const FILES = "abcdefgh";
export const RANKS = "12345678";

export function squareToBoardPosition(square) {
  return [
    (FILES.indexOf(square?.[0]) - 3.5) * BOARD_LAYOUT.squareSize,
    BOARD_LAYOUT.surfaceY,
    (RANKS.indexOf(square?.[1]) - 3.5) * BOARD_LAYOUT.squareSize,
  ];
}

export function boardPositionToSquare(x, z) {
  const file = Math.round(x / BOARD_LAYOUT.squareSize + 3.5);
  const rank = Math.round(z / BOARD_LAYOUT.squareSize + 3.5);
  if (file < 0 || file > 7 || rank < 0 || rank > 7) return null;
  return `${FILES[file]}${RANKS[rank]}`;
}

export function toggleSpatialMode(currentMode, requestedMode) {
  return requestedMode !== "play" && currentMode === requestedMode
    ? "play"
    : requestedMode;
}
