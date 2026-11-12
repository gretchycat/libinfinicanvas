package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class AuthoringImageIdentityTest {
    private fun zip(mime: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            out.putNextEntry(ZipEntry("mimetype")); out.write(mime.toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("mergedimage.png")); out.write(byteArrayOf(1,2,3)); out.closeEntry()
        }
    }.toByteArray()
    @Test fun authoringZipMimetypeIsStrongerThanItsContainer() {
        assertEquals("ora", ImageSignatures.extension(zip("image/openraster")))
        assertEquals("kra", ImageSignatures.extension(zip("application/x-krita")))
        assertEquals("image/openraster", ImageSignatures.identify(zip("image/openraster"))?.mimeType)
        assertEquals("image/x-krita", ImageSignatures.identify(zip("application/x-krita"))?.mimeType)
    }
    @Test fun genericZipOrFakeExtensionIsNotPromotedToAnImage() {
        assertNull(ImageSignatures.identify(zip("application/zip"), "fake.ora"))
        assertNull(ImageSignatures.identify(byteArrayOf(0x50,0x4b,3,4), "fake.kra"))
    }
    @Test fun svgzIdentityNeedsOnlyItsBoundedRootEvenForLargeDocuments() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg'>" + "<path d='M0 0L1 1'/>".repeat(20000) + "</svg>"
        val bytes = ByteArrayOutputStream().also { GZIPOutputStream(it).use { out -> out.write(svg.toByteArray()) } }.toByteArray()
        assertEquals("image/svg+xml", ImageSignatures.identify(bytes, "image.svgz")?.mimeType)
        assertEquals("svg", ImageSignatures.extension(bytes))
    }
    @Test fun arbitraryGzipDoesNotAcquireSvgIdentity() {
        val bytes = ByteArrayOutputStream().also { GZIPOutputStream(it).use { out -> out.write("not an image".repeat(10000).toByteArray()) } }.toByteArray()
        assertNull(ImageSignatures.identify(bytes, "fake.svgz"))
    }
    @Test fun postscriptAndKritaApplicationAliasesAdvertiseThumbnailRoutes() {
        val formats = BuiltinCanvasCapabilityProvider().handlers().flatMap { it.describe().formats }
        assertTrue(formats.any { "application/postscript" in it.mimeTypes && "eps" in it.extensions })
        assertTrue(formats.any { "application/x-krita" in it.mimeTypes && "kra" in it.extensions })
    }
    @Test fun projectThumbnailCancellationRemainsObservable() {
        Thread.currentThread().interrupt()
        try { org.junit.Assert.assertThrows(java.io.InterruptedIOException::class.java) {
            LayeredImageDecoder.decodeThumbnail(zip("image/openraster").inputStream(), 64)
        } } finally { Thread.interrupted() }
    }
}
