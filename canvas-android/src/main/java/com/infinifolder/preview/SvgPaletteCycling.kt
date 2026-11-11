package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.roundToInt

/** Infini SVG metadata stays vector paint: no quantization or 256-color conversion. */
object SvgPaletteCycling {
    const val NAMESPACE = "https://infinicanvas.org/svg"
    private const val MAX_XML = 8 * 1024 * 1024
    data class Cycle(val first: Int, val last: Int, val rate: Double, val reverse: Boolean)
    data class PaintSlot(val index: Int, val property: String, val selector: String)
    class Definition(val svg: String, val palette: IntArray, val cycles: List<Cycle>, val slots: List<PaintSlot>) {
        fun colorAt(index: Int, elapsedNanos: Long): Int {
            val cycle = cycles.firstOrNull { index in it.first..it.last } ?: return palette[index]
            val length = cycle.last - cycle.first + 1
            // Reduce time before conversion to avoid overflow after long-running sessions.
            val period = length / cycle.rate
            val steps = ((elapsedNanos.coerceAtLeast(0) / 1_000_000_000.0 % period) * cycle.rate).toInt()
            val direction = if (cycle.reverse) -1 else 1
            return palette[cycle.first + Math.floorMod(index - cycle.first - direction * steps, length)]
        }
        fun css(elapsedNanos: Long): String = slots.joinToString("\n") {
            "${it.selector}{${it.property}:#${String.format(Locale.ROOT, "%06x", colorAt(it.index, elapsedNanos) and 0xffffff)} !important;}"
        }
    }

