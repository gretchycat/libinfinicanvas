package com.infinifolder.preview

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeCanvasCapabilitiesTest {
    @Test fun discoveryInvokesTheActualPortablePixmapParserWithoutAnApp() {
        val bytes="P6\n2 1\n255\n".toByteArray()+byteArrayOf(255.toByte(),0,0,0,255.toByte(),0)
        val source=object:SourceResource {
            override val metadata=StreamMetadata(name="pixels",length=bytes.size.toLong(),capabilities=setOf(Capability.READ))
            override fun open():ByteSource=InputStreamSource(bytes.inputStream(),metadata)
        }
        val selected=CanvasCapabilities.registry.query(Operations.DECODE,includeUnavailable=true).single {it.descriptor.id=="canvas.netpbm"}
        val probe=selected.handler.probe(CapabilityProbeContext(CapabilityResource(source.metadata,source=source),ProbeBudget(maxNanos=1_000_000_000)))
        assertEquals(MatchConfidence.VERIFIED,probe.confidence)
        assertEquals(setOf(Operations.IDENTIFY), probe.operations)
        assertEquals(AvailabilityState.UNAVAILABLE,selected.availability.state) // JVM has no Android bitmap runtime.
        ImageDecodeUtils.lastDecodedPixelsForTest=null
        selected.handler.service(ByteImageCapabilityHandler::class.java)!!.decode(bytes,64,"ppm")
        val pixels=ImageDecodeUtils.lastDecodedPixelsForTest!!
        assertEquals(2,pixels.width);assertEquals(1,pixels.height)
        assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt()),pixels.pixels)
        // The actual Android Bitmap/display result remains a device check; these are parser pixels.
    }
}
