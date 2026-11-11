package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Test

class ImageSignaturesTest {
    @Test fun contentIdentifiesAnImageWithoutRelyingOnItsName() {
        // Complete one-pixel images, rather than prefixes which intentionally fail validation.
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB6sAAAAASUVORK5CYII=")
        val webp = java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        assertEquals("png", ImageSignatures.extension(png))
        assertEquals("webp", ImageSignatures.extension(webp))
        assertEquals("gif", ImageSignatures.extension(gif))
        assertNull(ImageSignatures.extension(png.copyOf(8)))
        assertNull(ImageSignatures.extension(gif.copyOf(6)))
    }
    @Test fun incompleteAndHeaderlessInputsDoNotGainInventedCertainty() {
        assertNull(ImageSignatures.extension("qoif".toByteArray()))
        assertNull(ImageSignatures.extension(byteArrayOf(1,2,3,4)))
        assertNull(ImageSignatures.extension(byteArrayOf()))
    }
    @Test fun samplingRetainsTheExistingViewerRule() {
        assertEquals(2, ImageDecodeUtils.sampleSize(8000, 6000, 2560, 2560))
        assertEquals(1, ImageDecodeUtils.sampleSize(4000, 1000, 2560, 2560))
        assertEquals(1, ImageDecodeUtils.sampleSize(192, 192, 2560, 2560))
    }
    @Test fun invalidTargetsAreRejectedBeforeSampling() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ImageDecodeUtils.sampleSize(100, 100, 0, 0) }
    }

}
