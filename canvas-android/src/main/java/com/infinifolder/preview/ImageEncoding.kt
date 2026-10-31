package com.infinifolder.preview

import android.graphics.Bitmap
import io.github.gretchycat.infinistream.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Materialized PNG bytes, matching the existing host's byte-array publication boundary.
 * Inputs remain borrowed. takeBytes transfers storage without another copy and closes this source. */
class EncodedImage internal constructor(bytes: ByteArray, width: Int, height: Int, input: StreamMetadata) : ByteSource, PositionalReadable {
    private var bytes: ByteArray? = bytes
    private var position = 0L
    override val metadata = StreamMetadata(ContentType(DataKind.BYTES, "image/png", "png"), length = bytes.size.toLong(),
        finiteness = Finiteness.FINITE, capabilities = setOf(Capability.READ, Capability.POSITIONAL_READ, Capability.KNOWN_LENGTH),
        attributes = mapOf("width" to width.toString(), "height" to height.toString()),
        provenance = input.provenance.copy(transformations = input.provenance.transformations + ImageEncoding.descriptor.id),
        warnings = input.warnings.toList())
    @Synchronized override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = readAt(position, buffer, offset, length)
        if (count > 0) position += count
        return count
    }
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val data = bytes ?: throw IOException("Encoded image is closed")
        requireRange(position, buffer, offset, length)
        if (length == 0) return 0
        if (position >= data.size) return -1
        val count = minOf(length.toLong(), data.size - position).toInt()
        data.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }
    @Synchronized @Throws(IOException::class)
    fun takeBytes(): ByteArray {
        val result = bytes ?: throw IOException("Encoded image is closed")
        bytes = null
        return result
    }
    @Synchronized override fun close() { bytes = null }
}

object ImageEncoding {
    val descriptor = ProcessorDescriptor("canvas.android.encode.png",
        listOf(PortDescriptor("image", setOf(DataKind.IMAGE_FRAME), formats = setOf("android-bitmap"))),
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES), formats = setOf("png"), capabilities = setOf(Capability.READ))))

    @JvmStatic @JvmOverloads @Throws(IOException::class)
    fun png(input: BitmapFrame, cancellation: CancellationToken = CancellationToken.NONE): EncodedImage {
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(PngImageProcessor()) { inputs, _ ->
            mapOf("encoded" to listOf(StreamMetadata(ContentType(DataKind.BYTES, "image/png", "png"), capabilities = setOf(Capability.READ))))
        }))
        registry.open(descriptor.id, mapOf("image" to listOf(input)), ProcessorOptions(cancellation = cancellation)).use {
            return (it as EncodedImageSession).takeImage()
        }
    }
}

class PngImageProcessor : StreamProcessor {
    override val descriptor get() = ImageEncoding.descriptor
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty()) throw IOException("Unsupported PNG mode/options")
        val input = requireImageFrame(inputs, descriptor)
        options.cancellation.check()
        val bitmap = input.requireBitmap()
        val bytes = ByteArrayOutputStream()
        var writeFailure: IOException? = null
        fun checkCancellation() {
            writeFailure?.let { throw it }
            try { options.cancellation.check() } catch (error: IOException) {
                writeFailure = error
                throw error
            }
        }
        val stream = object : OutputStream() {
            override fun write(value: Int) { checkCancellation(); bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                checkCancellation(); bytes.write(buffer, offset, length)
            }
        }
        val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        // Native Bitmap.compress may catch OutputStream exceptions. Keep their original identity,
        // including interruption whose flag may have been cleared by a platform call.
        writeFailure?.let { throw it }
        options.cancellation.check()
        if (!success) throw IOException("Could not encode image as PNG")
        return EncodedImageSession(EncodedImage(bytes.toByteArray(), bitmap.width, bitmap.height, input.metadata))
    }
}

private class EncodedImageSession(private val image: EncodedImage) : ProcessorSession {
    private var ownsImage = true
    override val outputs = mapOf("encoded" to listOf(image))
    fun takeImage(): EncodedImage {
        check(ownsImage) { "Image already transferred" }
        ownsImage = false
        return image
    }
    override fun close() { if (ownsImage) { ownsImage = false; image.close() } }
}
