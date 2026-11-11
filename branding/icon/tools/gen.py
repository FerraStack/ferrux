#!/usr/bin/env python3
"""FerraStack launcher icon family generator (Ferrux, Ferride, Lomio).

usage: gen.py <app> <outdir>

Writes the SVG masters on the 108x108 adaptive-icon canvas:
  ic_launcher_background.svg, ic_launcher_foreground.svg,
  ic_launcher_monochrome.svg
and icon.svg, the flattened store / full-bleed icon (the 72 dp visible area).
The VectorDrawables in res/drawable are converted from these with svg2vd.py;
see build.sh. Brand DNA: rounded hexagon, orange (#FF8A00/#FF6A00) to violet
(#7B2CFF/#9B4DFF) on near-black #0B0814, bold geometric glyph, no glow.
"""
import math
import os
import sys
from xml.dom import minidom


# Brand palette (from the org avatar)
ORANGE_A, ORANGE_B = "#FF8A00", "#FF6A00"
VIOLET_A, VIOLET_B = "#7B2CFF", "#9B4DFF"
BG = "#0B0814"
BG_LIFT = "#171029"   # centre of the very subtle background lift
HEX_FILL = "#130C22"  # hexagon interior, one step above the background

C = 54.0           # canvas centre (108 dp canvas)
HEX_R = 31.0       # hexagon circumradius, stroke centreline
HEX_STROKE = 5.5
HEX_CORNER = 6.0   # corner radius on the stroke centreline
GLYPH_W = 6.5      # glyph stroke weight


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def hexagon_path(cx, cy, r, corner):
    """Pointy-top hexagon (vertical left/right edges) with circular corners."""
    pts = [(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a)))
           for a in (-90, -30, 30, 90, 150, 210)]
    t = corner / math.tan(math.radians(60))  # tangent length, interior angle 120
    d = []
    n = len(pts)
    for i in range(n):
        p, v, q = pts[i - 1], pts[i], pts[(i + 1) % n]

        def toward(a, b, dist):
            dx, dy = b[0] - a[0], b[1] - a[1]
            ln = math.hypot(dx, dy)
            return (a[0] + dx / ln * dist, a[1] + dy / ln * dist)

        a_ = toward(v, p, t)
        b_ = toward(v, q, t)
        d.append(("M" if i == 0 else "L") + f"{f(a_[0])},{f(a_[1])}")
        d.append(f"A{f(corner)},{f(corner)} 0 0 1 {f(b_[0])},{f(b_[1])}")
    d.append("Z")
    return " ".join(d)


def sparkle_path(cx, cy, rx, ry, waist):
    """Four-point sparkle; `waist` = half-width of the pinch near the centre."""
    k = waist
    return (f"M{f(cx)},{f(cy - ry)} "
            f"C{f(cx + k * 0.35)},{f(cy - k)} {f(cx + k)},{f(cy - k * 0.35)} {f(cx + rx)},{f(cy)} "
            f"C{f(cx + k)},{f(cy + k * 0.35)} {f(cx + k * 0.35)},{f(cy + k)} {f(cx)},{f(cy + ry)} "
            f"C{f(cx - k * 0.35)},{f(cy + k)} {f(cx - k)},{f(cy + k * 0.35)} {f(cx - rx)},{f(cy)} "
            f"C{f(cx - k)},{f(cy - k * 0.35)} {f(cx - k * 0.35)},{f(cy - k)} {f(cx)},{f(cy - ry)} Z")


def bubble_path(x0, y0, x1, y1, r, tail):
    """Rounded-rect speech bubble with a tail at bottom-left.
    tail = (tx_start, tip_x, tip_y, tx_end) along the bottom edge."""
    ts, tipx, tipy, te = tail
    return (f"M{f(x0 + r)},{f(y0)} H{f(x1 - r)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(x1)},{f(y0 + r)} V{f(y1 - r)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(x1 - r)},{f(y1)} H{f(te)} "
            f"L{f(tipx)},{f(tipy)} L{f(ts)},{f(y1)} H{f(x0 + r)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(x0)},{f(y1 - r)} V{f(y0 + r)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(x0 + r)},{f(y0)} Z")


# --- shared gradients (userSpaceOnUse so they convert 1:1 to VectorDrawable) ---
def lin(gid, x1, y1, x2, y2, stops):
    s = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
    return (f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
            f'x1="{f(x1)}" y1="{f(y1)}" x2="{f(x2)}" y2="{f(y2)}">{s}</linearGradient>')


HEX_GRAD = lin("hex", C - HEX_R, C, C + HEX_R, C,
               [(0, ORANGE_A), (0.32, ORANGE_B), (0.68, VIOLET_A), (1, VIOLET_B)])
