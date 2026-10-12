"""Build the weapon pegboard's board models and the seamless board tile.

The block is a 3 px frame plate plus a 0.5 px board raised off it, and plates of
one material and facing that share a merged rectangle read as ONE board. That
used to be a Create connected texture over the board's front: one 4x4 sheet of
16 variants per material, picked per block, with the shared border carrying the
pattern one period further out and every other border holed out to alpha so the
plate's frame showed through the hole.

The connection is still the same test, but it is now baked into the state: the
block carries `left`/`right`/`up`/`down` (the board runs on across that border)
and this tool writes one model per combination. The board box itself is grown to
the block boundary on every merged border and pulled back 1 px on every other,
so

  * merged: the board reaches the boundary and butts into the neighbour's board
    - flush, no step, no seam line, and the pattern keeps its phase because both
    plates sample the same seamless tile over their own uv rectangle;
  * other: the 1 px the board gives up is the groove the frame shows through -
    the same recess the alpha holes used to make, and the frame behind it is
    what the groove's mouth opens onto, so no cap or other filler is needed.

Nothing needs alpha test any more.

Authored (north facing) model space, matching the blockstate's `y` rotation:

    +x = the viewer's left when facing the plate's front (left)
    +y = up (up), +z = from the room (0) toward the wall (16)

The board tile's sprite is mirrored against model x - sprite u = 0 is the
model x = 16 border, v = 0 the model y = 16 one - which is why the front face's
uv rectangle starts at the merged border: uv [0,0,16,16] for a fully merged
plate is the whole tile, and a lone plate shows its interior [1,1,15,15], the
same 14x14 the pre-raise board showed.

A cut face (the 0.5 px board edge standing in the groove) is the band of the
sprite the front face shows along that very border, stretched over the board's
own thickness: 1 texel wide, and as long as the board is wide there - 14 px on a
lone plate, 15 or 16 next to a merged border - so front and cut face meet on the
sprite line they share instead of on a full-width smear of the tile. FaceInfo
orders a face's quad so its u runs (0,1)->(2,3) and its v runs (0,3)->(1,2),
which puts u = 16 - x on a north face, u = x on up/down and v = 16 - y
everywhere on the sides; `cut_uv` below is that, in uv rectangle form.

The authored art is `tools/pegboard/weapon_pegboard_<material>.png`: the board
face with its 1 px rim ring, which is exactly the ring a merged border replaces.
From it this tool derives `weapon_pegboard_<material>.png` in the mod (ring
gone: the board's face runs to every edge) and then emits, per material,
`weapon_pegboard_<material>_<mask>.json` for mask 0..15 (bit 0 left, 1 right,
2 up, 3 down - a set bit is a merged border), the blockstate with one variant
per state, and `models/item/weapon_pegboard_<material>.json` as the lone-plate
model.

Mask and tile are re-derived in `check_*` from the model and sprite geometry
rather than from the tables above, so a swapped or misaligned entry cannot slip
through.

Run from the repository root:  python tools/make_pegboard_models.py
"""

import json
import re
from pathlib import Path

import numpy as np
from PIL import Image

MOD_ID = "createpneumatictacticals"
ASSETS = Path("src/main/resources/assets") / MOD_ID
TEXTURES = ASSETS / "textures/block"
MODELS = ASSETS / "models/block"
ITEM_MODELS = ASSETS / "models/item"
BLOCKSTATES = ASSETS / "blockstates"
SOURCE = Path("tools/pegboard")
MATERIAL_SOURCE = Path("src/main/java/dev/ignis/createpneumatictacticals/block/PegboardMaterial.java")

# model z (px): the frame plate against the wall, the board raised off it
PLATE = (13.0, 16.0)
BOARD = (12.5, 13.0)

# the border ring of the authored tile, as sprite indices: 0 is the left/up
# border (model x/y = 16), 15 the right/down one (model x/y = 0)
RING = (0, 15)
SIDES = ("left", "right", "up", "down")

