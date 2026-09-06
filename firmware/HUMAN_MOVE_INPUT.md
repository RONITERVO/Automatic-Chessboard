# Human-confirmed play (5.1.0)

The software position is authoritative in standalone Nano games and companion
games. Set up the ordinary chess starting position before starting. Calibration
still establishes the carriage position, but starting pieces are not scanned or
validated. Reed switches are suggestions for human input, not feedback for robot
motion or a reason to stop a game.

## Playing on the Nano

1. Press A in the main menu to start and calibrate. Follow any carriage recovery
   prompt. The game then starts directly from the standard position.
2. Move your piece by hand, including capture removal or castling's rook.
3. Press A. The Nano takes one filtered reed snapshot and displays `MOVE e2-e4`.
4. If that is your move, press A again. This accepts the displayed proposal;
   it does not scan the board again. The rules engine checks the move and the
   robot plays its reply using software occupancy.
5. If the proposal is wrong, press B. In `FROM`, B cycles your pieces and A
   selects the source. In `TO`, B cycles squares and A selects the destination.
   Review the resulting move and press A once more to accept it.

B also opens the editor before detection, for a move whose switches never
registered. Hold B for more than two seconds and release to return to the menu.
A rejected chess move returns to correction; it never demands a matching reed
pattern or a physical undo. Standalone Micro-Max retains queen promotion;
companions offer Q/R/B/N.

The suggestion filter considers piece movement geometry and source/destination
evidence. It does not treat unrelated extra or missing switches as errors.
A single snapshot cannot distinguish every capture: the captured square stays
occupied, and nearby magnets can obscure either endpoint. Always read the
proposal. Square correction works even with no useful sensor input.

## Robot moves and manual assistance

Movement, capture removal, en passant, castling, and routing update the stored
position. No reed checks run before, during, or after those game actions.
Existing motor limits, homing, motion faults, magnet timeout, routing constraints,
and stop controls remain active.

When a standalone capture cannot reach the bin, the LCD requests `REMOVE`.
Remove that piece and press A. The Nano trusts that confirmation and retries its
route. If the moving piece is still trapped, complete the displayed `MANUAL`
move and press A. These are confirmations of requested manual actions, not
sensor checks.

## Phone and Windows

Install matching 5.1.0 firmware and apps. Default human input uses the physical
two-press flow above. The board sends `MOVE <from><to>` only after confirmation.
The companion validates legality and sends `ACCEPT`, or `ACCEPT q|r|b|n` for
promotion. `REJECT` returns the board to correction without changing its position.

During a game, `BOARD` returns software occupancy. Apps cross-check that frame
against their command sequence; they do not compare game moves with reed
readings. Idle `BOARD` and explicit builder movement diagnostics still expose
raw reeds. The optional App input mode continues to execute both sides from
screen-selected moves and retain its visual-confirmation interaction.

The hosted simulator executes the same compiled Nano HEX. It starts with pieces
arranged and uses the same detect/confirm/editor controls. GitHub Pages rebuilds
the firmware when this release reaches main.

## Verification

`site/tests/firmware-human-input.test.mjs` runs the real AVR binary through
no-background-scanning, preview/accept, noisy input, capture, en passant,
castling, underpromotion, rejection/correction, game exit, and a robot route with
every physical reed open. The AI stack regression also uses the two-press flow.
The resource limits remain 29,900 flash bytes and 1,115 global SRAM bytes for
the Nano; no second piece table or dynamic allocation is added.
