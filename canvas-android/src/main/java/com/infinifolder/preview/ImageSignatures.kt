package com.infinifolder.preview

import java.nio.charset.StandardCharsets
import java.util.Locale

/** A format identity supported by a real Canvas parser, confirmed from a bounded header. */
data class ImageIdentity(val mimeType: String, val title: String, val evidence: String)

/**
 * Bounded image format identification. Fixed signatures are checked together with enough
 * structure to reject common false positives. Formats without a reliable magic value are
 * recognized only when an extension selects a parser and that parser's header is plausible.
 */
object ImageSignatures {
    private const val MAX_HEADER = 69632

    /** Compatibility helper used by animation dispatch. */
    fun extension(bytes: ByteArray): String? = identify(bytes)?.let {
        when (it.mimeType) {
            "image/svg+xml" -> "svg"
            "image/openraster" -> "ora"
            "image/x-krita", "application/x-krita" -> "kra"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/avif" -> "avif"
            "image/heif", "image/heic" -> "heif"
            "image/x-qoi" -> "qoi"
            "image/x-farbfeld" -> "ff"
            "image/jxl" -> "jxl"
            "image/bmp" -> "bmp"
            "image/x-icon", "image/x-win-bitmap" -> "ico"
            "video/x-mng" -> "mng"
            "video/x-fli" -> "fli"
            "image/jpeg" -> "jpg"
            "image/tiff", "image/x-adobe-dng" -> "tiff"
            "image/jp2", "image/jpx" -> "jp2"
            "image/vnd.adobe.photoshop" -> if (ascii(bytes, 4, 2) == "\u0000\u0002") "psb" else "psd"
            "image/x-cmu-raster" -> "ras"
            "image/x-sgi" -> "sgi"
            "image/x-palm-pdb" -> "pdb"
            "image/vnd.ms-dds" -> "dds"
            "image/ktx" -> "ktx"
            "image/fits" -> "fits"
            "image/dicom" -> "dcm"
            "image/vnd.radiance" -> "hdr"
            "image/x-exr" -> "exr"
            "image/x-ilbm" -> if (ascii(bytes, 8, 4) == "PBM ") "lbm" else "iff"
            "image/x-rip" -> "rip"
            "image/x-xbitmap" -> "xbm"
            "image/x-xpixmap" -> "xpm"
            "image/x-ansi" -> "ansi"
            "image/x-portable-bitmap" -> "pbm"
            "image/x-portable-graymap" -> "pgm"
            "image/x-portable-pixmap" -> "ppm"
            "image/x-portable-arbitrarymap" -> "pam"
            "image/x-portable-floatmap" -> "pfm"
            "image/x-tga" -> "tga"
            "image/vnd.zbrush.pcx" -> "pcx"
            "image/x-xwindowdump" -> "xwd"
            "image/x-neochrome" -> "neo"
            "image/x-degas" -> "pi1"
            "image/x-zx-spectrum-scr" -> "scr"
            "image/x-koala" -> "kla"
            "image/x-macpaint" -> "mac"
            "image/x-pict" -> "pict"
            "image/x-ota-bitmap" -> "otb"
            "image/x-sixel" -> "sixel"
            "application/postscript" -> "ps"
            else -> it.mimeType.substringAfterLast('/').substringAfterLast('+')
        }
    }

