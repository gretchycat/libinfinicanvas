package com.infinifolder.preview

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class CanvasViewerSourceTest {
    @Test fun originalMatchingUriRetainsTheNativeDecodeRoute() {
        val input = StreamMetadata(provenance = Provenance(source = "content://images/original"))
        assertTrue(CanvasStreams.canUseNativeUri(input, "content://images/original"))
    }
    @Test fun unrelatedOrUnknownUriCannotReplaceTheCommonSource() {
        val input = StreamMetadata(provenance = Provenance(source = "content://images/original"))
        assertFalse(CanvasStreams.canUseNativeUri(input, "content://images/another"))
        assertFalse(CanvasStreams.canUseNativeUri(StreamMetadata(), "content://images/original"))
    }
    @Test fun transformedSourceMustDecodeItsOwnBytesDespiteAnOriginalUri() {
        val input = StreamMetadata(provenance = Provenance(source = "file:///original.jpg", transformations = listOf("image.transform")))
        assertFalse(CanvasStreams.canUseNativeUri(input, "file:///original.jpg"))
    }
}
