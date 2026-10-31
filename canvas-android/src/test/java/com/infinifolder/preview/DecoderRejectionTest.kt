package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecoderRejectionTest {
    private fun fixture(ext: String) = javaClass.getResourceAsStream("/images/icon.$ext")!!.use { it.readBytes() }
    private fun index(bytes: ByteArray, token: ByteArray): Int = (0..bytes.size - token.size).first { start -> token.indices.all { bytes[start + it] == token[it] } }
    private fun index(bytes: ByteArray, token: String) = index(bytes, token.toByteArray())
    private fun rejected(decode: () -> Any?) {
        ImageDecodeUtils.lastDecodedPixelsForTest = null
        try { assertNull(decode()) } catch (_: IllegalArgumentException) {} catch (_: IndexOutOfBoundsException) {}
        assertNull("Incomplete/unsupported input published a frame", ImageDecodeUtils.lastDecodedPixelsForTest)
    }
    @Test fun exrRejectsUnimplementedRle() {
        val bytes = fixture("exr")
        val start = index(bytes, "compression\u0000compression\u0000") + 28
        bytes[start] = 1
        rejected { HdrAndScientificImageDecoder.decodeOpenExr(bytes, 0) }
    }
    @Test fun exrRejectsMissingChunks() {
        val bytes = fixture("exr")
        var pos = 8
        while (bytes[pos] != 0.toByte()) {
            while (bytes[pos++] != 0.toByte()) {}
            while (bytes[pos++] != 0.toByte()) {}
            val size = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(pos)
            pos += 4 + size
        }
        bytes.fill(0, pos + 1, pos + 1 + 192 * 8)
        rejected { HdrAndScientificImageDecoder.decodeOpenExr(bytes, 0) }
    }
    @Test fun xcfRejectsUnsupportedCompression() {
        val bytes = fixture("xcf")
        val pos = index(bytes, byteArrayOf(0,0,0,17,0,0,0,1)) + 8
        bytes[pos] = 2
        rejected { LayeredImageDecoder.decodeXcf(bytes, 0) }
    }
    @Test fun psdRejectsMissingComposite() {
        val bytes = fixture("psd")
        var pos = 26
        repeat(3) { pos += 4 + ByteBuffer.wrap(bytes).getInt(pos) }
        rejected { LayeredImageDecoder.decodePsdOrPsb(bytes.copyOf(pos) + byteArrayOf(0,0), 0) }
    }
    @Test fun hdrRejectsMissingScanlines() {
        val bytes = fixture("hdr")
        val text = bytes.toString(Charsets.ISO_8859_1)
        val end = Regex("-Y \\d+ \\+X \\d+\\n").find(text)!!.range.last + 1
        rejected { HdrAndScientificImageDecoder.decodeRadianceHdr(bytes.copyOf(end), 0) }
    }
    @Test fun fitsRejectsIncompleteArray() = rejected { HdrAndScientificImageDecoder.decodeFits(fixture("fits").copyOf(2881), 0) }
    @Test fun ddsRejectsMissingRaster() = rejected { HdrAndScientificImageDecoder.decodeDds(fixture("dds").copyOf(129), 0) }
    @Test fun pcxRejectsMissingScanlines() = rejected { PcxDecoder.decode(fixture("pcx").copyOf(129), 0) }
    @Test fun qoiRejectsMissingPixels() = rejected {
        SimpleLosslessImageDecoder.decodeQoi(fixture("qoi").copyOf(14) + byteArrayOf(0,0,0,0,0,0,0,1), 0)
    }
    @Test fun ppmRejectsMissingRaster() = rejected { NetpbmDecoder.decode("P6\n2 2\n255\n".toByteArray(), 0) }
    @Test fun asciiNetpbmRejectsPartialSamples() {
        for (text in listOf("P1\n2 2\n1", "P2\n2 2\n255\n1", "P3\n2 2\n255\n1 2"))
            rejected { NetpbmDecoder.decode(text.toByteArray(), 0) }
    }
    @Test fun epsiRejectsEmptyDeclaredPreview() = rejected {
        MetafileAndVectorImageDecoder.decodePostScript("%!PS-Adobe-3.0 EPSF-3.0\n%%BeginPreview: 8 1 1 1\n%%EndPreview\n".toByteArray(), 0)
    }
    @Test fun tiffRejectsUnknownCompression() {
        val bytes = fixture("tiff")
        val buffer = ByteBuffer.wrap(bytes).order(if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val ifd = buffer.getInt(4)
        val count = buffer.getShort(ifd).toInt() and 65535
        val entry = (0 until count).map { ifd + 2 + it * 12 }.first { buffer.getShort(it).toInt() == 259 }
        buffer.putShort(entry + 8, 0xffff.toShort())
        rejected { TiffAndRawImageDecoder.decodeTiff(bytes, 0) }
    }
    @Test fun packBitsDoesNotInventMissingBytes() {
        for (data in listOf(byteArrayOf(), byteArrayOf(2, 1), byteArrayOf((-2).toByte()))) {
            try { ImageDecodeUtils.decodePackBits(data, 0, 3); fail("Incomplete PackBits accepted") }
            catch (_: IllegalArgumentException) {}
        }
        assertArrayEquals(byteArrayOf(7,7,7), ImageDecodeUtils.decodePackBits(byteArrayOf((-2).toByte(),7), 0,3).first)
    }
    @Test fun cp437UsesKnownLetterAndBoxGlyphs() {
        val a = intArrayOf(0x0c,0x1e,0x33,0x33,0x3f,0x33,0x33,0)
        for (y in 0 until 16) for (x in 0 until 8)
            assertEquals("A $x,$y", a[y / 2] and (1 shl x) != 0, Cp437Font.isForeground(65,x,y))
        for (code in 0..255) {
            val points = (0 until 16).sumOf { y -> (0 until 8).count { x -> Cp437Font.isForeground(code,x,y) } }
            assertEquals("Visible glyph $code", code !in setOf(0,32,255), points > 0)
        }
        for (x in 0 until 8) assertTrue(Cp437Font.isForeground(0xc4,x,8)) // Horizontal line.
    }
}
