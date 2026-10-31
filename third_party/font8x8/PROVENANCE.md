# CP437 font source

The adjacent headers and README are unmodified copies from
https://github.com/dhepper/font8x8 at commit
`8e279d2d864e79128e96188a6b9526cfa3fbfef9`.
Daniel Hepper identifies these fonts as public domain, derived from Marcel
Sondaar and IBM public domain VGA fonts. Original notices are retained.

`scripts/generate-cp437-font.py` maps Unicode glyphs to CP437, adds explicitly
drawn DOS control symbols and missing mathematical symbols, and generates the
Kotlin table. These supplementary drawings are contributed under this project's
license. Each 8x8 row is doubled vertically to preserve the viewer's 8x16 cells.
Block and shade geometry remains handled by the renderer. This is a bitmap font,
not an exact reproduction of every historical VGA ROM or SAUCE font selection.
