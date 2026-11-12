package com.infinifolder.preview

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream

class ImageDecodersFixtureTest {

    private fun resolveImagesDir(): File {
        return File(javaClass.getResource("/images/icon.tga")!!.toURI()).parentFile
    }

    private fun decodeFixturePixels(fileName: String): ImageDecodeUtils.DecodedPixels? {
        val file = File(resolveImagesDir(), fileName)
        assertTrue("Missing fixture $fileName", file.isFile && file.length() > 0)
        val ext = file.extension.lowercase()
        val bytes = file.readBytes()
        ImageDecodeUtils.lastDecodedPixelsForTest = null

        when (ext) {
            "tga", "icb", "vda", "vst" -> TgaDecoder.decode(bytes.inputStream(), 256)
            "qoi", "ff" -> SimpleLosslessImageDecoder.decode(bytes, 256)
            "pbm", "pgm", "ppm", "pnm", "pam", "pfm" -> NetpbmDecoder.decode(bytes, 256)
            "xbm", "xpm", "xwd" -> X11ImageDecoder.decode(bytes, 256)
            "pcx", "dcx" -> PcxDecoder.decode(bytes, 256)
            "ora", "kra", "psd", "psb", "xcf", "ase", "aseprite" -> LayeredImageDecoder.decode(bytes, 256, ext)
            "tif", "tiff", "dng" -> TiffAndRawImageDecoder.decode(bytes, 256)
            "iff", "ilbm", "lbm", "neo", "pi1", "scr", "kla", "koa",
            "icns", "pntg", "mac", "pct", "pict", "ras", "sun", "sgi", "rgb",
            "pdb", "otb", "sixel" -> RetroImageDecoder.decode(bytes, 256, ext)
            "hdr", "exr", "dds", "ktx", "fits", "dcm" -> HdrAndScientificImageDecoder.decode(bytes, 256, ext)
            "wmf", "wmz", "ps", "eps", "ept", "ans" -> MetafileAndVectorImageDecoder.decode(bytes, 256, ext)
            "png" -> PureJavaPngFallback.decode(bytes, 256)
        }
        return ImageDecodeUtils.lastDecodedPixelsForTest
    }

    @Test
    fun allPureKotlinFormatDecodersDecodeGeneratedFixtures() {
        val fixtures = listOf(
            // Truevision TGA
            "icon.tga", "icon.icb", "icon.vda", "icon.vst",
            // Simple lossless
            "icon.qoi", "icon.ff",
            // Netpbm & PFM
            "icon.pbm", "icon.pgm", "icon.ppm", "icon.pnm", "icon.pam", "icon.pfm",
            // X11
            "icon.xbm", "icon.xpm", "icon.xwd",
            // PCX / DCX
            "icon.pcx", "icon.dcx",
            // Multi-layer & pixel art
            "icon.ora", "icon.kra", "icon.psd", "icon.psb", "icon.xcf", "icon.ase", "icon.aseprite",
            // TIFF & DNG
            "icon.tif", "icon.tiff", "icon.dng",
            // Retro & workstation
            "icon.iff", "icon.ilbm", "icon.lbm", "icon.neo", "icon.pi1",
            "icon.scr", "icon.kla", "icon.koa",
            "icon.icns", "icon.pntg", "icon.mac", "icon.pct", "icon.pict",
            "icon.ras", "icon.sun", "icon.sgi", "icon.rgb",
            "icon.pdb", "icon.otb", "icon.sixel",
            // HDR, GPU textures, scientific, medical
            "icon.hdr", "icon.exr", "icon.dds", "icon.ktx", "icon.fits", "icon.dcm",
            // Metafiles, PostScript/EPS, ANSI art
            "icon.wmf", "icon.wmz", "icon.ps", "icon.eps", "icon.ept", "icon.ans"
        )

        for (name in fixtures) {
            val decoded = decodeFixturePixels(name)
            assertNotNull("Failed to decode $name", decoded)
            assertTrue("Invalid dimensions for $name: ${decoded!!.width}x${decoded.height}", decoded.width > 0 && decoded.height > 0)
            val nonZeroPixels = decoded.pixels.count { (it and 0x00FFFFFF) != 0 }
            assertTrue("Decoded all-black image for $name", nonZeroPixels > 0)
        }
    }

    @Test
    fun jpeg2000HeaderDetectionRecognizesJp2AndJ2k() {
        val dir = resolveImagesDir()
        assertTrue(Jpeg2000Decoder.isJpeg2000(File(dir, "icon.jp2").readBytes()))
        assertTrue(Jpeg2000Decoder.isJpeg2000(File(dir, "icon.j2k").readBytes()))
    }

    @Test
    fun xcfZlibCompressedTilesDecodeInTheImageViewerPipeline() {
        val source = File(resolveImagesDir(), "icon.xcf").readBytes()
        // XCF compression was added in v004, which also adds the precision word.
        // Upgrade this older fixture before changing its compression property.
        val bytes = source.copyOfRange(0, 26) + ByteArray(4) + source.copyOfRange(26, source.size)
        "v004".forEachIndexed { index, value -> bytes[9 + index] = value.code.toByte() }
        for (oldOffset in listOf(43, 108, 128, 144)) {
            val shiftedOffset = oldOffset + 4
            val pointer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(shiftedOffset)
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(shiftedOffset, pointer + 4)
        }
        val compressionProperty = (0..bytes.size - 9).first { at ->
            bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() &&
                bytes[at + 2] == 0.toByte() && bytes[at + 3] == 17.toByte() &&
                bytes[at + 4] == 0.toByte() && bytes[at + 5] == 0.toByte() &&
                bytes[at + 6] == 0.toByte() && bytes[at + 7] == 1.toByte()
        }
        val tileOffset = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(148)
        assertEquals(0, bytes[compressionProperty + 8].toInt())
        assertEquals(156, tileOffset)

        val compressedTile = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(bytes, tileOffset, bytes.size - tileOffset) }
        }.toByteArray()
        val zlibXcf = bytes.copyOfRange(0, tileOffset) + compressedTile
        zlibXcf[compressionProperty + 8] = 2

        ImageDecodeUtils.lastDecodedPixelsForTest = null
        LayeredImageDecoder.decode(zlibXcf, 256, "xcf")
        assertNotNull(ImageDecodeUtils.lastDecodedPixelsForTest)
        assertEquals(64, ImageDecodeUtils.lastDecodedPixelsForTest!!.width)
        assertEquals(64, ImageDecodeUtils.lastDecodedPixelsForTest!!.height)
    }

    @Test
    fun macPaintDecodesWithoutHorizontalShift() {
        val decoded = decodeFixturePixels("icon.pntg")!!
        // Top-left corner (row 0, col 0) of the MacPaint fixture is dark background (black = 0xFF000000)
        // and the last scanline (row 719) is also decoded without running out of stream bytes.
        assertTrue((decoded.pixels[0] and 0x00FFFFFF) == 0)
        assertTrue((decoded.pixels[(719 * 576) + 575] and 0x00FFFFFF) == 0)
    }
}
