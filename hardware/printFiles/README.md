# 3D-print files

This directory separates models created for this project from third-party
models that have their own authors and license terms.

## Project-authored models

The files in [`project_authored`](project_authored/) were designed for this
Automatic Chessboard project:

- `enclosure/` contains the tested enclosure model.
- `board_tiles/` contains the common chessboard tile with a sensor cutout.
- `frame_spacers/` contains the TPU spacers pressed between the case and frame.

These project-authored models are covered by the repository's
[CC BY-NC-SA 4.0 license](../../LICENSE.md). They are part of a modified build
derived from the Greg06 Automated Chessboard project; see
[`ATTRIBUTION.md`](../../ATTRIBUTION.md).

## Third-party models

Third-party models are stored under [`third_party`](third_party/) with their
source information, attribution, original documentation, and license files.
Do not assume that one third-party model's license applies to another model or
to the project-authored files.

| Model | Creator | Source | License |
| --- | --- | --- | --- |
| Automatic Chessboard | Passe06 | [Thingiverse 5191069](https://www.thingiverse.com/thing:5191069) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| 2020 V-Slot Aluminium Profile | icimdengelen | [Thingiverse 4859905](https://www.thingiverse.com/thing:4859905) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| Corner Bracket (20x20x20mm) | Michaelo | [Thingiverse 2504141](https://www.thingiverse.com/thing:2504141) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |

## Optional printed V-slot components

The third-party [`2020 V-Slot Aluminium Profile`](third_party/icimdengelen_2020_vslot_aluminium_profile_4859905/)
model is included for builders who want to investigate printing a 20 x 20 mm
V-slot profile instead of using an aluminium extrusion. The third-party
[`Corner Bracket (20x20x20mm)`](third_party/michaelo_corner_bracket_20x20x20mm_2504141/)
package provides M3- and M5-nut bracket variants for joining compatible
20 x 20 mm extrusions.

For this chessboard, a complete rail replacement set must finish at these
longitudinal lengths while retaining the 20 x 20 mm cross-section:

| Qty | Length | Intended role |
| ---: | ---: | --- |
| 2 | 345 mm | Side frame rails |
| 1 | 350 mm | Long frame cross rail |
| 1 | 315 mm | Short frame cross rail |
| 1 | 395 mm | Moving gantry rail |

Change or segment only the model's longitudinal axis; uniformly scaling the
STL would make its V-slot cross-section incompatible with the wheels and
brackets. See [`MECHANICAL.md`](../MECHANICAL.md) for structural and acceptance
requirements.

Printed plastic parts should not be assumed to match the stiffness, strength,
wear resistance, or dimensional accuracy of aluminium components; validate the
material, print settings, dimensions, fastener fit, and loaded behavior before
relying on them in the machine.