    /** Null denotes ordinary SVG. Malformed extension metadata is explicitly rejected. */
    fun parse(bytes: ByteArray): Definition? {
        require(bytes.size <= MAX_XML) { "SVG exceeds encoded size limit" }
        val expanded = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= MAX_XML) { "Expanded SVG exceeds size limit" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } else bytes
        val text = expanded.toString(Charsets.UTF_8)
        if (!text.contains(NAMESPACE)) return null
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "SVG external entities are unsupported" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External SVG entities are unsupported") }
        val document = builder.parse(ByteArrayInputStream(expanded))
        val palettes = document.getElementsByTagNameNS(NAMESPACE, "palette")
        if (palettes.length == 0) return null
        require(palettes.length == 1) { "SVG cycling requires one indexed palette" }
        val paletteNode = palettes.item(0) as Element
        val colors = paletteNode.getElementsByTagNameNS(NAMESPACE, "color")
        require(colors.length in 1..1_000_000) { "Invalid SVG palette size" }
        val indices = (0 until colors.length).map { (colors.item(it) as Element).getAttribute("index").toInt() }
        val size = paletteNode.getAttribute("size").takeIf { it.isNotBlank() }?.toInt() ?: (indices.max() + 1)
        require(size in 1..1_000_000 && size == colors.length) { "SVG palette must define every index" }
        val palette = IntArray(size); val defined = BooleanArray(size)
        for (i in 0 until colors.length) {
            val node = colors.item(i) as Element; val index = indices[i]
            require(index in 0 until size && !defined[index]) { "Invalid or duplicate SVG palette index" }
            val color = node.getAttribute("value")
            require(Regex("#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?").matches(color)) { "Invalid SVG palette color" }
            val rgb = color.drop(1).let { if (it.length == 3) it.flatMap { c -> listOf(c, c) }.joinToString("") else it }
            palette[index] = 0xff000000.toInt() or rgb.toInt(16); defined[index] = true
        }
        val cycles = mutableListOf<Cycle>(); val nodes = document.getElementsByTagNameNS(NAMESPACE, "cycle")
        require(nodes.length <= 256) { "Too many SVG cycle ranges" }
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            fun optional(name: String, default: String) = node.getAttribute(name).ifBlank { default }
            require(optional("palette", paletteNode.getAttribute("id")) == paletteNode.getAttribute("id")) { "Unknown SVG palette" }
            require(optional("unit", "steps-per-second") == "steps-per-second" && optional("repeat", "indefinite") == "indefinite") { "Unsupported SVG cycle timing" }
            val first = node.getAttribute("start").toInt(); val last = node.getAttribute("end").toInt()
            val rate = node.getAttribute("rate").toDouble(); val direction = optional("direction", "forward")
            require(first in 0 until size && last in first until size && rate.isFinite() && rate > 0) { "Invalid SVG cycle range/rate" }
            require(direction in setOf("forward", "reverse")) { "Unsupported SVG cycle direction" }
            require(cycles.none { first <= it.last && it.first <= last }) { "Overlapping SVG cycle ranges" }
            cycles += Cycle(first, last, rate, direction == "reverse")
        }
        val slots = mutableListOf<PaintSlot>(); val all = document.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val element = all.item(i) as Element
            if (!element.hasAttributeNS(NAMESPACE, "palette-index")) continue
            val index = element.getAttributeNS(NAMESPACE, "palette-index").toInt()
            require(index in 0 until size) { "SVG paint references an unavailable palette index" }
            val properties = if (element.localName == "stop") listOf("stop-color") else buildList {
                // Explicit paint declarations determine the channel; an unstyled shape uses SVG's default fill.
                val style = element.getAttribute("style").split(';').mapNotNull {
                    val parts = it.split(':', limit = 2); if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }.toMap()
                val fill = style["fill"] ?: element.getAttribute("fill").ifBlank { "black" }
                val stroke = style["stroke"] ?: element.getAttribute("stroke").ifBlank { "none" }
                if (fill != "none") add("fill")
                if (stroke != "none") add("stroke")
            }
            for (property in properties) {
                val clazz = "infiniPalette${property.replace('-', '_')}$index"
                element.setAttribute("class", (element.getAttribute("class") + " " + clazz).trim())
                val slot = PaintSlot(index, property, ".$clazz")
                if (slot !in slots) slots += slot
            }
            element.removeAttributeNS(NAMESPACE, "palette-index")
        }
        // AndroidSVG ignores foreign metadata, but remove it before parsing the renderable tree.
        for (name in listOf("palette", "cycle")) {
            val foreign = document.getElementsByTagNameNS(NAMESPACE, name)
            // Android's DOM can return a snapshot here; desktop DOM commonly returns a live list.
            val remove = (0 until foreign.length).map { foreign.item(it) }
            remove.forEach { it.parentNode?.removeChild(it) }
        }
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(out))
        return Definition(out.toString("UTF-8"), palette, cycles, slots)
    }

    fun decode(bytes: ByteArray, targetPixels: Int, nativeDimensions: Boolean = false, allowStatic: Boolean = false): BitmapFrame? {
        require(targetPixels > 0)
        val definition = parse(bytes)
        if (definition == null && !allowStatic) return null
        val svg = if (definition != null) SVG.getFromString(definition.svg) else {
            val input = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()) else bytes.inputStream()
            input.use { SVG.getFromInputStream(it) }
        }
        val box = svg.documentViewBox
        val naturalW = (if (nativeDimensions) svg.documentWidth.takeIf { it > 0 } else null) ?: box?.width()?.takeIf { it > 0 } ?: svg.documentWidth.takeIf { it > 0 } ?: targetPixels.toFloat()
        val naturalH = (if (nativeDimensions) svg.documentHeight.takeIf { it > 0 } else null) ?: box?.height()?.takeIf { it > 0 } ?: svg.documentHeight.takeIf { it > 0 } ?: targetPixels.toFloat()
        val scale = (targetPixels / maxOf(naturalW, naturalH)).let { if (nativeDimensions) minOf(1f, it) else it }
        val width = (naturalW * scale).roundToInt().coerceAtLeast(1); val height = (naturalH * scale).roundToInt().coerceAtLeast(1)
        require(width.toLong() * height <= 4 * 1024 * 1024) { "SVG exceeds animation pixel limit" }
        if (box == null) svg.setDocumentViewBox(0f, 0f, naturalW, naturalH)
        svg.setDocumentWidth("100%"); svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (definition == null) {
            svg.renderToCanvas(Canvas(bitmap), RenderOptions.create().preserveAspectRatio(PreserveAspectRatio.LETTERBOX))
            return BitmapFrame(bitmap)
        }
        val animation = VectorAnimation(definition, svg, width, height)
        val pixels = IntArray(width * height); animation.render(0, pixels, true)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return BitmapFrame(bitmap).also { if (definition.cycles.isNotEmpty() && definition.slots.isNotEmpty()) it.animation = animation }
    }

    private class VectorAnimation(val definition: Definition, val svg: SVG, override val width: Int, override val height: Int) : BitmapAnimation {
        override val description = "SVG palette cycling (${definition.cycles.size} range(s))"
        private val buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(buffer)
        private var previous: String? = null
        override fun render(elapsedNanos: Long, output: IntArray, force: Boolean): Boolean {
            val css = definition.css(elapsedNanos)
            if (!force && css == previous) return false
            buffer.eraseColor(0)
            svg.renderToCanvas(canvas, RenderOptions.create().preserveAspectRatio(PreserveAspectRatio.LETTERBOX).css(css))
            buffer.getPixels(output, 0, width, 0, 0, width, height); previous = css
            return true
        }
    }
}
