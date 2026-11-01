package com.infinifolder.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class CachedImageCodecTest {
    @Test fun samplingStartsAtOneAndPreservesSmallArtwork() {
        assertEquals(1, CachedImageCodec.sampleSize(128, 128, 128))
        assertEquals(1, CachedImageCodec.sampleSize(256, 128, 128))
        assertEquals(4, CachedImageCodec.sampleSize(1024, 512, 128))
    }
    @Test fun samplingHandlesTinyTargetsAndMaximumDimensionsWithoutOverflow() {
        assertEquals(1 shl 30, CachedImageCodec.sampleSize(Int.MAX_VALUE, 1, 0))
        assertEquals(1, CachedImageCodec.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
