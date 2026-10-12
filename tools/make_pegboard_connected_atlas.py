"""Build the board's connection sheets.

The raised board is the block's front: a 0.5 px sheet whose front quad covers
the whole 16x16 face with uv 0..16, so its pixels are the plain board tile
`weapon_pegboard_<material>` at exactly the coordinates they had before the
sheet was raised, and whose four rim faces hug the 1 px inset (`from [1,1,12.5]
to [15,15,13]`), so they stand on the plate's own frame wherever the board
ends. That front quad is the connected one; a cell of this 4x4 sheet is the
tile with its 1 px border ring redone:

  * border shared with a plate of the same panel - the board runs on across the
    seam, so the ring pixel there becomes the tile's own pattern one period
    further out (left/up -P, right/down +P, P the pattern's period, 4 for every
    material today): the pattern keeps its phase through the seam instead of
    hitting the border ring there. A's right band, B's left band and B's first
    inner column then read as three consecutive pattern columns, and the sheet
    stays raised and flush across the seam - no step, no seam line.
  * border next to anything else (no neighbour, another facing, another
    material, outside the merged rectangle) - the board ends there, so the ring
    pixel goes to alpha 0: through it the plate, 0.5 px further back, shows its
    own frame. That hole is the recess the raise is supposed to have; without
    it the seam pixel would sit at the raised height even where the board is
    not there.
  * a corner pixel goes with the two borders meeting there: opaque only when
    BOTH of them merge, else it belongs to a hole.

The interior (the 14x14 the rim inset leaves) stays the tile untouched, which
is what keeps front and item model identical to the pre-raise board.

The block needs alpha test for those holes - see ClientModEvents.

Create's AllCTTypes.RECTANGLE picks the cell from the two neighbour pairs:

    x = left && right ? 2 : left ? 3 : right ? 1 : 0
    y = up && down ? 1 : up ? 2 : down ? 0 : 3
    index = x + y * 4

so the sides that keep their frame are:  left x in (0, 1)   right x in (0, 3)
                                         up   y in (0, 3)   down  y in (2, 3)

Run from the repository root:  python tools/make_pegboard_connected_atlas.py
"""

from pathlib import Path

import numpy as np
from PIL import Image

TEXTURES = Path("src/main/resources/assets/createpneumatictacticals/textures/block")
MATERIALS = ["wood", "andesite", "brass", "cast_iron"]

# cell index pair -> may this side keep its frame
KEEP_FRAME = {
    "left": lambda x, y: x in (0, 1),
    "right": lambda x, y: x in (0, 3),
    "up": lambda x, y: y in (0, 3),
    "down": lambda x, y: y in (2, 3),
}

# the ring pixel at sprite index 0 sits one pattern step left of the first inner
# column, the one at 15 one step right of the last: read the pattern line that
# far out instead, so the phase carries on over the seam
RING = (0, 15)


def period(tile):
    """The board's pattern period (px) inside the 14x14 the rim inset leaves."""
    inner = tile[1:15, 1:15]
    for p in range(2, 8):
        if (inner[:, :-p] == inner[:, p:]).all() and (inner[:-p, :] == inner[p:, :]).all():
            return p
    raise SystemExit("weapon_pegboard tile has no constant pattern period")


def shifted(index, p):
    """The pattern line one period further out from a ring pixel, unchanged inside."""
    if index == RING[0]:
        return index + p
    if index == RING[1]:
        return index - p
    return index


def cell(tile, x, y, p):
    """One 16x16 atlas cell: the tile, its border ring boarded over where this
    plate merges along that border and holed out where it does not."""
    out = tile.copy()
    merged = {side: not keep(x, y) for side, keep in KEEP_FRAME.items()}

    for v in range(16):
        for u in range(16):
            if u not in RING and v not in RING:
                continue
            sides = []
            if u == RING[0]:
                sides.append("left")
            if u == RING[1]:
                sides.append("right")
            if v == RING[0]:
                sides.append("up")
            if v == RING[1]:
                sides.append("down")
            out[v, u] = tile[shifted(v, p), shifted(u, p)] if all(merged[s] for s in sides) else 0

    return out


