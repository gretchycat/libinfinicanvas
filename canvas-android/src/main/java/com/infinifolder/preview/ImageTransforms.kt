package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.Matrix
import io.github.gretchycat.infinistream.*
import java.io.IOException

/** Source-pixel bounds after the host maps its display/gesture coordinates. */
data class ImageCropBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() }) { "Crop bounds must be finite" } }
    internal fun pixels(width: Int, height: Int): PixelCrop {
        require(width > 0 && height > 0)
        // Preserve the viewer's truncation and clamping, including out-of-bounds/one-pixel crops.
        val x = left.toInt().coerceIn(0, width - 1)
        val y = top.toInt().coerceIn(0, height - 1)
        val r = right.toInt().coerceIn(x + 1, width)
        val b = bottom.toInt().coerceIn(y + 1, height)
        return PixelCrop(x, y, r - x, b - y)
    }
}
internal data class PixelCrop(val left: Int, val top: Int, val width: Int, val height: Int)

enum class ImageOrientationChange { ROTATE_CLOCKWISE, ROTATE_COUNTERCLOCKWISE, FLIP_HORIZONTAL, FLIP_VERTICAL }
sealed interface ImageTransform {
    data class Orient(val change: ImageOrientationChange) : ImageTransform
    data class Crop(val bounds: ImageCropBounds) : ImageTransform
    data class Resize(val width: Int, val height: Int) : ImageTransform {
        init { require(width > 0 && height > 0) { "Resize dimensions must be positive" } }
    }
}

object ImageTransforms {
    val descriptor = ProcessorDescriptor("canvas.android.transform",
        listOf(PortDescriptor("image", setOf(DataKind.IMAGE_FRAME), formats = setOf("android-bitmap"))),
        listOf(PortDescriptor("image", setOf(DataKind.IMAGE_FRAME), formats = setOf("android-bitmap"))),
        options = setOf("operation", "left", "top", "right", "bottom", "width", "height"))

    /** Borrow input; caller closes or explicitly takes the resulting pixels. No-op results may alias input. */
    @JvmStatic @JvmOverloads @Throws(IOException::class)
    fun apply(input: BitmapFrame, transform: ImageTransform, cancellation: CancellationToken = CancellationToken.NONE): BitmapFrame {
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(ImageTransformProcessor()) { values, _ ->
            val metadata = values.getValue("image").single()
            // Pixel geometry/config and alias ownership are determined by the actual Android result.
            mapOf("image" to listOf(StreamMetadata(ContentType(DataKind.IMAGE_FRAME, format = "android-bitmap"),
                finiteness = Finiteness.FINITE, provenance = metadata.provenance.copy(
                    transformations = metadata.provenance.transformations + descriptor.id), warnings = metadata.warnings)))
        }))
        // An edit is an explicit requested step. A frame-to-frame target alone would allow a zero-step route.
        registry.open(descriptor.id, mapOf("image" to listOf(input)),
            ProcessorOptions(options(transform), cancellation = cancellation)).use {
            return (it as ImageFrameSession).takeFrame()
        }
    }

    internal fun options(transform: ImageTransform): Map<String, String> = when (transform) {
        is ImageTransform.Orient -> mapOf("operation" to transform.change.name)
        is ImageTransform.Crop -> with(transform.bounds) { mapOf("operation" to "CROP", "left" to left.toString(),
            "top" to top.toString(), "right" to right.toString(), "bottom" to bottom.toString()) }
        is ImageTransform.Resize -> mapOf("operation" to "RESIZE", "width" to transform.width.toString(), "height" to transform.height.toString())
    }
    internal fun parse(values: Map<String, String>): ImageTransform {
        try {
            val operation = values["operation"] ?: throw IOException("Missing image operation")
            val keys = when (operation) {
                "CROP" -> setOf("operation", "left", "top", "right", "bottom")
                "RESIZE" -> setOf("operation", "width", "height")
                else -> setOf("operation")
            }
            if (values.keys != keys) throw IOException("Unexpected or missing image options")
            return when (operation) {
                "CROP" -> ImageTransform.Crop(ImageCropBounds(values.getValue("left").toFloat(), values.getValue("top").toFloat(),
                    values.getValue("right").toFloat(), values.getValue("bottom").toFloat()))
                "RESIZE" -> ImageTransform.Resize(values.getValue("width").toInt(), values.getValue("height").toInt())
                else -> ImageTransform.Orient(ImageOrientationChange.valueOf(operation))
            }
        } catch (error: IllegalArgumentException) { throw IOException("Invalid image operation/options", error) }
    }
}

class ImageTransformProcessor : StreamProcessor {
    override val descriptor get() = ImageTransforms.descriptor
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL) throw IOException("Image transforms require pull mode")
        val input = requireImageFrame(inputs, descriptor)
        val transform = ImageTransforms.parse(options.values)
        options.cancellation.check()
        val bitmap = input.requireBitmap()
        val result = when (transform) {
            is ImageTransform.Orient -> {
                val matrix = Matrix()
                when (transform.change) {
                    ImageOrientationChange.ROTATE_CLOCKWISE -> matrix.postRotate(90f)
                    ImageOrientationChange.ROTATE_COUNTERCLOCKWISE -> matrix.postRotate(-90f)
                    ImageOrientationChange.FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ImageOrientationChange.FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            is ImageTransform.Crop -> {
                val crop = transform.bounds.pixels(bitmap.width, bitmap.height)
                Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
            }
            is ImageTransform.Resize -> Bitmap.createScaledBitmap(bitmap, transform.width, transform.height, true)
        }
        try {
            options.cancellation.check()
            val frame = BitmapFrame(result, ownsBitmap = result !== bitmap,
                provenance = input.metadata.provenance.copy(transformations = input.metadata.provenance.transformations +
                    "${descriptor.id}:${options.values.getValue("operation")}"), warnings = input.metadata.warnings)
            return ImageFrameSession(frame)
        } catch (error: Throwable) {
            if (result !== bitmap) result.recycle()
            throw error
        }
    }
}

internal fun requireImageFrame(inputs: Map<String, List<StreamHandle>>, descriptor: ProcessorDescriptor): BitmapFrame {
    val input = inputs["image"]?.singleOrNull() as? BitmapFrame ?: throw IOException("Requires one Android bitmap frame")
    if (inputs.keys != setOf("image") || !descriptor.inputs.single().accepts(input.metadata)) throw IOException("Incompatible bitmap input")
    return input
}

private class ImageFrameSession(private val frame: BitmapFrame) : ProcessorSession {
    private var ownsFrame = true
    override val outputs = mapOf("image" to listOf(frame))
    fun takeFrame(): BitmapFrame {
        check(ownsFrame) { "Frame already transferred" }
        ownsFrame = false
        return frame
    }
    override fun close() { if (ownsFrame) { ownsFrame = false; frame.close() } }
}
