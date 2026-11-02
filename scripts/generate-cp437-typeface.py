#!/usr/bin/env python3
"""Generate the selectable-text font from the same bitmap cells used for icons.
Requires fonttools; source provenance covers public-domain font8x8 and project-licensed supplementary drawings.
"""
from pathlib import Path
import re
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
root = Path(__file__).resolve().parents[1]
source = root / 'canvas-android/src/main/java/com/infinifolder/preview/Cp437Font.kt'
glyphs = re.findall(r'"([0-9a-f]{16})"', source.read_text())
assert len(glyphs) == 256
controls = '\0☺☻♥♦♣♠•◘○◙♂♀♪♫☼►◄↕‼¶§▬↨↑↓→←∟↔▲▼'
names = ['.notdef'] + [f'cp{i:02x}' for i in range(256)]
fb = FontBuilder(1024, isTTF=True)
fb.setupGlyphOrder(names)
chars = {}
shapes = {'.notdef': TTGlyphPen(None).glyph()}
for code, pattern in enumerate(glyphs):
    name = names[code+1]
    character = controls[code] if code < 32 else ('⌂' if code == 127 else bytes([code]).decode('cp437'))
    chars[ord(character)] = name
    pen = TTGlyphPen(None)
    values=bytes.fromhex(pattern)
    for row in range(16):
        for col in range(8):
            filled = (values[row//2] & (1 << col)) != 0
            if code == 0xb0: filled = ((col+row) & 3) == 0
            if code == 0xb1: filled = ((col+row) & 1) == 0
            if code == 0xb2: filled = ((col+row) & 3) != 0
            if filled:
                x, y = col*64, 1024-(row+1)*64
                pen.moveTo((x,y)); pen.lineTo((x+64,y)); pen.lineTo((x+64,y+64)); pen.lineTo((x,y+64)); pen.closePath()
    shapes[name] = pen.glyph()
fb.setupCharacterMap(chars)
fb.setupGlyf(shapes)
# TrueType places each outline using its left bearing, not xMin alone.
# Preserve the bitmap's blank left columns (notably the right-half block).
for glyph in shapes.values():
    glyph.recalcBounds(fb.font['glyf'])
fb.setupHorizontalMetrics({name: (512, getattr(shapes[name], 'xMin', 0)) for name in names})
fb.setupHorizontalHeader(ascent=1024, descent=0, lineGap=0)
fb.setupNameTable({'familyName':'Infini CP437', 'styleName':'Regular', 'uniqueFontIdentifier':'InfiniCP437-2', 'fullName':'Infini CP437 Regular', 'psName':'InfiniCP437-Regular', 'version':'Version 2.0', 'copyright':'font8x8: public domain; supplementary glyphs: project license. See font8x8 provenance.'})
fb.setupOS2(sTypoAscender=1024,sTypoDescender=0,sTypoLineGap=0,usWinAscent=1024,usWinDescent=0)
fb.setupPost(isFixedPitch=1)
# Stable artifact: seconds since 1904-01-01, fixed at 2026-01-01.
fb.font.recalcTimestamp = False
fb.font['head'].created = fb.font['head'].modified = 3850070400
out = root / 'canvas-android/src/main/assets/fonts/cp437.ttf'
out.parent.mkdir(parents=True,exist_ok=True)
fb.save(out)
print(out)
