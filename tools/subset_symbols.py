#!/usr/bin/env python3
"""Rebuild the Material Symbols icon subsets in app/src/main/assets/fonts from util/Sym.kt.

The app ships two small static fonts (outlined + filled) holding only the glyphs Sym.kt names.
To add an icon: add its codepoint to Sym.kt, then run this script against the upstream variable
font and commit the two regenerated .ttf files.

    pip install fonttools
    curl -L -o /tmp/msr.ttf \
      'https://github.com/google/material-design-icons/raw/master/variablefont/MaterialSymbolsRounded%5BFILL,GRAD,opsz,wght%5D.ttf'
    python3 tools/subset_symbols.py /tmp/msr.ttf

Axes match the original subsets: wght 400, GRAD 0, opsz 24, FILL 0 (outlined) / 1 (filled).
"""
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
SYM = ROOT / "app/src/main/java/io/github/kuscher/studiosnap/util/Sym.kt"
FONTS = ROOT / "app/src/main/assets/fonts"
AXES = {"wght": 400, "GRAD": 0, "opsz": 24}
OUTPUTS = {0: "MaterialSymbolsRounded.ttf", 1: "MaterialSymbolsRounded_Fill.ttf"}


def codepoints() -> list[int]:
    cps = sorted({int(h, 16) for h in re.findall(r'"\\u([0-9a-fA-F]{4})"', SYM.read_text())})
    if not cps:
        sys.exit(f"no codepoints found in {SYM}")
    return cps


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    variable = sys.argv[1]
    cps = codepoints()
    for fill, name in OUTPUTS.items():
        font = instancer.instantiateVariableFont(TTFont(variable), {**AXES, "FILL": fill})
        opts = subset.Options()
        opts.layout_features = []  # the originals carry no substitutions; keep glyph choice identical
        opts.name_IDs = ["*"]
        opts.notdef_outline = True
        opts.hinting = False
        sub = subset.Subsetter(opts)
        sub.populate(unicodes=cps)
        sub.subset(font)
        missing = [hex(c) for c in cps if c not in font.getBestCmap()]
        if missing:
            sys.exit(f"{name}: codepoints missing from the source font: {missing}")
        font.save(FONTS / name)
        print(f"{name}: {len(cps)} glyphs")


if __name__ == "__main__":
    main()
