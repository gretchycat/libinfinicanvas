package com.infinifolder.preview

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EncodedImageTest {
    private fun image(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) = EncodedImage(bytes, 2, 3, StreamMetadata())
    @Test fun actualEncodedMetadataDropsFrameClaims() {
        val input = StreamMetadata(ContentType(DataKind.IMAGE_FRAME, format = "android-bitmap"), "source.jpg", 100,
            capabilities = setOf(Capability.WRITE), attributes = mapOf("pixelFormat" to "ARGB_8888", "width" to "900"),
            provenance = Provenance(source = "original", transformations = listOf("crop"), checksums = mapOf("sha256" to "input")),
            verification = Verification.VERIFIED, warnings = listOf("input warning"))
        val output = EncodedImage(byteArrayOf(1, 2, 3), 2, 3, input).metadata
        assertEquals(ContentType(DataKind.BYTES, "image/png", "png"), output.content)
        assertEquals(3L, output.length)
        assertEquals(setOf(Capability.READ, Capability.POSITIONAL_READ, Capability.KNOWN_LENGTH), output.capabilities)
        assertEquals(mapOf("width" to "2", "height" to "3"), output.attributes)
        assertNull(output.name); assertEquals(Verification.UNKNOWN, output.verification)
        assertEquals(Finiteness.FINITE, output.finiteness)
        assertEquals("original", output.provenance.source)
        assertEquals(listOf("crop", ImageEncoding.descriptor.id), output.provenance.transformations)
        assertEquals(input.provenance.checksums, output.provenance.checksums)
        assertEquals(input.warnings, output.warnings)
    }
    @Test fun sequentialReadsShortEofAndZeroLengthAreCorrect() {
        val image = image(); val buffer = ByteArray(3)
        assertEquals(3, image.read(buffer)); assertArrayEquals(byteArrayOf(1, 2, 3), buffer)
        assertEquals(1, image.read(buffer)); assertEquals(4.toByte(), buffer[0])
        assertEquals(-1, image.read(buffer)); assertEquals(0, image.read(buffer, 1, 0))
    }
    @Test fun positionalReadDoesNotMoveSequentialCursor() {
        val image = image(); val buffer = ByteArray(2)
        assertEquals(2, image.readAt(2, buffer)); assertArrayEquals(byteArrayOf(3, 4), buffer)
        image.read(buffer); assertArrayEquals(byteArrayOf(1, 2), buffer)
    }
    @Test fun independentCursorsRetainTheirOwnPositions() {
        val image = image(); val a = SourceCursor(image, image.metadata); val b = SourceCursor(image, image.metadata)
        val buffer = ByteArray(1); a.seek(3); a.read(buffer); assertEquals(4.toByte(), buffer[0])
        b.read(buffer); assertEquals(1.toByte(), buffer[0]); a.close(); b.close()
        assertEquals(4, image.read(ByteArray(4)))
    }
    @Test fun longOffsetsAtAndBeyondEofAreSafe() {
        val image = image(); val buffer = ByteArray(1)
        assertEquals(-1, image.readAt(4, buffer)); assertEquals(-1, image.readAt(Int.MAX_VALUE.toLong() + 1, buffer))
        assertEquals(0, image.readAt(Long.MAX_VALUE, buffer, 0, 0))
    }
    @Test fun invalidRangesAreRejectedWithoutMovingCursor() {
        val image = image(); val buffer = ByteArray(1)
        assertThrows(IOException::class.java) { image.readAt(-1, buffer) }
        assertThrows(IOException::class.java) { image.readAt(Long.MAX_VALUE, buffer) }
        assertThrows(IOException::class.java) { image.read(buffer, 0, 2) }
        image.read(buffer); assertEquals(1.toByte(), buffer[0])
    }
    @Test fun takeBytesTransfersTheOriginalStorageAndClosesTheSource() {
        val bytes = byteArrayOf(1, 2, 3); val image = image(bytes)
        assertSame(bytes, image.takeBytes()); image.close()
        assertThrows(IOException::class.java) { image.takeBytes() }
        assertThrows(IOException::class.java) { image.read(ByteArray(1)) }
    }
    @Test fun closeIsIdempotentAndDisablesEveryRead() {
        val image = image(); image.close(); image.close()
        assertThrows(IOException::class.java) { image.read(ByteArray(0)) }
        assertThrows(IOException::class.java) { image.readAt(0, ByteArray(1)) }
        assertThrows(IOException::class.java) { image.takeBytes() }
    }
    @Test fun emptyStorageReturnsEofWithoutInventingBytes() {
        val image = image(byteArrayOf())
        assertEquals(0L, image.metadata.length)
        assertEquals(-1, image.read(ByteArray(1))); assertEquals(0, image.read(ByteArray(0)))
    }
    @Test fun pngProcessorRejectsModesOptionsAndWrongInputBeforeBackend() {
        val processor = PngImageProcessor()
        for (options in listOf(ProcessorOptions(mode = FlowMode.PUSH), ProcessorOptions(mapOf("quality" to "20")), ProcessorOptions()))
            assertThrows(IOException::class.java) { processor.open(emptyMap(), options) }
        assertEquals(setOf(DataKind.IMAGE_FRAME), processor.descriptor.inputs.single().kinds)
        assertEquals(setOf("png"), processor.descriptor.outputs.single().formats)
    }
}
