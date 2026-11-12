package com.infinifolder.preview

import java.io.InterruptedIOException

/** Optional pacing hint; existing BitmapAnimation implementations keep their current contract. */
interface FramePacedBitmapAnimation : BitmapAnimation {
    val frameIntervalMillis: Long
}

/** A raster snapshot plus sparse indexed paint coverage. No SVG, CSS or bitmaps retained. */
internal class RasterPaletteAnimation(
    override val width: Int,
    override val height: Int,
    private val initial: IntArray,
    private val palette: IntArray,
    private val cycles: List<SvgPaletteCycling.Cycle>,
    private val used: IntArray,
    private val positions: IntArray,
    private val slots: IntArray,
    private val coverage: ByteArray
) : FramePacedBitmapAnimation {
    override val description = "Rasterized SVG palette cycling (${cycles.size} range(s))"
    override val frameIntervalMillis = (500.0 / cycles.maxOf { it.rate }).toLong().coerceIn(16, 33)
    private val cycleForSlot = IntArray(used.size) { slot -> cycles.indexOfFirst { used[slot] in it.first..it.last } }
    private val colors = IntArray(used.size)
    private val previous = IntArray(used.size)
    private val red = IntArray(used.size)
    private val green = IntArray(used.size)
    private val blue = IntArray(used.size)

    override fun render(elapsedNanos: Long, output: IntArray, force: Boolean): Boolean {
        require(output.size >= initial.size)
        for (slot in used.indices) {
            val cycle = cycles[cycleForSlot[slot]]
            val length = cycle.last - cycle.first + 1
            val phase = cycle.phase(elapsedNanos)
            colors[slot] = palette[cycle.first + Math.floorMod(used[slot] - cycle.first - (if (cycle.reverse) -phase else phase), length)]
            val anchor = palette[used[slot]]
            red[slot] = ((colors[slot] ushr 16 and 255) - (anchor ushr 16 and 255) + 255) shl 8
            green[slot] = ((colors[slot] ushr 8 and 255) - (anchor ushr 8 and 255) + 255) shl 8
            blue[slot] = ((colors[slot] and 255) - (anchor and 255) + 255) shl 8
        }
        if (!force && colors.contentEquals(previous)) return false
        initial.copyInto(output)
        for (i in positions.indices) {
            if (i and 16383 == 0 && Thread.currentThread().isInterrupted) throw InterruptedIOException("SVG cycling cancelled")
            val encoded = positions[i]
            val at = encoded and Int.MAX_VALUE
            val slot = slots[i]
            if (encoded < 0) {
                output[at] = (initial[at] and 0xff000000.toInt()) or (colors[slot] and 0xffffff)
                continue
            }
            val weight = coverage[i].toInt() and 255
            val original = initial[at]
            // Integer coverage is compiled once; the frame loop has no division or allocation.
            val r = ((original ushr 16 and 255) + channelDelta[red[slot] + weight]).coerceIn(0, 255)
            val g = ((original ushr 8 and 255) + channelDelta[green[slot] + weight]).coerceIn(0, 255)
            val b = ((original and 255) + channelDelta[blue[slot] + weight]).coerceIn(0, 255)
            output[at] = (original and 0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        colors.copyInto(previous)
        return true
    }
    companion object {
        // Shared by raster animations: 511 signed channel deltas × 256 coverage values (512 KiB).
        private val channelDelta = IntArray(511 * 256) { packed ->
            val delta = ((packed ushr 8) - 255) * (packed and 255)
            if (delta >= 0) (delta + 127) / 255 else -((-delta + 127) / 255)
        }
    }
}

/** Small balanced RGB lookup, built once. IDs distinguish even equal-RGB palette entries. */
internal class PaletteColorLookup(private val colors: IntArray) {
    private class Node(val index: Int, val axis: Int, val left: Node?, val right: Node?)
    private fun component(index: Int, axis: Int) = colors[index] ushr (16 - axis * 8) and 255
    private fun build(indices: List<Int>, depth: Int): Node? {
        if (indices.isEmpty()) return null
        val axis = depth % 3
        val sorted = indices.sortedWith(compareBy<Int> { component(it, axis) }.thenBy { it })
        val middle = sorted.size / 2
        return Node(sorted[middle], axis, build(sorted.subList(0, middle), depth + 1), build(sorted.subList(middle + 1, sorted.size), depth + 1))
    }
    private val root = build(colors.indices.toList(), 0)
    fun nearest(color: Int): Int {
        var best = 0
        var distance = Int.MAX_VALUE
        fun visit(node: Node?) {
            if (node == null) return
            val candidate = colorDistance(color, colors[node.index])
            if (candidate < distance || candidate == distance && node.index < best) { best = node.index; distance = candidate }
            val delta = (color ushr (16 - node.axis * 8) and 255) - component(node.index, node.axis)
            visit(if (delta <= 0) node.left else node.right)
            if (delta * delta <= distance) visit(if (delta <= 0) node.right else node.left)
        }
        visit(root)
        return best
    }
    companion object {
        fun marker(index: Int) = 0xff000000.toInt() or (((index + 1) * 0x9e3779) and 0xffffff)
        fun colorDistance(a: Int, b: Int): Int {
            val r = (a ushr 16 and 255) - (b ushr 16 and 255)
            val g = (a ushr 8 and 255) - (b ushr 8 and 255)
            val blue = (a and 255) - (b and 255)
            return r * r + g * g + blue * blue
        }
    }
}
