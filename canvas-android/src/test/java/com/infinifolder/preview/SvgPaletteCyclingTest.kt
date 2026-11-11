package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Test

class SvgPaletteCyclingTest {
    private fun image(cycle: String = "start='0' end='2' rate='2'", paint: String = "i:palette-index='0'") = """
        <svg xmlns='http://www.w3.org/2000/svg' xmlns:i='https://infinicanvas.org/svg' width='3' height='1'>
        <metadata><i:palette id='p' size='3'><i:color index='0' value='#f00'/><i:color index='1' value='#00ff00'/><i:color index='2' value='#00f'/></i:palette><i:cycle palette='p' $cycle/></metadata>
        <rect width='1' height='1' $paint/><path fill='none' stroke='#f00' i:palette-index='1'/></svg>
    """.trimIndent().toByteArray()
    @Test fun cyclesIndexedFillAndStrokeWithoutChangingOtherPaint() {
        val d = SvgPaletteCycling.parse(image())!!
        assertEquals(0xffff0000.toInt(), d.colorAt(0, 0))
        assertEquals(0xff0000ff.toInt(), d.colorAt(0, 500_000_000))
        assertEquals(0xff00ff00.toInt(), d.colorAt(0, 1_000_000_000))
        assertEquals(0xffff0000.toInt(), d.colorAt(0, 1_500_000_000))
        assertEquals(listOf("fill", "stroke"), d.slots.map { it.property })
        assertTrue(d.css(500_000_000).contains("#0000ff"))
        assertFalse(d.svg.contains("palette-index"))
    }
    @Test fun reverseAndLargeClocksStayInPalette() {
        val d = SvgPaletteCycling.parse(image("start='0' end='2' rate='2' direction='reverse'"))!!
        assertEquals(0xff00ff00.toInt(), d.colorAt(0, 500_000_000))
        assertTrue(d.colorAt(0, Long.MAX_VALUE) in d.palette)
    }
    @Test fun ordinarySvgIsNotMarkedAnimated() { assertNull(SvgPaletteCycling.parse("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray())) }
    @Test fun metadataIsPrefixIndependentAndDetectableByContent() { assertNotNull(SvgPaletteCycling.parse(image())); assertEquals("svg", ImageSignatures.extension(image())) }
    @Test fun rejectsInvalidMetadataAndEntities() {
        for (bytes in listOf(image("start='0' end='3' rate='1'"), image("start='0' end='2' rate='NaN'"), image("start='0' end='2' rate='0'"), image("start='0' end='2' rate='1' repeat='2'"), image(paint="i:palette-index='3'"), ("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]>" + String(image())).toByteArray())) {
            assertTrue(runCatching { SvgPaletteCycling.parse(bytes) }.isFailure)
        }
    }
    @Test fun paletteCanExceedEightBitAndStopColorsAreIndexed() {
        val colors = (0..299).joinToString("") { "<i:color index='$it' value='#123456'/>" }
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' xmlns:i='${SvgPaletteCycling.NAMESPACE}'><metadata><i:palette size='300'>$colors</i:palette><i:cycle start='256' end='299' rate='4'/></metadata><defs><linearGradient id='g'><stop i:palette-index='299'/></linearGradient></defs></svg>"
        val d = SvgPaletteCycling.parse(svg.toByteArray())!!
        assertEquals(300, d.palette.size); assertEquals("stop-color", d.slots.single().property)
    }
}