PLATE_ELEMENT = {
    "from": [0, 0, PLATE[0]],
    "to": [16, 16, PLATE[1]],
    "faces": {
        "north": {"uv": [0, 0, 16, 16], "texture": "#frame"},
        "south": {"uv": [0, 0, 16, 16], "texture": "#back"},
        "east": {"uv": [0, 0, 16, 16], "texture": "#edge"},
        "west": {"uv": [16, 0, 0, 16], "texture": "#edge"},
        "up": {"uv": [16, 0, 0, 16], "texture": "#edge", "rotation": 90},
        "down": {"uv": [0, 0, 16, 16], "texture": "#edge", "rotation": 90},
    },
}


def materials():
    """The material suffixes, read from the enum so a new one cannot be missed."""
    source = MATERIAL_SOURCE.read_text(encoding="utf-8")
    found = re.findall(r'^\s*[A-Z_]+\(\s*"([a-z_]+)",\s*"(_[a-z_]+)"\s*\)', source, re.M)
    if not found:
        raise SystemExit(f"no materials found in {MATERIAL_SOURCE}")
    return [suffix for _, suffix in found]


def period(tile):
    """The board's pattern period (px) inside the 14x14 the board face leaves."""
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


def board_tile(tile, p):
    """The board face without its rim ring: every ring pixel becomes the tile's
    own pattern one period further out, so the pattern runs to every edge and
    two merged plates' faces read as one pattern across the seam."""
    out = tile.copy()
    for v in range(16):
        for u in range(16):
            if u in RING or v in RING:
                out[v, u] = tile[shifted(v, p), shifted(u, p)]
    return out


def merged(mask):
    """The merged borders of a mask: bit 0 left, 1 right, 2 up, 3 down."""
    return {side: bool(mask & 1 << i) for i, side in enumerate(SIDES)}


def cut_uv(side, x0, y0, x1, y1):
    """The uv rectangle of the board's cut face on `side`.

    It shows the sprite band the front face shows along that border, one texel
    across the board's 0.5 px thickness, and it runs the board's own length
    there: y0..y1 on a column (left/right), x0..x1 on a row (up/down). FaceInfo
    puts a face's u on vertices (0,1) and its v on (0,3), which for a north
    board means the front mirrors about x (u = 16 - x) while the horizontal
    faces do not (u = x) - the row bands therefore come out reversed, so their
    texels land where the front put them.
    """
    if side == "left":       # the box's east face, showing the sprite's k = 16 - x1 column
        return [16 - x1, 16 - y1, 17 - x1, 16 - y0]
    if side == "right":      # the box's west face, the front's rightmost column
        return [15 - x0, 16 - y1, 16 - x0, 16 - y0]
    if side == "up":         # the box's up face, the front's top row
        return [16 - x0, 16 - y1, 16 - x1, 17 - y1]
    return [16 - x0, 15 - y0, 16 - x1, 16 - y0]   # the box's down face, the front's bottom row


def board_element(mask):
    """The board: box grown to the boundary on merged borders, 1 px short on the
    rest, its front showing that same rectangle of the seamless tile and the cut
    faces of the borders it stops at showing the tile's edge band."""
    on = merged(mask)
    x0, x1 = (0 if on["right"] else 1), (16 if on["left"] else 15)
    y0, y1 = (0 if on["down"] else 1), (16 if on["up"] else 15)
    u0, u1 = (0 if on["left"] else 1), (16 if on["right"] else 15)
    v0, v1 = (0 if on["up"] else 1), (16 if on["down"] else 15)

    faces = {"north": {"uv": [u0, v0, u1, v1], "texture": "#board"}}
    for side, axis in (("left", "east"), ("right", "west"), ("up", "up"), ("down", "down")):
        if not on[side]:
            faces[axis] = {"uv": cut_uv(side, x0, y0, x1, y1), "texture": "#board"}
    return {"from": [x0, y0, BOARD[0]], "to": [x1, y1, BOARD[1]], "faces": faces}


