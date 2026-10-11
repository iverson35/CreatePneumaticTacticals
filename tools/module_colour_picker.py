"""Offline picker for the machine tool's raw-material colour.

For every module texture in the gun packs it computes a few candidate colours,
paints the raw-material sheet with each of them and renders what the game would
show, so the result can be judged outside the game:

    python tools/module_colour_picker.py [--out DIR] [--sheet PNG] [--packs DIR]

Output (default: <system temp>/cpt-colour-picker):

  <texture>.png   one strip per texture, columns = candidates, left to right:
                    mean        plain average of the opaque pixels
                    bucket16    the first in-game picker: 16 levels per channel,
                                most populated bucket, its mean (superseded)
                    hue         hue clustering: the most populated hue cluster
                                (neutral pixels form their own cluster), the
                                mean of its pixels
                    hue+bright  the same cluster, averaged over its brighter
                                half: what the eye reads as the material —
                                this is what the game paints with
                    hue+boost   the hue colour scaled so that the tinted sheet
                                keeps the module's own brightness
                  rows = the tint swatch, a 12x12 px face, a 4x4 px face and a
                  shaded cube. The faces are drawn exactly like the renderer
                  draws them (the sheet's four corners tiled over the face,
                  multiplied by the tint); the cube is the same faces with the
                  usual per-face shading added so it reads as a solid, which
                  the game gets from the lightmap instead.
  all-textures.png  the strips laid out in a 4-per-row grid, file-name order
  index.txt         which texture sits in which cell of that grid
  summary.csv       name, candidate, colour, share of the texture's pixels

Pure standard library: no PIL, no numpy.
"""

from __future__ import annotations

import argparse
import colorsys
import csv
import glob
import os
import struct
import sys
import tempfile
import zlib
from collections import defaultdict

# ---------------------------------------------------------------- PNG reading


def decode_png(path):
    """(width, height, channels, pixels) for an 8-bit PNG of any colour type."""
    data = open(path, "rb").read()
    pos, idat, width, height, depth, colour = 8, b"", 0, 0, 0, 0
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or interlace:
                raise ValueError("%s: only 8-bit non-interlaced PNGs" % path)
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"PLTE":
            palette = [chunk[i:i + 3] for i in range(0, len(chunk), 3)]
        pos += 12 + length
    if colour == 3:  # palette: expand to RGB
        raw = zlib.decompress(idat)
        stride = width
        rows = unfilter(raw, width, height, 1, stride)
        out = bytearray()
        for index in rows:
            out += palette[index]
        return width, height, 3, bytes(out)
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[colour]
    raw = zlib.decompress(idat)
    stride = width * channels
    return width, height, channels, unfilter(raw, width, height, channels, stride)


def unfilter(raw, width, height, channels, stride):
    out = bytearray()
    prev = bytearray(stride)
    pos = 0
    for _ in range(height):
        kind = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if kind == 1:
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 0xFF
        elif kind == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif kind == 3:
            for x in range(stride):
                left = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((left + prev[x]) >> 1)) & 0xFF
        elif kind == 4:
            for x in range(stride):
                left = line[x - channels] if x >= channels else 0
                up = prev[x]
                upleft = prev[x - channels] if x >= channels else 0
                p = left + up - upleft
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - upleft)
                pred = left if (pa <= pb and pa <= pc) else (up if pb <= pc else upleft)
                line[x] = (line[x] + pred) & 0xFF
        out += line
        prev = line
    return bytes(out)


def read_rgba(path):
    """(width, height, [ (r,g,b,a), ... ]) with alpha defaulted to 255."""
    width, height, channels, raw = decode_png(path)
    pixels = []
    for i in range(0, len(raw), channels):
        if channels == 1:
            pixels.append((raw[i], raw[i], raw[i], 255))
        elif channels == 2:
            pixels.append((raw[i], raw[i], raw[i], raw[i + 1]))
        elif channels == 3:
            pixels.append((raw[i], raw[i + 1], raw[i + 2], 255))
        else:
            pixels.append((raw[i], raw[i + 1], raw[i + 2], raw[i + 3]))
    return width, height, pixels