    /**
     * Identifies a real encoded image or animation from a prefix. [resourceLength] is the
     * original object's length, not the prefix length, so header validation stays bounded.
     */
    fun identify(bytes: ByteArray, fileName: String? = null, resourceLength: Long? = null): ImageIdentity? {
        if (bytes.isEmpty()) return null
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val length = resourceLength ?: bytes.size.toLong()
        fun result(mime: String, title: String, evidence: String = "Validated image header") = ImageIdentity(mime, title, evidence)

        // Strong signatures with basic structural checks.
        if (starts(bytes, 0x1f, 0x8b) && validSvgz(bytes))
            return result("image/svg+xml", "SVG Vector Image", "Compressed SVG root parsed from a bounded prefix")
        if (starts(bytes, 0x50, 0x4b, 3, 4)) layeredZipMime(bytes)?.let {
            return result(it, "Layered Image", "Authoring ZIP mimetype entry validated")
        }
        if (starts(bytes, 137, 80, 78, 71, 13, 10, 26, 10) && bytes.size >= 33 &&
            be32(bytes, 8) == 13L && ascii(bytes, 12, 4) == "IHDR" &&
            be32(bytes, 16) in 1L..40000L && be32(bytes, 20) in 1L..40000L &&
            (length < 0 || length >= 33)) return result("image/png", "PNG Image")
        if ((ascii(bytes, 0, 6) == "GIF87a" || ascii(bytes, 0, 6) == "GIF89a") && bytes.size >= 13 &&
            le16(bytes, 6) > 0 && le16(bytes, 8) > 0) return result("image/gif", "GIF Image")
        if (starts(bytes, 255, 216, 255) && jpegDimensions(bytes)) return result("image/jpeg", "JPEG Image")
        if (ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" && bytes.size >= 20) {
            val declared = le32(bytes, 4)
            if (declared >= 12 && declared + 8 <= length && ascii(bytes, 12, 4) in setOf("VP8 ", "VP8L", "VP8X"))
                return result("image/webp", "WebP Image")
        }
        if (bytes.size >= 12 && ascii(bytes, 4, 4) == "ftyp") {
            val boxLength = be32(bytes, 0)
            val brand = ascii(bytes, 8, 4)
            if (boxLength in 16L..length && brand in AVIF_BRANDS) return result("image/avif", "AVIF Image")
            if (boxLength in 16L..length && brand in HEIF_BRANDS) return result("image/heif", "HEIF Image")
        }
        if (bytes.size >= 128 && u16le(bytes, 4) in setOf(0xaf11, 0xaf12)) {
            val declared = le32(bytes, 0)
            val frames = u16le(bytes, 6)
            val width = u16le(bytes, 8); val height = u16le(bytes, 10)
            if (declared in 128L..length && frames in 1..10000 && width in 1..65535 && height in 1..65535 &&
                width.toLong() * height <= 4L * 1024 * 1024)
                return result("video/x-fli", "Autodesk Animator FLI/FLC Animation")
        }
        if (starts(bytes, 138, 77, 78, 71, 13, 10, 26, 10) && bytes.size >= 44 &&
            be32(bytes, 8) == 28L && ascii(bytes, 12, 4) == "MHDR")
            return result("video/x-mng", "MNG Animation")
        if (ascii(bytes, 0, 4) == "qoif" && bytes.size >= 14 && be32(bytes, 4) in 1L..40000L &&
            be32(bytes, 8) in 1L..40000L && bytes[12].toInt() in 3..4 && bytes[13].toInt() in 0..1 && length >= 22)
            return result("image/x-qoi", "QOI Image")
        if (ascii(bytes, 0, 8) == "farbfeld" && bytes.size >= 16) {
            val width = be32(bytes, 8); val height = be32(bytes, 12)
            if (width in 1L..32768L && height in 1L..32768L && 16L + width * height * 8L == length)
                return result("image/x-farbfeld", "Farbfeld Image")
        }
        if (bytes.size >= 8 && (ascii(bytes, 0, 4) == "II*\u0000" || ascii(bytes, 0, 4) == "MM\u0000*")) {
            val little = ascii(bytes, 0, 2) == "II"
            val ifd = if (little) le32(bytes, 4) else be32(bytes, 4)
            if (ifd >= 8 && ifd + 2 <= length && ifd < bytes.size && ifd <= Int.MAX_VALUE) {
                val at = ifd.toInt()
                if (at + 2 <= bytes.size) {
                    val count = if (little) le16(bytes, at) else be16(bytes, at)
                    if (count in 1..4096 && ifd + 2L + count * 12L + 4 <= length)
                        return result("image/tiff", "TIFF Image")
                }
            }
        }
        if (bytes.size >= 22 && ascii(bytes, 0, 8) == "gimp xcf ") {
            val version = ascii(bytes, 9, 5)
            val width = be32(bytes, 14); val height = be32(bytes, 18)
            if (version.startsWith("file") && width in 1L..40000L && height in 1L..40000L && length >= 26)
                return result("image/x-xcf", "GIMP XCF Image")
        }
        if (bytes.size >= 8 && ascii(bytes, 0, 4) == "icns" && be32(bytes, 4) in 8L..length)
            return result("image/icns", "Apple Icon Image")
        if (bytes.size >= 32 && be32(bytes, 0) == 0x59A66A95L &&
            be32(bytes, 4) in 1L..32768L && be32(bytes, 8) in 1L..32768L &&
            be32(bytes, 12) in setOf(1L, 8L, 24L, 32L) && be32(bytes, 20) in 0L..3L && be32(bytes, 24) in 0L..2L)
            return result("image/x-cmu-raster", "Sun Raster Image")
        if (bytes.size >= 12 && be16(bytes, 0) == 0x01da && be16(bytes, 2) in 1..2 &&
            be16(bytes, 4) in 1..3 && be16(bytes, 6) in 1..32768 && be16(bytes, 8) in 1..32768 && be16(bytes, 10) in 1..4)
            return result("image/x-sgi", "Silicon Graphics RGB Image")
        if (bytes.size >= 68 && (bytes[0].toInt() and 255) == 10 && (bytes[2].toInt() and 255) == 1 &&
            (bytes[3].toInt() and 255) in setOf(1, 2, 4, 8) && le16(bytes, 8) >= le16(bytes, 4) &&
            le16(bytes, 10) >= le16(bytes, 6) && (bytes[65].toInt() and 255) in 1..4 && le16(bytes, 66) > 0)
            return result("image/vnd.zbrush.pcx", "ZSoft PCX Image")
        if (bytes.size >= 86 && ascii(bytes, 60, 8) == "vIMGView" && be16(bytes, 76) > 0 && be32(bytes, 78) in 86L until length)
            return result("image/x-palm-pdb", "Palm ImageViewer Database")
        if (starts(bytes, 0, 0, 0, 12, 74, 80, 32, 32, 13, 10, 135, 10))
            return result("image/jp2", "JPEG 2000 Image")
        if (starts(bytes, 255, 79, 255, 81)) return result("image/jp2", "JPEG 2000 Codestream")
        if (bytes.size >= 4 && ascii(bytes, 0, 4) == "8BPS" && bytes.size >= 26 &&
            be16(bytes, 4) in 1..2 && be16(bytes, 12) in 1..56 && be32(bytes, 14) in 1L..300000L &&
            be32(bytes, 18) in 1L..300000L && be16(bytes, 22) in setOf(1, 8, 16) && be16(bytes, 24) in 0..4)
            return result("image/vnd.adobe.photoshop", "Adobe Photoshop Image")
        if (bytes.size >= 12 && ascii(bytes, 4, 4) == "jP  " && be32(bytes, 0) == 12L)
            return result("image/jp2", "JPEG 2000 Image")
        if (starts(bytes, 255, 79, 255, 81)) return result("image/jp2", "JPEG 2000 Codestream")
        if (starts(bytes, 255, 10)) return result("image/jxl", "JPEG XL Image")
        if (starts(bytes, 0, 0, 0, 12, 74, 88, 76, 32, 13, 10, 135, 10)) return result("image/jxl", "JPEG XL Image")
        if (starts(bytes, 118, 47, 49, 1)) return result("image/x-exr", "OpenEXR Image")
        if (ascii(bytes, 0, 4) == "DDS " && bytes.size >= 128 && le32(bytes, 4) == 124L &&
            le32(bytes, 12) in 1L..32768L && le32(bytes, 16) in 1L..32768L) return result("image/vnd.ms-dds", "DirectDraw Surface Image")
        if (bytes.size >= 68 && starts(bytes, 0xab, 0x4b, 0x54, 0x58, 0x20, 0x31, 0x31, 0xbb, 13, 10, 26, 10) &&
            le32(bytes, 12) == 0x04030201L && le32(bytes, 36) in 1L..32768L && le32(bytes, 40) in 1L..32768L) {
            val format = le32(bytes, 24); val internal = le32(bytes, 28); val kv = le32(bytes, 60)
            val imageSizeAt = 64L + kv
            if ((format in setOf(0x1907L, 0x1908L) || internal in setOf(0x8051L, 0x8058L)) &&
                kv <= 16L * 1024 * 1024 && imageSizeAt + 4 <= length && imageSizeAt + 4 <= bytes.size)
                return result("image/ktx", "Khronos Texture Image")
        }
        if (validBmp(bytes, length)) return result("image/bmp", "BMP Image", "BMP dimensions, pixel layout and file bounds validated")
        if (validIco(bytes, length)) return result("image/x-icon", "Windows Icon Image", "ICO directory and first image bounds validated")
        if (bytes.size >= 100 && le32(bytes, 0) in 100L..65536L && be32(bytes, 4) == 7L &&
            be32(bytes, 8) in 1L..40000L && be32(bytes, 12) in 1L..40000L && be32(bytes, 16) in 1L..40000L)
            return result("image/x-xwindowdump", "X Window Dump Image")
        if (bytes.size >= 12 && validIff(bytes, length))
            return result("image/x-ilbm", "Amiga IFF Bitmap", "IFF FORM, BMHD and BODY chunks validated")

        if (bytes.size >= 3 && bytes[0] == 'P'.code.toByte() && validNetpbm(bytes, length)) {
            val mime = when (bytes[1].toInt().toChar()) {
                '1', '4' -> "image/x-portable-bitmap"
                '2', '5' -> "image/x-portable-graymap"
                '3', '6' -> "image/x-portable-pixmap"
                '7' -> "image/x-portable-arbitrarymap"
                'F', 'f' -> "image/x-portable-floatmap"
                else -> "image/x-portable-anymap"
            }
            return result(mime, "Netpbm Image", "Netpbm header and raster length validated")
        }

        // Parser-backed candidates for formats with no dependable fixed signature.
        when (ext) {
            "tga", "icb", "vda", "vst" -> if (TgaDecoder.identifies(bytes, resourceLength ?: length))
                return result("image/x-tga", "Truevision TGA Image", "TGA extension and dimensions/data layout validated")
            "pbm", "pgm", "ppm", "pnm", "pam", "pfm" -> if (validNetpbm(bytes, length)) {
                val mime = when (ext) {
                    "pbm" -> "image/x-portable-bitmap"; "pgm" -> "image/x-portable-graymap"
                    "ppm" -> "image/x-portable-pixmap"; "pam" -> "image/x-portable-arbitrarymap"
                    "pfm" -> "image/x-portable-floatmap"; else -> "image/x-portable-anymap"
                }
                return result(mime, "Netpbm Image", "Netpbm header and raster length validated")
            }
            "pcx", "dcx" -> if (validPcx(bytes, length, ext))
                return result("image/vnd.zbrush.pcx", "ZSoft PCX Image", "PCX/DCX image header validated")
            "iff", "ilbm", "lbm" -> if (validIff(bytes, length))
                return result("image/x-ilbm", "Amiga IFF Bitmap", "IFF FORM, BMHD and BODY chunks validated")
            "neo" -> if (bytes.size >= 128 && length == 32128L && be16(bytes, 2) in 0..2)
                return result("image/x-neochrome", "Atari ST Neochrome Image", "Neochrome header and fixed image size validated")
            "pi1", "pi2", "pi3", "pc1", "pc2", "pc3" -> if (validDegas(bytes, length, ext))
                return result("image/x-degas", "Atari ST DEGAS Image", "DEGAS resolution and image layout validated")
            "scr" -> if (bytes.size >= 16 && length == 6912L)
                return result("image/x-zx-spectrum-scr", "ZX Spectrum Screen Image", "Screen size validated")
            "kla", "koa" -> if (bytes.size >= 16 && length in setOf(10000L, 10003L))
                return result("image/x-koala", "Commodore 64 Koala Image", "Koala image size validated")
            "xbm", "xpm" -> textIdentity(bytes, length)?.let { return it }
            "svg" -> if (validSvg(bytes)) return result("image/svg+xml", "SVG Vector Image", "SVG root parsed from bounded XML prefix")
            "svgz" -> if (validSvgz(bytes)) return result("image/svg+xml", "SVG Vector Image", "Compressed SVG root parsed with an expansion limit")
            "hdr" -> if (ascii(bytes, 0, minOf(bytes.size, 10)).startsWith("#?RADIANCE") || ascii(bytes, 0, minOf(bytes.size, 6)) == "#?RGBE")
                return result("image/vnd.radiance", "Radiance HDR Image", "Radiance header validated")
            "dcm", "dicom" -> if (bytes.size >= 132 && ascii(bytes, 128, 4) == "DICM")
                return result("image/dicom", "DICOM Medical Image", "DICOM preamble validated")
            "mac", "pntg" -> if (length >= 1280 && bytes.size >= 640 && RetroImageDecoder.decodeMacPaint(bytes, 1) != null)
                return result("image/x-macpaint", "MacPaint Image", "MacPaint bitmap stream decoded")
            "pct", "pict" -> if (validPict(bytes))
                return result("image/x-pict", "QuickDraw PICT Image", "PICT frame and supported bitmap opcode validated")
            "otb" -> if (validOtb(bytes, length))
                return result("image/x-ota-bitmap", "Nokia OTA Bitmap", "OTA dimensions, depth and bitmap length validated")
            "sixel", "six" -> if (validSixel(bytes))
                return result("image/x-sixel", "DEC Sixel Graphics", "Sixel command stream recognized")
        }
        // Text-based art has no binary magic; its command grammar identifies the renderer.
        textIdentity(bytes, length)?.let { return it }
        return null
    }

