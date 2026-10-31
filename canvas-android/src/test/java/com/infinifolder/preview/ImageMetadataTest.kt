package com.infinifolder.preview

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class ImageMetadataTest {
    private class Source(override val metadata: StreamMetadata = StreamMetadata(capabilities = setOf(Capability.READ))) : ByteSource {
        var closed = false
        var reads = 0
        var onRead: (() -> Int)? = null
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            onRead?.let { return it() }
            if (length == 0) return 0
            buffer[offset] = 42
            return 1 // Deliberately short; never closes or stages the caller's source.
        }
        override fun close() { closed = true }
    }
    private fun open(source: ByteSource, processor: ImageExifProcessor, options: ProcessorOptions = ProcessorOptions()) =
        processor.open(mapOf("encoded" to listOf(source)), options)

    @Test fun descriptorDeclaresPullBytesToStructuredMetadata() {
        val d = ImageMetadata.descriptor
        assertEquals(setOf(FlowMode.PULL), d.modes)
        assertTrue(d.inputs.single().accepts(Source().metadata))
        assertEquals(setOf("image-exif"), d.outputs.single().formats)
        assertTrue(d.options.isEmpty())
    }
    @Test fun actualOutputRetainsFieldsAndClearsEncodedClaims() {
        val input = StreamMetadata(ContentType(DataKind.BYTES, "image/jpeg", "jpeg"), "photo.jpg", 999,
            Finiteness.FINITE, setOf(Capability.READ, Capability.POSITIONAL_READ, Capability.KNOWN_LENGTH),
            attributes = mapOf("width" to "9000"), provenance = Provenance(source = "content://original", transformations = listOf("unwrap"), checksums = mapOf("sha256" to "original")),
            verification = Verification.VERIFIED, warnings = listOf("input warning"))
        val fields = ImageExifFields("Make", "Model", "2026:10:31 12:00:00", "0.01", "2.8", "200", "50/1", ImageLocation(41.5f, -87.25f))
        val result = ImageMetadata.read(Source(input), CancellationToken.NONE, ImageExifProcessor { fields })
        assertEquals(fields, result.fields)
        val m = result.metadata
        assertEquals(DataKind.STRUCTURED, m.content.kind)
        assertNull(m.content.mimeType); assertNull(m.length)
        assertTrue(m.capabilities.isEmpty()); assertEquals(Verification.UNKNOWN, m.verification)
        assertEquals(Finiteness.FINITE, m.finiteness)
        assertEquals("photo.jpg", m.name)
        assertEquals("content://original", m.provenance.source)
        assertEquals(listOf("unwrap", "canvas.android.exif"), m.provenance.transformations)
        assertEquals(input.provenance.checksums, m.provenance.checksums)
        assertEquals(listOf("input warning"), m.warnings)
        assertEquals(mapOf("storage" to "image-exif", "make" to "Make", "model" to "Model", "dateTime" to fields.dateTime,
            "exposureTime" to "0.01", "fNumber" to "2.8", "iso" to "200", "focalLength" to "50/1", "latitude" to "41.5", "longitude" to "-87.25"), m.attributes)
    }
    @Test fun absentFieldsRemainUnknown() {
        val value = ImageMetadata.read(Source(), CancellationToken.NONE, ImageExifProcessor { ImageExifFields() })
        assertEquals(mapOf("storage" to "image-exif"), value.metadata.attributes)
        assertNull(value.fields.location)
    }
    @Test fun rawBlankAndRationalValuesArePreserved() {
        val fields = ImageExifFields(make = " ", exposureTime = "1/125", focalLength = "350/10")
        val value = ImageMetadata.read(Source(), CancellationToken.NONE, ImageExifProcessor { fields })
        assertEquals(fields, value.fields)
        assertEquals(" ", value.metadata.attributes["make"])
    }
    @Test fun sessionAndReturnedDescriptionDoNotCloseBorrowedSource() {
        val source = Source()
        val session = open(source, ImageExifProcessor { it.close(); ImageExifFields(model = "live") })
        val value = session.outputs.getValue("exif").single() as ImageExifMetadata
        session.close(); session.close(); value.close()
        assertFalse(source.closed)
        assertEquals("live", value.fields.model)
    }
    @Test fun parserFailureLeavesBorrowedInputOpen() {
        val source = Source()
        val error = IOException("parser failed")
        assertSame(error, assertThrows(IOException::class.java) { open(source, ImageExifProcessor { throw error }) })
        assertFalse(source.closed)
    }
    @Test fun shortReadsReachParserWithoutMaterialization() {
        val source = Source()
        open(source, ImageExifProcessor { assertEquals(100, it.read(ByteArray(100))); ImageExifFields() }).close()
        assertEquals(100, source.reads); assertFalse(source.closed)
    }
    @Test fun cancellationBeforeOpenDoesNotReadOrParse() {
        val source = Source()
        assertThrows(InterruptedIOException::class.java) {
            open(source, ImageExifProcessor { fail("must not parse"); ImageExifFields() }, ProcessorOptions(cancellation = CancellationToken { true }))
        }
        assertEquals(0, source.reads); assertFalse(source.closed)
    }
    @Test fun cancellationDuringReadPropagatesEvenIfBackendCatchesIt() {
        var cancelled = false
        val source = Source().apply { onRead = { cancelled = true; 1 } }
        assertThrows(InterruptedIOException::class.java) {
            open(source, ImageExifProcessor {
                try { it.read() } catch (_: IOException) {}
                ImageExifFields()
            }, ProcessorOptions(cancellation = CancellationToken { cancelled }))
        }
        assertEquals(1, source.reads); assertFalse(source.closed)
    }
    @Test fun sourceFailurePropagatesEvenIfBackendCatchesIt() {
        val error = IOException("provider failed")
        val source = Source().apply { onRead = { throw error } }
        assertSame(error, assertThrows(IOException::class.java) {
            open(source, ImageExifProcessor { try { it.read() } catch (_: IOException) {}; ImageExifFields() })
        })
    }
    @Test fun invalidSourceReadCannotBecomeAnEmptySuccess() {
        for (count in listOf(0, -2, 2)) {
            val source = Source().apply { onRead = { count } }
            assertThrows(IOException::class.java) {
                open(source, ImageExifProcessor { try { it.read() } catch (_: IOException) {}; ImageExifFields() })
            }
        }
    }
    @Test fun cancellationAfterBackendReturnsIsChecked() {
        var cancelled = false
        assertThrows(InterruptedIOException::class.java) {
            open(Source(), ImageExifProcessor { cancelled = true; ImageExifFields() }, ProcessorOptions(cancellation = CancellationToken { cancelled }))
        }
    }
    @Test fun unsupportedModeAndOptionsAreRejectedBeforeParsing() {
        val processor = ImageExifProcessor { fail("must not parse"); ImageExifFields() }
        for (options in listOf(ProcessorOptions(mode = FlowMode.PUSH), ProcessorOptions(mapOf("unknown" to "1"))))
            assertThrows(IOException::class.java) { open(Source(), processor, options) }
    }
    @Test fun wrongPortsCapabilitiesKindsAndMultiplicityAreRejected() {
        val processor = ImageExifProcessor { fail("must not parse"); ImageExifFields() }
        val source = Source()
        for (inputs in listOf(emptyMap(), mapOf("wrong" to listOf(source)), mapOf("encoded" to listOf(source, source)),
            mapOf("encoded" to listOf(Source(StreamMetadata()))), mapOf("encoded" to listOf(Source(StreamMetadata(ContentType(DataKind.TEXT), capabilities = setOf(Capability.READ)))))))
            assertThrows(IOException::class.java) { processor.open(inputs, ProcessorOptions()) }
    }
    @Test fun cancelledResourceIsNeverOpened() {
        val resource = object : SourceResource {
            override val metadata = StreamMetadata()
            override fun open(): ByteSource { fail("must not open"); return Source() }
        }
        assertThrows(InterruptedIOException::class.java) { ImageMetadata.read(resource, CancellationToken { true }) }
    }
    @Test fun ownedResourceHandleClosesWhenNegotiationFails() {
        val source = Source(StreamMetadata()) // Missing READ; rejected before platform parser.
        val resource = object : SourceResource { override val metadata = source.metadata; override fun open() = source }
        assertThrows(IOException::class.java) { ImageMetadata.read(resource) }
        assertTrue(source.closed)
    }
}
