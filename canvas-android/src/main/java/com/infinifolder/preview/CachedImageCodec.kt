package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decoder for already bounded, materialized artwork caches. Cache policy stays with the host. */
object CachedImageCodec {
    data class Decoded(val frame: BitmapFrame, val sourceWidth: Int, val sourceHeight: Int)

    fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    internal fun sampleSize(width: Int, height: Int, targetPixels: Int): Int {
        val target = targetPixels.coerceAtLeast(1).toLong() * 2
        var sample = 1
        while (width.toLong() / sample > target || height.toLong() / sample > target) sample *= 2
        return sample
    }

    fun decode(bytes: ByteArray, targetPixels: Int): Decoded? {
        val (width, height) = dimensions(bytes)
        if (width <= 0 || height <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(width, height, targetPixels) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val maxSide = maxOf(decoded.width, decoded.height)
        val targetSide = targetPixels.coerceAtLeast(1)
        val bitmap = if (maxSide > targetSide) {
            Bitmap.createScaledBitmap(decoded,
                (decoded.width.toLong() * targetSide / maxSide).toInt().coerceAtLeast(1),
                (decoded.height.toLong() * targetSide / maxSide).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        return Decoded(BitmapFrame(bitmap, ownsBitmap = true), width, height)
    }
}