def write_png(path, width, height, pixels):
    """pixels: flat list of (r,g,b) or (r,g,b,a) tuples, row major."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            pixel = pixels[y * width + x]
            raw += bytes(pixel[:3]) + bytes([pixel[3] if len(pixel) > 3 else 255])

    def chunk(kind, payload):
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    open(path, "wb").write(png)


# ------------------------------------------------------------ colour pickers


def opaque_pixels(pixels, alpha_min=128):
    return [(r, g, b) for r, g, b, a in pixels if a >= alpha_min]


def mean_colour(pix):
    n = len(pix)
    return (sum(p[0] for p in pix) // n, sum(p[1] for p in pix) // n, sum(p[2] for p in pix) // n)


def bucket_colour(pix, bits=4):
    shift = 8 - bits
    counts = defaultdict(int)
    sums = defaultdict(lambda: [0, 0, 0])
    for r, g, b in pix:
        key = ((r >> shift) << (2 * bits)) | ((g >> shift) << bits) | (b >> shift)
        counts[key] += 1
        total = sums[key]
        total[0] += r
        total[1] += g
        total[2] += b
    best = max(counts, key=lambda k: counts[k])
    n = counts[best]
    return (sums[best][0] // n, sums[best][1] // n, sums[best][2] // n), n / len(pix)


def hue_clusters(pix, hue_bins=24, saturation_min=0.12, value_min=0.12):
    """Opaque pixels grouped by hue: -1 for the near-neutral ones, else the bin."""
    clusters = defaultdict(list)
    for p in pix:
        hue, saturation, value = colorsys.rgb_to_hsv(p[0] / 255, p[1] / 255, p[2] / 255)
        key = -1 if (saturation < saturation_min or value < value_min) else int(hue * hue_bins)
        clusters[key].append(p)
    return clusters


def luma(p):
    return 0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]


def hue_colour(pix, **kwargs):
    """Most populated hue cluster, neutral pixels in a cluster of their own."""
    clusters = hue_clusters(pix, **kwargs)
    best = max(clusters, key=lambda k: len(clusters[k]))
    members = clusters[best]
    return mean_colour(members), len(members) / len(pix), best


def bright_colour(pix, **kwargs):
    """The same cluster, averaged over its brighter half: the shade the eye reads
    as the material's colour, rather than the average of its shading."""
    clusters = hue_clusters(pix, **kwargs)
    best = max(clusters, key=lambda k: len(clusters[k]))
    members = sorted(clusters[best], key=luma)
    upper = members[len(members) // 2:]
    return mean_colour(upper), len(upper) / len(pix), best


def boost(colour, sheet_mean, sheet_max):
    """Scale a tint so the painted sheet keeps the colour's own brightness."""
    gain = 1.0 / sheet_mean
    brightest = max(colour) / 255.0
    if brightest * sheet_max * gain > 1.0:
        gain = 1.0 / (brightest * sheet_max)
    return tuple(min(255, int(round(c * gain))) for c in colour)


# --------------------------------------------------------------- the drawing


class Sheet:
    """The raw-material sheet, as the renderer samples it."""

    def __init__(self, path):
        self.width, self.height, self.pixels = read_rgba(path)
        opaque = [(r, g, b) for r, g, b, a in self.pixels if a >= 128]
        self.mean = sum(mean_colour(opaque)) / 3 / 255.0
        self.max = max(max(p) for p in opaque) / 255.0
        self.texels = min(self.width, self.height)

    def texel(self, x, y):
        x = min(self.width - 1, max(0, int(x)))
        y = min(self.height - 1, max(0, int(y)))
        r, g, b, _ = self.pixels[y * self.width + x]
        return r / 255.0, g / 255.0, b / 255.0


def face_pixels(sheet, tint, size):
    """The renderer's tiling: every quarter of the face takes the sheet's
    matching corner patch at one texel per pixel."""
    out = []
    half = size / 2.0
    for y in range(size):
        for x in range(size):
            quadrant_x = 1 if x >= half else 0
            quadrant_y = 1 if y >= half else 0
            source_x = x if quadrant_x == 0 else sheet.texels - half + (x - half)
            source_y = y if quadrant_y == 0 else sheet.texels - half + (y - half)
            texel = sheet.texel(source_x, source_y)
            out.append(tuple(
                min(255, int(round(texel[c] * tint[c]))) for c in range(3)))
    return out


def scale(pixels, size, factor):
    out = []
    for y in range(size * factor):
        for x in range(size * factor):
            out.append(pixels[(y // factor) * size + (x // factor)])
    return out


def blit(canvas, canvas_width, pixels, width, height, x0, y0):
    for y in range(height):
        for x in range(width):
            canvas[(y0 + y) * canvas_width + x0 + x] = pixels[y * width + x]


def cube_pixels(sheet, tint, size):
    """A shaded isometric cube of the painted sheet: top lit, sides darker."""
    face = face_pixels(sheet, tint, 32)
    out = [(255, 255, 255)] * (size * size)
    # corner points of the three visible faces (x, y) and their shading
    top = [(size / 2, 0), (size, size / 4), (size / 2, size / 2), (0, size / 4)]
    left = [(0, size / 4), (size / 2, size / 2), (size / 2, size), (0, size * 3 / 4)]
    right = [(size, size / 4), (size, size * 3 / 4), (size / 2, size), (size / 2, size / 2)]
    for quad, light in ((top, 1.0), (left, 0.72), (right, 0.55)):
        # two triangles of the quad; barycentric mapping back to face space
        a, b, c, d = quad
        for tri in ((a, b, c), (a, c, d)):
            (x1, y1), (x2, y2), (x3, y3) = tri
            min_x, max_x = int(min(x1, x2, x3)), int(max(x1, x2, x3)) + 1
            min_y, max_y = int(min(y1, y2, y3)), int(max(y1, y2, y3)) + 1
            denominator = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
            if denominator == 0:
                continue
            for y in range(max(0, min_y), min(size, max_y)):
                for x in range(max(0, min_x), min(size, max_x)):
                    px, py = x + 0.5, y + 0.5
                    w1 = ((y2 - y3) * (px - x3) + (x3 - x2) * (py - y3)) / denominator
                    w2 = ((y3 - y1) * (px - x3) + (x1 - x3) * (py - y3)) / denominator
                    w3 = 1 - w1 - w2
                    if w1 < 0 or w2 < 0 or w3 < 0:
                        continue
                    # the quad's corners map to the face's corners in order
                    u = w1 * 0 + w2 * 1 + w3 * 1
                    v = w1 * 0 + w2 * 0 + w3 * 1
                    sample = face[min(31, int(v * 32)) * 32 + min(31, int(u * 32))]
                    out[y * size + x] = tuple(min(255, int(round(sample[c] * light))) for c in range(3))
    return out


# ------------------------------------------------------------------- driving


def texture_paths(packs):
    found = []
    for root in packs:
        found += glob.glob(os.path.join(root, "*", "assets", "*", "textures", "gun", "*.png"))
    return sorted(set(found))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=os.path.join(tempfile.gettempdir(), "cpt-colour-picker"),
                        help="output directory (default: <system temp>/cpt-colour-picker)")
    parser.add_argument("--sheet", default=os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "src", "main", "resources", "assets", "createpneumatictacticals",
        "textures", "block", "raw_material.png"),
        help="the raw-material sheet the shell is painted with")
    parser.add_argument("--packs", default="gunpacks",
                        help="directory holding the gun packs (comma separated)")
    parser.add_argument("--alpha", type=int, default=128, help="opaque threshold")
    args = parser.parse_args(argv)

    packs = [p for p in args.packs.split(",") if p]
    sheet = Sheet(args.sheet)
    paths = texture_paths(packs)
    if not paths:
        print("no module textures under %s" % packs, file=sys.stderr)
        return 1

    os.makedirs(args.out, exist_ok=True)
    columns = ("mean", "bucket16", "hue", "hue+bright", "hue+boost")
    unit = 96          # one column's width
    rows_heights = (24, unit, unit, unit)   # swatch, 12 px face, 4 px face, cube
    header = unit      # the source texture's own row
    strip_height = header + sum(rows_heights) + 4 * (len(rows_heights) + 1)
    strips = []
    strip_names = []
    summary = []

    for path in paths:
        name = os.path.splitext(os.path.basename(path))[0]
        width, height, pixels = read_rgba(path)
        pix = opaque_pixels(pixels, args.alpha)
        if not pix:
            print("skip %s: no opaque pixels" % name, file=sys.stderr)
            continue
        bucket, bucket_share = bucket_colour(pix)
        hue, hue_share, hue_key = hue_colour(pix)
        bright, bright_share, _ = bright_colour(pix)
        colours = {
            "mean": (mean_colour(pix), 1.0),
            "bucket16": (bucket, bucket_share),
            "hue": (hue, hue_share),
            "hue+bright": (bright, bright_share),
            "hue+boost": (boost(hue, sheet.mean, sheet.max), hue_share),
        }
        for column, (colour, share) in colours.items():
            summary.append((name, column, "#%02x%02x%02x" % colour, "%.0f%%" % (share * 100)))

        canvas_width = unit * len(columns)
        canvas = [(255, 255, 255)] * (canvas_width * strip_height)
        # the source texture, upscaled to fill the header row on its own
        factor = max(1, header // max(width, height))
        scaled = []
        for y in range(height * factor):
            for x in range(width * factor):
                r, g, b, _ = pixels[(y // factor) * width + (x // factor)]
                scaled.append((r, g, b))
        blit(canvas, canvas_width, scaled, width * factor, height * factor,
             (header - width * factor) // 2, (header - height * factor) // 2)
        y = header + 4
        for index, column in enumerate(columns):
            colour, _ = colours[column]
            x0 = index * unit
            row = y
            blit(canvas, canvas_width, [colour] * (unit * rows_heights[0]), unit, rows_heights[0],
                 x0, row)
            row += rows_heights[0] + 4
            for size in (12, 4):
                blit(canvas, canvas_width, scale(face_pixels(sheet, colour, size), size, unit // size),
                     unit, unit, x0, row)
                row += unit + 4
            blit(canvas, canvas_width, cube_pixels(sheet, colour, unit), unit, unit, x0, row)

        out = os.path.join(args.out, name + ".png")
        write_png(out, canvas_width, strip_height, canvas)
        strips.append(canvas)
        strip_names.append(name)

    if strips:
        gap = 6
        across = 4
        sheet_width = unit * len(columns)
        cell_width = sheet_width + gap
        cell_height = strip_height + gap
        rows_count = (len(strips) + across - 1) // across
        contact = [(255, 255, 255)] * (cell_width * across * cell_height * rows_count)
        for index, strip in enumerate(strips):
            blit(contact, cell_width * across, strip, sheet_width, strip_height,
                 (index % across) * cell_width, (index // across) * cell_height)
        write_png(os.path.join(args.out, "all-textures.png"),
                  cell_width * across, cell_height * rows_count, contact)

    with open(os.path.join(args.out, "index.txt"), "w", newline="", encoding="utf-8") as handle:
        handle.write("all-textures.png is a %d-per-row grid, row-major:\n\n" % across)
        for index, strip_name in enumerate(strip_names):
            handle.write("%2d: %s\n" % (index + 1, strip_name))
        handle.write("\neach cell, top to bottom: the source texture, then for every column\n"
                     "(%s) the tint swatch, the 12 px and 4 px shell faces and the cube.\n"
                     % ", ".join(columns))

    with open(os.path.join(args.out, "summary.csv"), "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("texture", "picker", "colour", "share"))
        writer.writerows(summary)

    print("%d textures -> %s" % (len(strips), os.path.abspath(args.out)))
    print("columns: %s" % ", ".join(columns))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
