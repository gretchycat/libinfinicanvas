package com.infinifolder.preview

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImageTransformsTest {
    @Test fun cropFractionsUseOriginalTruncation() {
        assertEquals(PixelCrop(1, 2, 6, 5), ImageCropBounds(1.9f, 2.8f, 7.9f, 7.7f).pixels(10, 10))
    }
    @Test fun cropOutsideSourceUsesOriginalClamping() {
        assertEquals(PixelCrop(0, 0, 10, 8), ImageCropBounds(-100f, -100f, 100f, 100f).pixels(10, 8))
        assertEquals(PixelCrop(9, 7, 1, 1), ImageCropBounds(100f, 100f, 200f, 200f).pixels(10, 8))
    }
    @Test fun reversedAndCollapsedCropsKeepOnePixel() {
        assertEquals(PixelCrop(5, 4, 1, 1), ImageCropBounds(5f, 4f, 2f, 1f).pixels(10, 8))
        assertEquals(PixelCrop(0, 0, 1, 1), ImageCropBounds(-10f, -10f, -2f, -1f).pixels(10, 8))
    }
    @Test fun singlePixelSourceIsAValidCrop() {
        assertEquals(PixelCrop(0, 0, 1, 1), ImageCropBounds(-10f, 20f, 500f, 1000f).pixels(1, 1))
    }
    @Test fun fullSourceBoundsKeepExactGeometry() {
        assertEquals(PixelCrop(0, 0, 20, 11), ImageCropBounds(0f, 0f, 20f, 11f).pixels(20, 11))
    }
    @Test fun nonFiniteCropBoundsAreRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            for (index in 0..3) {
                val bounds = floatArrayOf(0f, 0f, 2f, 2f).apply { this[index] = value }
                assertThrows(IllegalArgumentException::class.java) { ImageCropBounds(bounds[0], bounds[1], bounds[2], bounds[3]) }
            }
    }
    @Test fun invalidSourceDimensionsAreRejected() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1))
            assertThrows(IllegalArgumentException::class.java) { ImageCropBounds(0f, 0f, 1f, 1f).pixels(width, height) }
    }
    @Test fun resizeRequiresPositiveDimensionsWithoutNewViewerLimits() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1))
            assertThrows(IllegalArgumentException::class.java) { ImageTransform.Resize(width, height) }
        assertEquals(10001, ImageTransform.Resize(10001, 1).width) // UI owns its existing 10000-pixel cap.
    }
    @Test fun allOrientationOptionsRoundTrip() {
        for (change in ImageOrientationChange.entries) {
            val transform = ImageTransform.Orient(change)
            assertEquals(transform, ImageTransforms.parse(ImageTransforms.options(transform)))
        }
    }
    @Test fun fractionalAndNegativeCropOptionsRoundTrip() {
        val transform = ImageTransform.Crop(ImageCropBounds(-1.25f, 2.75f, 45.5f, 80f))
        assertEquals(transform, ImageTransforms.parse(ImageTransforms.options(transform)))
    }
    @Test fun resizeOptionsRoundTrip() {
        val transform = ImageTransform.Resize(125, 300)
        assertEquals(transform, ImageTransforms.parse(ImageTransforms.options(transform)))
    }
    @Test fun missingUnknownOrMixedOptionsAreRejected() {
        for (options in listOf(emptyMap(), mapOf("operation" to "UNKNOWN"), mapOf("operation" to "CROP"),
            mapOf("operation" to "RESIZE", "width" to "10", "height" to "20", "left" to "0"),
            mapOf("operation" to "FLIP_HORIZONTAL", "extra" to "1")))
            assertThrows(IOException::class.java) { ImageTransforms.parse(options) }
    }
    @Test fun invalidNumericOptionsAreRejected() {
        for (value in listOf("0", "-1", "2147483648", "1.5", "bad"))
            assertThrows(IOException::class.java) { ImageTransforms.parse(mapOf("operation" to "RESIZE", "width" to value, "height" to "1")) }
        for (value in listOf("NaN", "Infinity", "bad"))
            assertThrows(IOException::class.java) { ImageTransforms.parse(mapOf("operation" to "CROP", "left" to value, "top" to "0", "right" to "2", "bottom" to "2")) }
    }
    @Test fun descriptorRequiresTypedAndroidFramesAndPull() {
        val descriptor = ImageTransforms.descriptor
        assertEquals(setOf(FlowMode.PULL), descriptor.modes)
        assertEquals(setOf("android-bitmap"), descriptor.inputs.single().formats)
        assertEquals(setOf(DataKind.IMAGE_FRAME), descriptor.outputs.single().kinds)
        assertFalse(descriptor.inputs.single().accepts(StreamMetadata()))
    }
    @Test fun untypedFramesWrongPortsAndMultiplicityAreRejected() {
        val fake = object : StreamHandle {
            override val metadata = StreamMetadata(ContentType(DataKind.IMAGE_FRAME, format = "android-bitmap"))
            override fun close() { fail("Borrowed input must stay open") }
        }
        for (inputs in listOf(emptyMap(), mapOf("wrong" to listOf(fake)), mapOf("image" to listOf(fake)), mapOf("image" to listOf(fake, fake))))
            assertThrows(IOException::class.java) { ImageTransformProcessor().open(inputs, ProcessorOptions(mapOf("operation" to "FLIP_HORIZONTAL"))) }
    }
    @Test fun pushIsRejectedBeforeOpeningPixels() {
        assertThrows(IOException::class.java) { ImageTransformProcessor().open(emptyMap(), ProcessorOptions(mode = FlowMode.PUSH)) }
    }
}