ORANGE_GRAD = lin("orange", 0, 40, 0, 68, [(0, ORANGE_A), (1, ORANGE_B)])
VIOLET_GRAD = lin("violet", 0, 40, 0, 68, [(0, VIOLET_B), (1, VIOLET_A)])
DIAG_GRAD = lin("diag", 36, 68, 72, 38,
                [(0, ORANGE_A), (0.35, ORANGE_B), (0.65, VIOLET_A), (1, VIOLET_B)])


def stroke(d, paint, w=GLYPH_W, cap="round", join="round"):
    return (f'<path d="{d}" fill="none" stroke="{paint}" stroke-width="{f(w)}" '
            f'stroke-linecap="{cap}" stroke-linejoin="{join}"/>')


def fill(d, paint, rule=None):
    r = f' fill-rule="{rule}"' if rule else ""
    return f'<path d="{d}" fill="{paint}"{r}/>'


# --- glyphs: list of (kind, d, colour-role) ; role in orange|violet|diag|hex ---
def glyph_ferrux():
    # ">_" terminal prompt
    return [
        ("stroke", "M38.5,42.5 L49.5,53.5 L38.5,64.5", "orange"),
        ("stroke", "M55.5,64.5 L69.5,64.5", "violet"),
    ]


def glyph_ferride():
    # "</>" code glyph
    return [
        ("stroke", "M45,44.5 L36.5,53.5 L45,62.5", "orange"),
        ("stroke", "M63,44.5 L71.5,53.5 L63,62.5", "violet"),
        ("stroke", "M57,42 L51,65", "diag"),
    ]


def glyph_lomio():
    # solid chat bubble with a four-point spark cut out of it (negative space,
    # so the spark survives monochrome / themed-icon tinting as a hole)
    bub = bubble_path(36, 39.5, 72, 63, 9, (42, 39.5, 70.5, 50.5))
    spark = sparkle_path(54, 51.25, 8.6, 8.6, 2.1)
    return [
        ("fill-evenodd", bub + " " + spark, "diag"),
    ]


APPS = {
    "ferrux": glyph_ferrux,
    "ferride": glyph_ferride,
    "lomio": glyph_lomio,
}


def svg(body, defs=""):
    d = f"<defs>{defs}</defs>" if defs else ""
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{d}{body}</svg>\n')


def background():
    radial = (f'<radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="54" cy="50" r="64">'
              f'<stop offset="0" stop-color="{BG_LIFT}"/><stop offset="1" stop-color="{BG}"/>'
              f'</radialGradient>')
    return radial, fill("M0,0 H108 V108 H0 Z", "url(#bg)")


def foreground(glyph, mono=False):
    hexd = hexagon_path(C, C, HEX_R, HEX_CORNER)
    parts = []
    if not mono:
        parts.append(fill(hexd, HEX_FILL))
        parts.append(stroke(hexd, "url(#hex)", HEX_STROKE))
    else:
        parts.append(stroke(hexd, "#FFFFFF", HEX_STROKE))
    for kind, d, role in glyph:
        paint = "#FFFFFF" if mono else f"url(#{role})"
        parts.append(stroke(d, paint) if kind == "stroke" else
                     fill(d, paint, "evenodd" if kind == "fill-evenodd" else None))
    defs = "" if mono else HEX_GRAD + ORANGE_GRAD + VIOLET_GRAD + DIAG_GRAD
    return defs, "".join(parts)


def svg_store(body, defs):
    d = f"<defs>{defs}</defs>" if defs else ""
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" '
            f'viewBox="18 18 72 72">{d}{body}</svg>\n')


def main():
    app, od = sys.argv[1], sys.argv[2]
    os.makedirs(od, exist_ok=True)
    g = APPS[app]()
    bdefs, bbody = background()
    fdefs, fbody = foreground(g)
    mdefs, mbody = foreground(g, mono=True)
    files = {
        "ic_launcher_background.svg": svg(bbody, bdefs),
        "ic_launcher_foreground.svg": svg(fbody, fdefs),
        "ic_launcher_monochrome.svg": svg(mbody, mdefs),
        "icon.svg": svg_store(bbody + fbody, bdefs + fdefs),
    }
    for name, content in files.items():
        with open(os.path.join(od, name), "w") as fh:
            pretty = minidom.parseString(content.encode()).toprettyxml(indent="  ", encoding="UTF-8")
            fh.write("\n".join(l for l in pretty.decode().splitlines() if l.strip()) + "\n")


if __name__ == "__main__":
    main()