    private fun textIdentity(bytes: ByteArray, resourceLength: Long = bytes.size.toLong()): ImageIdentity? {
        val text = String(bytes, 0, minOf(bytes.size, MAX_HEADER), StandardCharsets.ISO_8859_1)
        if (Regex("(?m)^!\\|(?:\\*|c[0-9A-Z]{1,2}(?:\\||$)|[LRB][0-9A-Z]{8}(?:\\||$))").containsMatchIn(text))
            return ImageIdentity("image/x-rip", "RIPscrip Graphics", "RIPscrip command stream recognized")
        if (Regex("(?m)^\\s*/\\*\\s*XPM\\s*\\*/").containsMatchIn(text) &&
            Regex("(?m)^\\s*\\\"\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\\"").containsMatchIn(text))
            return ImageIdentity("image/x-xpixmap", "X PixMap Image", "XPM header parsed")
        if (Regex("(?m)^#define\\s+\\w+_(?:width|height)\\s+\\d+").findAll(text).count() >= 2 &&
            Regex("(?is)static\\s+(?:unsigned\\s+)?char\\s+\\w+_bits\\s*\\[").containsMatchIn(text))
            return ImageIdentity("image/x-xbitmap", "X Bitmap Image", "XBM dimensions and data declaration parsed")
        if (text.startsWith("%!PS-Adobe") || text.startsWith("%!"))
            return ImageIdentity("application/postscript", "PostScript / EPS Document", "PostScript header recognized")
        if (text.startsWith("#?RADIANCE") || text.startsWith("#?RGBE"))
            return ImageIdentity("image/vnd.radiance", "Radiance HDR Image", "Radiance header recognized")
        if (text.startsWith("SIMPLE  =") && bytes.size >= 80) {
            val firstCards = text.chunked(80).take(128)
            val bitpix = firstCards.firstOrNull { it.startsWith("BITPIX  =") }?.substringAfter('=')?.trim()?.takeWhile { it == '-' || it.isDigit() }?.toIntOrNull()
            val naxis = firstCards.firstOrNull { it.startsWith("NAXIS   =") }?.substringAfter('=')?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
            val width = firstCards.firstOrNull { it.startsWith("NAXIS1  =") }?.substringAfter('=')?.trim()?.takeWhile { it.isDigit() }?.toLongOrNull()
            val height = firstCards.firstOrNull { it.startsWith("NAXIS2  =") }?.substringAfter('=')?.trim()?.takeWhile { it.isDigit() }?.toLongOrNull()
            val endCard = firstCards.indexOfFirst { it.startsWith("END") }
            val dataOffset = if (endCard >= 0) ((endCard + 1) * 80L + 2879L) / 2880L * 2880L else Long.MAX_VALUE
            val channels = if (naxis != null && naxis >= 3) 3L else 1L
            if (bitpix != null && bitpix in setOf(8, 16, 32, -32, -64) && naxis != null && naxis in 2..9 &&
                width != null && width in 1L..40000L && height != null && height in 1L..40000L &&
                endCard >= 0 &&
                dataOffset + width * height * channels * (kotlin.math.abs(bitpix) / 8) <= resourceLength)
                return ImageIdentity("image/fits", "Flexible Image Transport System", "FITS dimensions and primary data size validated")
        }
        val ansiControls = Regex("\\u001b\\[[0-?]*[ -/]*[@-~]").findAll(text).take(2).count()
        if (ansiControls >= 2)
            return ImageIdentity("image/x-ansi", "ANSI Text Art", "ANSI terminal control sequences parsed")
        val lower = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase(Locale.ROOT)
        if (lower.startsWith("<svg") || lower.startsWith("<?xml") && lower.substring(0, minOf(lower.length, 4096)).contains("<svg"))
            return ImageIdentity("image/svg+xml", "SVG Vector Image", "SVG root parsed from XML prefix")
        return null
    }

    private fun validSvg(bytes: ByteArray): Boolean = textIdentity(bytes)?.mimeType == "image/svg+xml"

    private fun layeredZipMime(bytes: ByteArray): String? = try {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            val entry = zip.nextEntry ?: return null
            if (entry.name != "mimetype" || entry.isDirectory) return null
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65)
            while (output.size() < 65) {
                val count = zip.read(buffer, 0, 65 - output.size())
                if (count < 0) break
                if (count == 0) return null
                output.write(buffer, 0, count)
            }
            val value = output.toByteArray()
            if (value.size > 64) return null
            when (String(value, StandardCharsets.US_ASCII).trim()) {
                "image/openraster" -> "image/openraster"
                "application/x-krita", "image/x-krita" -> "image/x-krita"
                else -> null
            }
        }
    } catch (_: Exception) { null }