def block_model(material, mask):
    return {
        "parent": "block/block",
        "textures": {
            "particle": f"{MOD_ID}:block/weapon_pegboard{material}",
            "board": f"{MOD_ID}:block/weapon_pegboard{material}",
            "frame": f"{MOD_ID}:block/weapon_pegboard_frame{material}",
            "edge": f"{MOD_ID}:block/weapon_pegboard_edge{material}",
            "back": f"{MOD_ID}:block/weapon_pegboard_back{material}",
        },
        "elements": [PLATE_ELEMENT, board_element(mask)],
    }


def model_name(material, mask):
    return f"weapon_pegboard{material}_{mask}"


def state_key(facing, material, mask):
    on = merged(mask)
    sides = ",".join(f"{side}={'true' if on[side] else 'false'}" for side in SIDES)
    return f"facing={facing},material={material[1:]},{sides}"


def blockstate(suffixes):
    variants = {}
    for material in suffixes:
        for mask in range(16):
            for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
                variant = {"model": f"{MOD_ID}:block/{model_name(material, mask)}"}
                if y:
                    variant["y"] = y
                variants[state_key(facing, material, mask)] = variant
    return {"variants": variants}


def write_json(path, data):
    """Write only when the bytes change, so re-running does not churn the tree."""
    text = json.dumps(data, indent=2) + "\n"
    if path.exists() and path.read_text(encoding="utf-8") == text:
        return False
    path.write_text(text, encoding="utf-8")
    return True


def write_png(path, pixels):
    if path.exists() and (np.array(Image.open(path).convert("RGBA")) == pixels).all():
        return False
    Image.fromarray(pixels).save(path)
    return True


def check_tile(tile, board, p, material):
    """Re-derive the board tile from where each pixel sits in the sprite."""
    tag = f"weapon_pegboard{material}"
    assert (board[1:15, 1:15] == tile[1:15, 1:15]).all(), f"{tag}: board interior is not the tile"
    assert (board[..., 3] == 255).all(), f"{tag}: the board tile must be opaque"
    for v in range(16):
        for u in range(16):
            if u not in RING and v not in RING:
                continue
            want = tile[shifted(v, p), shifted(u, p)]
            assert (board[v, u] == want).all(), f"{tag}: ring {v},{u} is not the pattern"
    # the two faces a merged border joins read as consecutive pattern lines: the
    # band one plate shows at its edge and the edge line the other one starts at
    assert (board[1:15, RING[0]] == tile[1:15, p]).all(), f"{tag}: left band"
    assert (board[1:15, RING[1]] == tile[1:15, 15 - p]).all(), f"{tag}: right band"
    assert (board[RING[0], 1:15] == tile[p, 1:15]).all(), f"{tag}: up band"
    assert (board[RING[1], 1:15] == tile[15 - p, 1:15]).all(), f"{tag}: down band"