def check(sheet, tile, p):
    """Re-derive all 16 cells from where each ring pixel sits in the sprite, not
    from the tables above: its sides follow from its own v/u position, so a
    swapped or misaligned entry cannot slip through.

    u=0 is the model-x=16 border and u=15 the model-x=0 one, v=0 the model-y=16
    border and v=15 the model-y=0 one; the state (x, y) is left/right x up/down.
    """
    for x in range(4):
        for y in range(4):
            merged = {
                "left": x in (2, 3),
                "right": x in (1, 2),
                "up": y in (1, 2),
                "down": y in (0, 1),
            }
            cellpx = sheet[y * 16:(y + 1) * 16, x * 16:(x + 1) * 16]
            tag = f"cell x={x} y={y}"
            assert (cellpx[1:15, 1:15] == tile[1:15, 1:15]).all(), f"{tag}: interior is not the tile"

            for v in range(16):
                for u in range(16):
                    if u not in RING and v not in RING:
                        continue
                    sides = []
                    if u == RING[0]:
                        sides.append("left")
                    if u == RING[1]:
                        sides.append("right")
                    if v == RING[0]:
                        sides.append("up")
                    if v == RING[1]:
                        sides.append("down")
                    if all(merged[s] for s in sides):
                        want = tile[shifted(v, p), shifted(u, p)]
                        assert (cellpx[v, u] == want).all(), f"{tag}: ring {v},{u} is not the pattern"
                    else:
                        assert cellpx[v, u, 3] == 0, f"{tag}: ring {v},{u} should be a hole"

            # the bands the merge shows: one pattern column/row per side, phase
            # carried on, so a plate merged on one side only still reads right
            if merged["left"]:
                assert (cellpx[1:15, 0] == tile[1:15, p]).all(), f"{tag}: left band"
            if merged["right"]:
                assert (cellpx[1:15, 15] == tile[1:15, 15 - p]).all(), f"{tag}: right band"
            if merged["up"]:
                assert (cellpx[0, 1:15] == tile[p, 1:15]).all(), f"{tag}: up band"
            if merged["down"]:
                assert (cellpx[15, 1:15] == tile[15 - p, 1:15]).all(), f"{tag}: down band"


def main():
    for material in MATERIALS:
        tile = np.array(Image.open(TEXTURES / f"weapon_pegboard_{material}.png").convert("RGBA"))
        if tile.shape[:2] != (16, 16):
            raise SystemExit(f"weapon_pegboard_{material}.png must be 16x16")
        p = period(tile)

        sheet = np.zeros((64, 64, 4), dtype=np.uint8)
        for y in range(4):
            for x in range(4):
                index = x + y * 4
                top, left = (index // 4) * 16, (index % 4) * 16
                sheet[top:top + 16, left:left + 16] = cell(tile, x, y, p)

        # a plate that merges nowhere is the tile with its whole ring holed out,
        # one that merges on all four sides is the tile with every ring pixel
        # replaced by the pattern - the same pixels, one period out
        alone = sheet[48:64, 0:16]
        assert (alone[1:15, 1:15] == tile[1:15, 1:15]).all(), "the alone cell is not the tile"
        ring = np.zeros((16, 16), bool)
        ring[0, :] = ring[15, :] = ring[:, 0] = ring[:, 15] = True
        assert (alone[..., 3][ring] == 0).all(), "an unmerged border is not holed out"
        whole = sheet[16:32, 32:48]
        assert (whole[..., 3] == 255).all(), "a fully merged border is not solid"
        check(sheet, tile, p)

        target = TEXTURES / f"weapon_pegboard_connected_{material}.png"
        Image.fromarray(sheet).save(target)
        print(f"wrote {target} (period {p})")


if __name__ == "__main__":
    main()