    private fun validSvgz(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || u8(bytes, 0) != 0x1f || u8(bytes, 1) != 0x8b) return false
        return try {
            java.util.zip.GZIPInputStream(bytes.inputStream()).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() < 65536) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 65536 - out.size()))
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    // Identity only needs the root, not the entire SVG or gzip trailer.
                    if (validSvg(out.toByteArray())) return true
                }
                false
            }
        } catch (_: Exception) { false }
    }

    private fun validPict(b: ByteArray): Boolean {
        val bases = if (b.size > 530) listOf(512, 0) else listOf(0)
        return bases.any { base ->
            if (base + 18 > b.size) return@any false
            val top = be16(b, base + 2); val left = be16(b, base + 4)
            val bottom = be16(b, base + 6); val right = be16(b, base + 8)
            val width = right - left; val height = bottom - top
            if (width !in 1..40000 || height !in 1..40000 || width.toLong() * height > 160_000_000L) return@any false
            val limit = minOf(b.size - 50, base + 4096)
            for (at in base + 10 until limit) {
                if (be16(b, at) != 0x009a || at + 70 > b.size || be32(b, at + 2) != 0x000000ffL) continue
                val rowBytes = be16(b, at + 6) and 0x3fff
                val frameHeight = be16(b, at + 12) - be16(b, at + 8)
                val frameWidth = be16(b, at + 14) - be16(b, at + 10)
                val pixelSize = be16(b, at + 34)
                if (rowBytes > 0 && frameWidth == width && frameHeight == height && pixelSize in setOf(16, 32)) return@any true
            }
            false
        }
    }

    private fun validOtb(b: ByteArray, length: Long): Boolean {
        if (b.size < 5) return false
        val flags = u8(b, 0); val extended = (flags and 0x10) != 0
        val header = if (extended) 6 else 4
        if (b.size < header) return false
        val width = if (extended) be16(b, 1) else u8(b, 1)
        val height = if (extended) be16(b, 3) else u8(b, 2)
        val depth = u8(b, if (extended) 5 else 3)
        return width in 1..40000 && height in 1..40000 && depth == 1 &&
            header + (width.toLong() * height + 7) / 8 <= length
    }

    private fun validSixel(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val text = String(b, 0, minOf(b.size, MAX_HEADER), StandardCharsets.ISO_8859_1)
        val q = text.indexOf('q')
        if (!(text.startsWith("\u001bP") && q >= 2 || text.startsWith("\u0090") && q >= 1)) return false
        val data = text.substring(q + 1)
        return data.any { it in '?'..'~' } && data.count { it in '?'..'~' } >= 8
    }

    private fun validPcx(b: ByteArray, length: Long, ext: String): Boolean {
        if (ext == "dcx") {
            if (b.size < 132 || le32(b, 0) != 0x3ade68b1L) return false
            val first = le32(b, 4)
            if (first !in 4L until length || first > b.size - 128) return false
            return validPcxHeader(b, first.toInt(), length)
        }
        return validPcxHeader(b, 0, length)
    }
    private fun validPcxHeader(b: ByteArray, at: Int, length: Long): Boolean {
        if (at < 0 || at + 128 > b.size || length < at + 129L || u8(b, at) != 10 || u8(b, at + 2) !in 0..1 || u8(b, at + 3) !in setOf(1, 2, 4, 8)) return false
        val width = le16(b, at + 8) - le16(b, at + 4) + 1
        val height = le16(b, at + 10) - le16(b, at + 6) + 1
        val planes = u8(b, at + 65); val row = le16(b, at + 66)
        return width in 1..40000 && height in 1..40000 && width.toLong() * height <= 160_000_000L &&
            planes in 1..4 && row >= (width * u8(b, at + 3) + 7) / 8 && row > 0
    }

    private fun validBmp(b: ByteArray, length: Long): Boolean {
        if (b.size < 26 || ascii(b, 0, 2) != "BM") return false
        val declaredSize = le32(b, 2)
        if (declaredSize !in 26L..length) return false
        val dib = le32(b, 14)
        if (dib !in setOf(12L, 40L, 52L, 56L, 64L, 108L, 124L) || 14L + dib > length) return false
        val offset = le32(b, 10)
        if (offset < 14L + dib || offset >= length || offset > b.size) return false
        val width: Long
        val height: Long
        val planes: Int
        val depth: Int
        val compression: Long
        val imageSize: Long
        if (dib == 12L) {
            width = le16(b, 18).toLong(); height = le16(b, 20).toLong()
            planes = le16(b, 22); depth = le16(b, 24); compression = 0; imageSize = 0
        } else {
            if (b.size < 54) return false
            width = signedLe32(b, 18)
            val signedHeight = signedLe32(b, 22)
            height = kotlin.math.abs(signedHeight)
            planes = le16(b, 26); depth = le16(b, 28)
            compression = le32(b, 30); imageSize = le32(b, 34)
        }
        if (width !in 1L..40000L || height !in 1L..40000L || width * height > 160_000_000L ||
            planes != 1 || depth !in setOf(1, 4, 8, 16, 24, 32) || compression !in 0L..6L) return false
        if (compression in setOf(0L, 3L, 6L)) {
            val row = ((width * depth + 31) / 32) * 4
            if (offset + row * height > minOf(length, declaredSize)) return false
        } else if (imageSize == 0L || offset + imageSize > minOf(length, declaredSize)) return false
        return true
    }

    private fun validIco(b: ByteArray, length: Long): Boolean {
        if (b.size < 22 || u8(b, 0) != 0 || u8(b, 1) != 0 || u8(b, 2) != 1 || u8(b, 3) != 0) return false
        val count = le16(b, 4)
        if (count !in 1..64 || 6L + count * 16L > length || 6 + count * 16 > b.size) return false
        val size = le32(b, 14)
        val offset = le32(b, 18)
        return size > 0 && offset >= 6L + count * 16 && offset + size <= length
    }

    private fun signedLe32(b: ByteArray, at: Int): Long {
        val value = le32(b, at)
        return if (value >= 0x80000000L) value - 0x1_0000_0000L else value
    }

    private fun validNetpbm(b: ByteArray, length: Long): Boolean {
        if (b.size < 3 || b[0].toInt() != 'P'.code) return false
        val kind = b[1].toInt().toChar()
        if (kind !in '1'..'7' && kind !in setOf('F', 'f')) return false
        var at = 2
        fun token(): String? {
            while (at < b.size) {
                val c = u8(b, at)
                if (c == '#'.code) { while (at < b.size && u8(b, at) !in setOf(10, 13)) at++ }
                else if (c in setOf(9, 10, 13, 32)) at++ else break
            }
            if (at >= b.size) return null
            val start = at
            while (at < b.size && u8(b, at) !in setOf(9, 10, 13, 32, '#'.code)) at++
            return String(b, start, at - start, StandardCharsets.US_ASCII)
        }
        fun positive() = token()?.toLongOrNull()?.takeIf { it in 1L..40000L }?.toInt()
        if (kind == '7') {
            val header = String(b, 2, minOf(b.size - 2, 4096), StandardCharsets.US_ASCII)
            val width = Regex("(?m)^WIDTH\\s+(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
            val height = Regex("(?m)^HEIGHT\\s+(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
            val depth = Regex("(?m)^DEPTH\\s+(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
            val max = Regex("(?m)^MAXVAL\\s+(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
            val end = Regex("(?m)^ENDHDR\\s*\\r?\\n").find(header)?.range?.last?.plus(3) ?: return false
            return width != null && width in 1L..40000L && height != null && height in 1L..40000L &&
                depth != null && depth in 1L..4L && max != null && max in 1L..65535L &&
                end + width * height * depth * (if (max > 255) 2 else 1) <= length
        }
        val width = positive() ?: return false; val height = positive() ?: return false
        val channels = if (kind == '3' || kind == '6' || kind == 'F') 3L else 1L
        val max = when (kind) {
            '1', '4' -> 1
            'F', 'f' -> {
                val scale = token()?.toFloatOrNull()?.takeIf { it.isFinite() && it != 0f } ?: return false
                if (at >= b.size || u8(b, at) !in setOf(9, 10, 13, 32)) return false
                if (u8(b, at) == 13 && at + 1 < b.size && u8(b, at + 1) == 10) at += 2 else at++
                return width.toLong() * height * channels * 4L <= length - at
            }
            else -> token()?.toLongOrNull()?.takeIf { it in 1L..65535L }?.toInt() ?: return false
        }
        if (width.toLong() * height > 160_000_000L) return false
        if (kind in setOf('1', '2', '3')) {
            val sampleLimit = if (kind == '1') 1L else max.toLong()
            val sampleCount = minOf(16L, width.toLong() * height * channels).toInt()
            repeat(sampleCount) {
                val sample = token()?.toLongOrNull() ?: return false
                if (sample !in 0L..sampleLimit) return false
            }
            return true
        }
        if (at >= b.size || u8(b, at) !in setOf(9, 10, 13, 32)) return false
        if (u8(b, at) == 13 && at + 1 < b.size && u8(b, at + 1) == 10) at += 2 else at++
        val rowBytes = if (kind == '4') (width + 7L) / 8 else width.toLong() * channels * if (max > 255) 2 else 1
        return at + rowBytes * height <= length
    }

    private fun validIff(b: ByteArray, length: Long): Boolean {
        if (b.size < 12 || ascii(b, 0, 4) != "FORM" || ascii(b, 8, 4) !in setOf("ILBM", "PBM ")) return false
        val end = be32(b, 4) + 8
        if (end !in 12L..length) return false
        var at = 12; var width = 0; var height = 0; var planes = 0; var body = false
        while (at + 8 <= minOf(b.size.toLong(), end).toInt()) {
            val id = ascii(b, at, 4); val size = be32(b, at + 4)
            if (size > end - at - 8 || size > Int.MAX_VALUE) return false
            if (id == "BMHD" && size >= 20) {
                width = be16(b, at + 8); height = be16(b, at + 10); planes = u8(b, at + 16)
                if (width !in 1..40000 || height !in 1..40000 || planes !in 1..32 || u8(b, at + 17) !in 0..2 || u8(b, at + 18) !in 0..1) return false
            }
            if (id == "BODY" && size > 0) body = true
            at += 8 + size.toInt() + (size.toInt() and 1)
        }
        return width > 0 && height > 0 && planes > 0 && body
    }

    private fun validDegas(b: ByteArray, length: Long, ext: String): Boolean {
        if (b.size < 34) return false
        val mode = be16(b, 0)
        val resolution = mode and 3
        if (resolution !in 0..2) return false
        val compressed = ext.startsWith("pc") || (mode and 0x8000) != 0
        return if (compressed) length > 34 else length >= when (resolution) { 0 -> 32034L; 1 -> 32066L; else -> 32034L }
    }

    private fun jpegDimensions(b: ByteArray): Boolean {
        var at = 2
        while (at + 4 <= b.size && at < MAX_HEADER) {
            if (u8(b, at) != 255) { at++; continue }
            while (at < b.size && u8(b, at) == 255) at++
            if (at >= b.size) return false
            val marker = u8(b, at++)
            if (marker in setOf(0xd8, 0xd9) || marker in 0xd0..0xd7 || marker == 0x01) continue
            if (at + 2 > b.size) return false
            val len = be16(b, at); if (len < 2 || at + len > b.size) return false
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf))
                return len >= 8 && be16(b, at + 3) > 0 && be16(b, at + 5) > 0
            if (marker == 0xda) return false
            at += len
        }
        return false
    }

    private fun ascii(b: ByteArray, at: Int, count: Int): String =
        if (at < 0 || count < 0 || at.toLong() + count > b.size) "" else String(b, at, count, StandardCharsets.ISO_8859_1)
    private fun starts(b: ByteArray, vararg expected: Int) = b.size >= expected.size && expected.indices.all { u8(b, it) == expected[it] }
    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 255
    private fun be16(b: ByteArray, at: Int) = (u8(b, at) shl 8) or u8(b, at + 1)
    private fun le16(b: ByteArray, at: Int) = u8(b, at) or (u8(b, at + 1) shl 8)
    private fun be32(b: ByteArray, at: Int) = (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()
    private fun le32(b: ByteArray, at: Int) = u8(b, at).toLong() or (u8(b, at + 1).toLong() shl 8) or (u8(b, at + 2).toLong() shl 16) or (u8(b, at + 3).toLong() shl 24)
    private fun u16le(b: ByteArray, at: Int) = le16(b, at)

    private val AVIF_BRANDS = setOf("avif", "avis")
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heis", "hevm", "hevs", "heim")
}
