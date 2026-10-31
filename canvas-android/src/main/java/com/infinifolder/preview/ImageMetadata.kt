package com.infinifolder.preview

import android.media.ExifInterface
import io.github.gretchycat.infinistream.*
import java.io.IOException
import java.io.InputStream

/** Raw values retain the existing backend's units/precision; presentation belongs to the host. */
data class ImageExifFields(val make: String? = null, val model: String? = null,
    val dateTime: String? = null, val exposureTime: String? = null, val fNumber: String? = null,
    val iso: String? = null, val focalLength: String? = null, val location: ImageLocation? = null)
data class ImageLocation(val latitude: Float, val longitude: Float)

/** Immutable structured description, independent of the borrowed encoded source's lifetime. */
class ImageExifMetadata internal constructor(val fields: ImageExifFields, input: StreamMetadata) : StreamHandle {
    override val metadata = ImageMetadata.outputMetadata(input).copy(attributes = buildMap {
        put("storage", "image-exif")
        fields.make?.let { put("make", it) }; fields.model?.let { put("model", it) }
        fields.dateTime?.let { put("dateTime", it) }; fields.exposureTime?.let { put("exposureTime", it) }
        fields.fNumber?.let { put("fNumber", it) }; fields.iso?.let { put("iso", it) }
        fields.focalLength?.let { put("focalLength", it) }
        fields.location?.let { put("latitude", it.latitude.toString()); put("longitude", it.longitude.toString()) }
    })
    override fun close() {} // No live parser, stream or bitmap is retained.
}

/** Platform EXIF parsing behind common source/processor contracts; no resolver, staging or UI policy. */
object ImageMetadata {
    val descriptor = ProcessorDescriptor("canvas.android.exif",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("exif", setOf(DataKind.STRUCTURED), formats = setOf("image-exif"))))

    /** Opens one independent source and closes it on success, error or cancellation. */
    @JvmStatic @JvmOverloads @Throws(IOException::class)
    fun read(resource: SourceResource, cancellation: CancellationToken = CancellationToken.NONE): ImageExifMetadata {
        cancellation.check()
        return resource.open().use { read(it, cancellation) }
    }

    /** Borrows and consumes the current sequential cursor. The caller retains source ownership. */
    @JvmStatic @JvmOverloads @Throws(IOException::class)
    fun read(source: ByteSource, cancellation: CancellationToken = CancellationToken.NONE): ImageExifMetadata =
        read(source, cancellation, ImageExifProcessor())

    internal fun read(source: ByteSource, cancellation: CancellationToken, processor: ImageExifProcessor): ImageExifMetadata {
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(processor) { inputs, _ ->
            mapOf("exif" to listOf(outputMetadata(inputs.getValue("encoded").single())))
        }))
        registry.openPipeline(source, descriptor.outputs.single(), ProcessorOptions(cancellation = cancellation)).use {
            return it.outputs.getValue("exif").single() as ImageExifMetadata
        }
    }

    internal fun outputMetadata(input: StreamMetadata) = StreamMetadata(
        ContentType(DataKind.STRUCTURED, format = "image-exif"), name = input.name, finiteness = Finiteness.FINITE,
        attributes = mapOf("storage" to "image-exif"),
        provenance = input.provenance.copy(transformations = input.provenance.transformations + descriptor.id),
        warnings = input.warnings.toList())
}

class ImageExifProcessor internal constructor(private val parse: (InputStream) -> ImageExifFields) : StreamProcessor {
    constructor() : this(::readAndroidExif)
    override val descriptor get() = ImageMetadata.descriptor

    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty()) throw IOException("Unsupported EXIF mode/options")
        val source = inputs["encoded"]?.singleOrNull() as? ByteSource
            ?: throw IOException("EXIF requires one encoded byte source")
        if (inputs.keys != setOf("encoded") || !descriptor.inputs.single().accepts(source.metadata))
            throw IOException("Incompatible EXIF input")
        options.cancellation.check()
        val input = source.metadata
        var inputFailure: IOException? = null
        // Check cancellation around reads, including InputStream's default skip implementation.
        val guarded = object : ByteSource {
            override val metadata get() = source.metadata
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                try {
                    requireRange(0, buffer, offset, length)
                    options.cancellation.check()
                    // Android EXIF expects full requested reads. Fill its existing buffer without
                    // staging the file or allocating another buffer; legal short source reads work.
                    var total = 0
                    while (total < length) {
                        options.cancellation.check()
                        val count = source.read(buffer, offset + total, length - total)
                        if (count < -1 || count > length - total || count == 0)
                            throw IOException("Invalid EXIF source read result: $count")
                        options.cancellation.check()
                        if (count == -1) return if (total == 0) -1 else total
                        total += count
                    }
                    return total
                } catch (error: IOException) {
                    if (inputFailure == null) inputFailure = error
                    throw error
                }
            }
            override fun close() {} // Borrowed input.
        }
        val fields = guarded.asInputStream(ownsSource = false).use(parse)
        // Some Android parser paths catch IOExceptions, including interrupted reads.
        options.cancellation.check()
        inputFailure?.let { throw it }
        val output = ImageExifMetadata(fields, input)
        return object : ProcessorSession {
            override val outputs = mapOf("exif" to listOf(output))
            override fun close() { output.close() }
        }
    }
}

private fun readAndroidExif(stream: InputStream): ImageExifFields {
    val exif = ExifInterface(stream)
    val coordinates = FloatArray(2)
    return ImageExifFields(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL),
        exif.getAttribute(ExifInterface.TAG_DATETIME), exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
        exif.getAttribute(ExifInterface.TAG_F_NUMBER), exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
        exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
        if (exif.getLatLong(coordinates)) ImageLocation(coordinates[0], coordinates[1]) else null)
}
