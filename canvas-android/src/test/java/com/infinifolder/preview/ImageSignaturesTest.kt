package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Test

class ImageSignaturesTest {
    @Test fun contentIdentifiesAnImageWithoutRelyingOnItsName() {
        val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        assertEquals("png", ImageSignatures.extension(png))
        assertEquals("webp", ImageSignatures.extension("RIFFxxxxWEBP".toByteArray()))
        assertEquals("gif", ImageSignatures.extension("GIF89a".toByteArray()))
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
