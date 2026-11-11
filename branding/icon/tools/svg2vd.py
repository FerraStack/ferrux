#!/usr/bin/env python3
"""Minimal SVG -> Android VectorDrawable converter for the FerraStack icon masters.

Supports exactly what the masters use: <path> with fill / stroke (solid colour or
url(#id) to a userSpaceOnUse linear/radial gradient), stroke-width/-linecap/
-linejoin and fill-rule. Anything else is rejected loudly.
usage: svg2vd.py in.svg out.xml [comment]
"""
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"


def argb(c):
    c = c.strip()
    if not (c.startswith("#") and len(c) == 7):
        raise SystemExit(f"unsupported colour {c!r}")
    return "#FF" + c[1:].upper()


def main():
    src, dst = sys.argv[1], sys.argv[2]
    comment = sys.argv[3] if len(sys.argv) > 3 else None
    root = ET.parse(src).getroot()
    vb = [float(v) for v in root.get("viewBox").split()]
    assert vb[:2] == [0, 0], "viewBox must start at 0,0"
    grads = {}
    for g in root.iter():
        tag = g.tag.replace(NS, "")
        if tag in ("linearGradient", "radialGradient"):
            assert g.get("gradientUnits") == "userSpaceOnUse", g.get("id")
            grads[g.get("id")] = (tag, g)

    def paint_xml(attr, value, indent):
        if value.startswith("url(#"):
            tag, g = grads[value[5:-1]]
            pad = " " * indent
            if tag == "linearGradient":
                head = (f'{pad}    <gradient\n{pad}        android:type="linear"\n'
                        f'{pad}        android:startX="{g.get("x1")}"\n{pad}        android:startY="{g.get("y1")}"\n'
                        f'{pad}        android:endX="{g.get("x2")}"\n{pad}        android:endY="{g.get("y2")}">\n')
            else:
                head = (f'{pad}    <gradient\n{pad}        android:type="radial"\n'
                        f'{pad}        android:centerX="{g.get("cx")}"\n{pad}        android:centerY="{g.get("cy")}"\n'
                        f'{pad}        android:gradientRadius="{g.get("r")}">\n')
            items = "".join(
                f'{pad}        <item android:offset="{s.get("offset")}" android:color="{argb(s.get("stop-color"))}" />\n'
                for s in g if s.tag.replace(NS, "") == "stop")
            return ("aapt", f'{pad}<aapt:attr name="android:{attr}">\n{head}{items}'
                            f'{pad}    </gradient>\n{pad}</aapt:attr>\n')
        return ("attr", f'android:{attr}="{argb(value)}"')

    paths = []
    uses_aapt = False
    for el in root.iter():
        tag = el.tag.replace(NS, "")
        if tag in ("svg", "defs", "linearGradient", "radialGradient", "stop"):
            continue
        if tag != "path":
            raise SystemExit(f"unsupported element <{tag}>")
        attrs = [f'android:pathData="{el.get("d")}"']
        children = []
        fill = el.get("fill", "#000000")
        if fill != "none":
            kind, x = paint_xml("fillColor", fill, 8)
            (children if kind == "aapt" else attrs).append(x)
            if el.get("fill-rule") == "evenodd":
                attrs.append('android:fillType="evenOdd"')
        stroke = el.get("stroke")
        if stroke and stroke != "none":
            kind, x = paint_xml("strokeColor", stroke, 8)
            (children if kind == "aapt" else attrs).append(x)
            attrs.append(f'android:strokeWidth="{el.get("stroke-width", "1")}"')
            if el.get("stroke-linecap"):
                attrs.append(f'android:strokeLineCap="{el.get("stroke-linecap")}"')
            if el.get("stroke-linejoin"):
                attrs.append(f'android:strokeLineJoin="{el.get("stroke-linejoin")}"')
        uses_aapt |= bool(children)
        a = "\n        ".join(attrs)
        if children:
            paths.append(f"    <path\n        {a}>\n" + "".join(children) + "    </path>\n")
        else:
            paths.append(f"    <path\n        {a} />\n")

    w, h = vb[2], vb[3]
    ns = ' xmlns:android="http://schemas.android.com/apk/res/android"'
    if uses_aapt:
        ns += '\n    xmlns:aapt="http://schemas.android.com/aapt"'
    out = '<?xml version="1.0" encoding="utf-8"?>\n'
    if comment:
        out += f"<!-- {comment} -->\n"
    out += (f"<vector{ns}\n    android:width=\"{w:g}dp\"\n    android:height=\"{h:g}dp\"\n"
            f"    android:viewportWidth=\"{w:g}\"\n    android:viewportHeight=\"{h:g}\">\n")
    out += "".join(paths) + "</vector>\n"
    with open(dst, "w") as fh:
        fh.write(out)


if __name__ == "__main__":
    main()
