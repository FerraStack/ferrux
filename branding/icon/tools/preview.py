#!/usr/bin/env python3
"""Builds the launcher-icon preview sheet (masks, themed icons, 48 px) as SVG.

usage: preview.py <app> <out.svg>   (render with: rsvg-convert out.svg -o preview.png)
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen  # noqa: E402

APP = sys.argv[1]
OUT = sys.argv[2]
NAMES = {"ferrux": "Ferrux", "ferride": "Ferride", "lomio": "Lomio"}
FONT = "font-family=\"Adwaita Sans, DejaVu Sans, sans-serif\""

THEMED = {
    "light": dict(wall=("#F4E9D8", "#D6E2F3"), bg="#DCE4F8", fg="#27406F", ink="#2A2A33"),
    "dark": dict(wall=("#1A1E2A", "#2B1F3A"), bg="#2E3038", fg="#C9D7F6", ink="#E8E6EE"),
}


def layers(app):
    g = gen.APPS[app]()
    bdefs, bbody = gen.background()
    fdefs, fbody = gen.foreground(g)
    _, mbody = gen.foreground(g, mono=True)
    return bdefs + fdefs, bbody + fbody, mbody


def squircle(cx, cy, r, n=4.0, steps=160):
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        c, s = math.cos(t), math.sin(t)
        x = cx + r * math.copysign(abs(c) ** (2 / n), c)
        y = cy + r * math.copysign(abs(s) ** (2 / n), s)
        pts.append(f"{x:.2f},{y:.2f}")
    return "M" + " L".join(pts) + " Z"


clip_id = [0]


def tile(x, y, size, shape, body, crop=True, extra=""):
    """Nested <svg> showing the adaptive icon (visible 72 dp crop) clipped to `shape`."""
    clip_id[0] += 1
    cid = f"c{clip_id[0]}"
    r = size / 2
    cx, cy = x + r, y + r
    if shape == "circle":
        shp = f'<circle cx="{cx}" cy="{cy}" r="{r}"/>'
    elif shape == "squircle":
        shp = f'<path d="{squircle(cx, cy, r)}"/>'
    elif shape == "rounded":
        shp = f'<rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{size * 0.22:.2f}"/>'
    else:
        shp = f'<rect x="{x}" y="{y}" width="{size}" height="{size}"/>'
    vb = "18 18 72 72" if crop else "0 0 108 108"
    return (f'<clipPath id="{cid}">{shp}</clipPath>'
            f'<g clip-path="url(#{cid})"><svg x="{x}" y="{y}" width="{size}" height="{size}" '
            f'viewBox="{vb}">{body}{extra}</svg></g>')


def themed_body(mbody, mode):
    t = THEMED[mode]
    return f'<rect width="108" height="108" fill="{t["bg"]}"/>' + mbody.replace("#FFFFFF", t["fg"])


def text(x, y, s, size=15, fill="#2A2A33", weight="normal", anchor="middle"):
    return (f'<text x="{x}" y="{y}" {FONT} font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}" text-anchor="{anchor}">{s}</text>')


def main():
    defs, body, mbody = layers(APP)
    W, H = 1280, 900
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
         f"<defs>{defs}"
         f'<linearGradient id="wl" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{THEMED["light"]["wall"][0]}"/><stop offset="1" stop-color="{THEMED["light"]["wall"][1]}"/></linearGradient>'
         f'<linearGradient id="wd" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{THEMED["dark"]["wall"][0]}"/><stop offset="1" stop-color="{THEMED["dark"]["wall"][1]}"/></linearGradient>'
         "</defs>",
         f'<rect width="{W}" height="{H}" fill="#F3F2F6"/>',
         text(40, 56, f"{NAMES[APP]} · launcher icon", 30, "#17141F", "bold", "start"),
         text(40, 84, "FerraStack icon family · adaptive icon 108 dp · background + foreground + monochrome",
              15, "#6A6676", anchor="start")]

    # Row 1: masks
    y0, s, gap, x0 = 120, 170, 36, 40
    labels = [("square", "Full bleed (72 dp visible)", True), ("circle", "Circle", True),
              ("squircle", "Squircle", True), ("rounded", "Rounded square", True)]
    for i, (shape, lab, crop) in enumerate(labels):
        x = x0 + i * (s + gap)
        o.append(tile(x, y0, s, shape, body, crop))
        o.append(text(x + s / 2, y0 + s + 26, lab))
    # safe-zone diagnostic: full 108 canvas, 72 dp visible square, 66 dp safe circle
    x = x0 + 4 * (s + gap)
    guides = ('<rect x="18" y="18" width="72" height="72" fill="none" stroke="#FFFFFF" stroke-opacity="0.55" '
              'stroke-width="0.6" stroke-dasharray="2 1.5"/>'
              '<circle cx="54" cy="54" r="33" fill="none" stroke="#3FE0A0" stroke-width="0.7"/>')
    o.append(tile(x, y0, s, "square", body, crop=False, extra=guides))
    o.append(text(x + s / 2, y0 + s + 26, "Safe zone (66 dp, green)"))
    x = x0 + 5 * (s + gap)
    o.append(f'<rect x="{x}" y="{y0}" width="{s}" height="{s}" fill="#FFFFFF"/>')
    o.append(f'<svg x="{x}" y="{y0}" width="{s}" height="{s}" viewBox="0 0 108 108">'
             + mbody.replace("#FFFFFF", "#17141F") + guides.replace("#FFFFFF", "#888888") + "</svg>")
    o.append(text(x + s / 2, y0 + s + 26, "Monochrome layer"))

    # Row 2: themed icons on wallpapers (light + dark), regular icon beside for reference
    y1, pw, ph = 360, 580, 250
    for j, mode in enumerate(("light", "dark")):
        px = 40 + j * (pw + 40)
        t = THEMED[mode]
        o.append(f'<rect x="{px}" y="{y1}" width="{pw}" height="{ph}" rx="22" fill="url(#w{mode[0]})"/>')
        o.append(text(px + 24, y1 + 36, f"Themed icon · {mode} wallpaper (Android 13+)", 15, t["ink"], "bold", "start"))
        isz = 104
        cells = [("Themed", themed_body(mbody, mode), "circle"),
                 ("Themed", themed_body(mbody, mode), "squircle"),
                 ("Default", body, "circle"),
                 ("Default", body, "squircle")]
        for k, (lab, b, shape) in enumerate(cells):
            cx = px + 32 + k * (isz + 34)
            o.append(tile(cx, y1 + 66, isz, shape, b))
            o.append(text(cx + isz / 2, y1 + 66 + isz + 26, NAMES[APP], 14, t["ink"]))
            o.append(text(cx + isz / 2, y1 + 66 + isz + 46, lab, 12, t["ink"]))

    # Row 3: 48 px actual size + family
    y2 = 660
    o.append(text(40, y2, "Actual size · 48 px", 18, "#17141F", "bold", "start"))
    o.append(text(660, y2, "Family · 48 px (Ferrux · Ferride · Lomio)", 18, "#17141F", "bold", "start"))
    for j, mode in enumerate(("light", "dark")):
        py = y2 + 20 + j * 104
        t = THEMED[mode]
        o.append(f'<rect x="40" y="{py}" width="580" height="92" rx="16" fill="url(#w{mode[0]})"/>')
        o.append(f'<rect x="660" y="{py}" width="580" height="92" rx="16" fill="url(#w{mode[0]})"/>')
        cells = [(body, "circle"), (body, "squircle"), (body, "rounded"),
                 (themed_body(mbody, mode), "circle"), (themed_body(mbody, mode), "squircle")]
        for k, (b, shape) in enumerate(cells):
            o.append(tile(64 + k * 76, py + 22, 48, shape, b))
        o.append(text(470, py + 52, "default ×3 · themed ×2", 13, t["ink"], anchor="start"))
        for k, app in enumerate(("ferrux", "ferride", "lomio")):
            _, b2, m2 = layers(app)
            o.append(tile(684 + k * 76, py + 22, 48, "circle", b2))
            o.append(tile(684 + (k + 3) * 76 + 24, py + 22, 48, "circle", themed_body(m2, mode)))
    o.append("</svg>")
    with open(OUT, "w") as fh:
        fh.write("".join(o))


if __name__ == "__main__":
    main()
