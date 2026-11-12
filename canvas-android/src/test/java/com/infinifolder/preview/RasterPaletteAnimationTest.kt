package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Test

class RasterPaletteAnimationTest {
    private val palette = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
    @Test fun everyPhaseIsVisitedAndWrapsInBothDirections() {
        for (reverse in listOf(false, true)) {
            val animation = RasterPaletteAnimation(2, 1, intArrayOf(palette[0], palette[0]), palette,
                listOf(SvgPaletteCycling.Cycle(0, 2, 24.0, reverse)), intArrayOf(0), intArrayOf(0), intArrayOf(0), byteArrayOf(-1))
            val output = IntArray(2)
            for (step in 0..192) {
                val elapsed = kotlin.math.ceil(step * 1e9 / 24.0).toLong()
                animation.render(elapsed, output, true)
                assertEquals(palette[Math.floorMod(if (reverse) step else -step, 3)], output[0])
                assertEquals("Untagged equal RGB stays fixed", palette[0], output[1])
            }
        }
    }
    @Test fun duplicatePaletteColorsRetainTheirIndices() {
        val colors = intArrayOf(palette[0], palette[1], palette[0], palette[2])
        val animation = RasterPaletteAnimation(2, 1, intArrayOf(colors[0], colors[2]), colors,
            listOf(SvgPaletteCycling.Cycle(0, 3, 1.0, false)), intArrayOf(0, 2), intArrayOf(0, 1), intArrayOf(0, 1), byteArrayOf(-1, -1))
        val output = IntArray(2); animation.render(1_000_000_000, output, true)
        assertArrayEquals(intArrayOf(palette[2], palette[1]), output)
    }
    @Test fun partialCoveragePreservesStaticContributionAndAlphaWithoutDrifting() {
        val initial = intArrayOf(0x807f007f.toInt())
        val animation = RasterPaletteAnimation(1, 1, initial, palette,
            listOf(SvgPaletteCycling.Cycle(0, 2, 1.0, false)), intArrayOf(0), intArrayOf(0), intArrayOf(0), byteArrayOf(127))
        val output = IntArray(1)
        repeat(50) { animation.render(1_000_000_000, output, true); assertEquals(0x800000fe.toInt(), output[0]) }
        animation.render(0, output, true); assertArrayEquals(initial, output)
    }
    @Test fun unchangedFramesAreSkippedButForcedFramesCopyTheSnapshot() {
        val animation = RasterPaletteAnimation(1, 1, intArrayOf(palette[0]), palette,
            listOf(SvgPaletteCycling.Cycle(0, 2, 1.0, false)), intArrayOf(0), intArrayOf(0), intArrayOf(0), byteArrayOf(-1))
        val output = IntArray(1)
        assertTrue(animation.render(0, output, false)); assertFalse(animation.render(1, output, false))
        assertTrue(animation.render(1, output, true))
        assertEquals(33, animation.frameIntervalMillis)
    }
    @Test fun independentCyclesAndLargePalettesKeepAllColors() {
        val colors = IntArray(600) { 0xff000000.toInt() or it }
        val animation = RasterPaletteAnimation(2, 1, intArrayOf(colors[0], colors[300]), colors,
            listOf(SvgPaletteCycling.Cycle(0, 299, 24.0, false), SvgPaletteCycling.Cycle(300, 599, 12.0, true)),
            intArrayOf(0, 300), intArrayOf(0, 1), intArrayOf(0, 1), byteArrayOf(-1, -1))
        val output = IntArray(2)
        for (step in 0 until 300) {
            animation.render(kotlin.math.ceil(step * 1e9 / 12.0).toLong(), output, true)
            assertEquals(colors[Math.floorMod(-step * 2, 300)], output[0]); assertEquals(colors[300 + step], output[1])
        }
        assertEquals(20, animation.frameIntervalMillis)
    }
    @Test fun lookupMatchesExhaustiveSearchAndMarkerIdsStayDistinct() {
        val random = java.util.Random(732)
        val colors = IntArray(500) { random.nextInt() or 0xff000000.toInt() }
        val lookup = PaletteColorLookup(colors)
        repeat(1000) {
            val color = random.nextInt()
            val expected = colors.indices.minBy { PaletteColorLookup.colorDistance(color, colors[it]) }
            assertEquals(expected, lookup.nearest(color))
        }
        assertEquals(100_000, (0 until 100_000).map { PaletteColorLookup.marker(it) }.toSet().size)
    }
    @Test fun directIndexPixelsRetainAlphaAndRespectCancellation() {
        val animation = RasterPaletteAnimation(1, 1, intArrayOf(0x80ff0000.toInt()), palette,
            listOf(SvgPaletteCycling.Cycle(0, 2, 1.0, false)), intArrayOf(0), intArrayOf(Int.MIN_VALUE), intArrayOf(0), byteArrayOf(-1))
        val output = IntArray(1); animation.render(1_000_000_000, output, true)
        assertEquals(0x800000ff.toInt(), output[0])
        try {
            Thread.currentThread().interrupt()
            assertTrue(runCatching { animation.render(2_000_000_000, output, true) }.exceptionOrNull() is java.io.InterruptedIOException)
        } finally { Thread.interrupted() }
        assertTrue(animation.render(2_000_000_000, output, true))
    }
    @Test fun clockBoundariesDoNotLoseTheLastColor() {
        val cycle = SvgPaletteCycling.Cycle(64, 255, 24.00146484375, false)
        for (step in 0..1000) {
            val time = kotlin.math.ceil(step * 1e9 / cycle.rate).toLong()
            assertEquals(step % 192, cycle.phase(time))
        }
        assertEquals(0, cycle.phase(-1))
        assertTrue(cycle.phase(Long.MAX_VALUE) in 0 until 192)
    }
}
