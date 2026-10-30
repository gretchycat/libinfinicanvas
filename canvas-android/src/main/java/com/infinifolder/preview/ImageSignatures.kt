package com.infinifolder.preview

/** Bounded signature hints for existing decoders; raw/headerless formats retain explicit name hints. */
object ImageSignatures {
    fun extension(bytes: ByteArray): String? {
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 255 == values[it] }
        fun text(offset: Int, value: String) = bytes.size >= offset + value.length && value.indices.all { bytes[offset + it].toInt() and 255 == value[it].code }
        return when {
            starts(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
            starts(255, 216, 255) && bytes.size >= 4 && (bytes[3].toInt() and 255) in 0xc0..0xfe -> "jpg"
            text(0, "GIF87a") || text(0, "GIF89a") -> "gif"
            text(0, "RIFF") && text(8, "WEBP") -> "webp"
            text(0, "qoif") && bytes.size >= 14 && (bytes[12].toInt() and 255) in 3..4 && (bytes[13].toInt() and 255) in 0..1 -> "qoi"
            text(0, "farbfeld") && bytes.size >= 16 -> "ff"
            starts(73, 73, 42, 0) || starts(77, 77, 0, 42) -> "tiff"
            starts(0, 0, 0, 12, 106, 80, 32, 32, 13, 10, 135, 10) -> "jp2"
            starts(255, 79, 255, 81) -> "j2k"
            starts(0, 0, 0, 12, 74, 88, 76, 32, 13, 10, 135, 10) -> "jxl"
            text(0, "8BPS") && bytes.size >= 6 && bytes[4] == 0.toByte() && bytes[5].toInt() in 1..2 -> if (bytes[5].toInt() == 1) "psd" else "psb"
            else -> null
        }
    }
}