def check_model(material, mask, model, board):
    """Re-derive the board box, its front uv and its cut faces from the mask."""
    tag = model_name(material, mask)
    on = merged(mask)
    assert model["parent"] == "block/block" and len(model["textures"]) == 5, f"{tag}: header"
    assert model["textures"]["board"] == f"{MOD_ID}:block/weapon_pegboard{material}", f"{tag}: board texture"
    assert model["elements"][0] == PLATE_ELEMENT, f"{tag}: plate is not the canonical one"
    assert (model["elements"][0]["from"][2], model["elements"][0]["to"][2]) == PLATE, f"{tag}: plate depth"
    assert len(model["elements"]) == 2, f"{tag}: the plate and the board are the whole model"

    body = model["elements"][1]
    x0, x1 = (0 if on["right"] else 1), (16 if on["left"] else 15)
    y0, y1 = (0 if on["down"] else 1), (16 if on["up"] else 15)
    u0, u1 = (0 if on["left"] else 1), (16 if on["right"] else 15)
    v0, v1 = (0 if on["up"] else 1), (16 if on["down"] else 15)
    assert body["from"] == [x0, y0, BOARD[0]] and body["to"] == [x1, y1, BOARD[1]], f"{tag}: board box"
    assert body["faces"]["north"]["uv"] == [u0, v0, u1, v1], f"{tag}: front uv"
    assert body["faces"]["north"]["texture"] == "#board", f"{tag}: front texture"
    # the front draws the sprite 1:1: same width and height as the box
    assert (u1 - u0) == (x1 - x0) and (v1 - v0) == (y1 - y0), f"{tag}: front uv does not match the box"

    for side, axis in (("left", "east"), ("right", "west"), ("up", "up"), ("down", "down")):
        if on[side]:
            assert axis not in body["faces"], f"{tag}: merged {side} still draws its cut face"
            continue
        cut = body["faces"][axis]
        assert cut["uv"] == cut_uv(side, x0, y0, x1, y1), f"{tag}: {side} cut uv"
        assert cut["texture"] == "#board", f"{tag}: {side} cut texture"
        if side in ("left", "right"):
            # a column: the front's own edge texel across the board's thickness,
            # as tall as the front still is there
            assert cut["uv"][2] - cut["uv"][0] == 1, f"{tag}: {side} cut is not a 1 texel band"
            assert (cut["uv"][1], cut["uv"][3]) == (v0, v1), f"{tag}: {side} cut does not span the front's rows"
            band = cut["uv"][0]
            line = board[:, band]
        else:
            # a row: 1 texel over the board's thickness, and exactly the board's
            # width along the border - 14 px on a lone plate, more next to a
            # merged border - sampled mirrored about x the way the front is
            assert cut["uv"][3] - cut["uv"][1] == 1, f"{tag}: {side} cut is not a 1 texel band"
            assert (cut["uv"][0], cut["uv"][2]) == (16 - x0, 16 - x1), f"{tag}: {side} cut u"
            assert abs(cut["uv"][2] - cut["uv"][0]) == x1 - x0, f"{tag}: {side} cut is not the board's width"
            band = cut["uv"][1]
            line = board[band, :]
        assert line[..., 3].all(), f"{tag}: {side} band line is not opaque in the board tile"
        # and that band is the very sprite line the front face's edge sits on
        assert band == {"left": u0, "right": u1 - 1, "up": v0, "down": v1 - 1}[side], \
            f"{tag}: {side} band is not the front face's edge line"


def main():
    suffixes = materials()
    boards = {}
    for material in suffixes:
        path = SOURCE / f"weapon_pegboard{material}.png"
        tile = np.array(Image.open(path).convert("RGBA"))
        if tile.shape[:2] != (16, 16):
            raise SystemExit(f"{path} must be 16x16, is {tile.shape[1]}x{tile.shape[0]}")
        p = period(tile)
        board = board_tile(tile, p)
        check_tile(tile, board, p, material)
        boards[material] = board
        target = TEXTURES / f"weapon_pegboard{material}.png"
        if write_png(target, board):
            print(f"wrote {target}")
        for mask in range(16):
            model = block_model(material, mask)
            check_model(material, mask, model, board)
            name = f"{model_name(material, mask)}.json"
            if write_json(MODELS / name, model):
                print(f"wrote {MODELS / name}")
        item = {"parent": f"{MOD_ID}:block/{model_name(material, 0)}"}
        if write_json(ITEM_MODELS / f"weapon_pegboard{material}.json", item):
            print(f"wrote {ITEM_MODELS / f'weapon_pegboard{material}.json'}")

    states = blockstate(suffixes)
    assert len(states["variants"]) == len(suffixes) * 16 * 4, "blockstate variant count"
    for material in suffixes:
        for mask in range(16):
            for facing in ("north", "east", "south", "west"):
                key = state_key(facing, material, mask)
                assert key in states["variants"], f"missing variant {key}"
                want = f"{MOD_ID}:block/{model_name(material, mask)}"
                assert states["variants"][key]["model"] == want, f"{key} points at the wrong model"
    if write_json(BLOCKSTATES / "weapon_pegboard.json", states):
        print(f"wrote {BLOCKSTATES / 'weapon_pegboard.json'}")
    print(f"{len(suffixes)} materials, {len(suffixes) * 16} board models, "
          f"{len(states['variants'])} blockstates")


if __name__ == "__main__":
    main()
