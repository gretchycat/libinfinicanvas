package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawImageExplorerTest {
    private fun pack(values:List<Int>,bits:Int):ByteArray {
        val bytes=ByteArray((values.size*bits+7)/8)
        values.forEachIndexed{x,v->repeat(bits){n->val at=x*bits+n;if(v and (1 shl (bits-1-n))!=0)bytes[at/8]=(bytes[at/8].toInt() or (1 shl (7-at%8))).toByte()}}
        return bytes
    }
    @Test fun allPackedIndexedDepthsDecodeActualIndexes(){
        for(bits in listOf(1,2,4,6,8)){
            val values=listOf(0,1,(1 shl bits)-1)
            val c=RawImageExplorer.Config(width=3,height=1,bits=bits)
            val decoded=RawImageExplorer.decode(pack(values,bits),c)
            val palette=RawImagePalettes.colors(c.palette,bits)
            assertArrayEquals(values.map{palette[it]}.toIntArray(),decoded.pixels)
        }
    }
    @Test fun sixBitEgaUsesAllSixtyFourColors(){
        val c=RawImageExplorer.Config(width=4,height=1,bits=6,palette=RawImagePalettes.Preset.EGA)
        val pixels=RawImageExplorer.decode(pack(listOf(0,4,32,63),6),c).pixels
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xffaa0000.toInt(),0xff550000.toInt(),0xffffffff.toInt()),pixels)
        assertEquals(64,RawImagePalettes.colors(c.palette,6).toSet().size)
    }
    @Test fun twelveSixteenTwentyFourThirtyTwoBitRgb(){
        assertEquals(0xffaabbcc.toInt(),RawImageExplorer.decode(byteArrayOf(0xab.toByte(),0xc0.toByte()),RawImageExplorer.Config(width=1,height=1,bits=12)).pixels[0])
        assertEquals(0xffff0000.toInt(),RawImageExplorer.decode(byteArrayOf(0,0xf8.toByte()),RawImageExplorer.Config(width=1,height=1,bits=16)).pixels[0])
        assertEquals(0xffff0000.toInt(),RawImageExplorer.decode(byteArrayOf(0xf8.toByte(),0),RawImageExplorer.Config(width=1,height=1,bits=16,littleEndian=false)).pixels[0])
        assertEquals(0xff112233.toInt(),RawImageExplorer.decode(byteArrayOf(0x33,0x22,0x11),RawImageExplorer.Config(width=1,height=1,bits=24,bgr=true)).pixels[0])
        assertEquals(0x44112233,RawImageExplorer.decode(byteArrayOf(0x11,0x22,0x33,0x44),RawImageExplorer.Config(width=1,height=1,bits=32,alpha=true)).pixels[0])
    }
    @Test fun rowPaddingBottomUpAndThumbnailSampling(){
        val bytes=byteArrayOf(1,2,0,0,3,4,0,0)
        val c=RawImageExplorer.Config(width=2,height=2,bits=8,stride=4,bottomUp=true)
        val palette=RawImagePalettes.colors(c.palette,8)
        assertArrayEquals(intArrayOf(palette[3],palette[4],palette[1],palette[2]),RawImageExplorer.decode(bytes,c).pixels)
        val sample=RawImageExplorer.decode(bytes,c,1)
        assertEquals(1,sample.width);assertEquals(1,sample.height);assertEquals(palette[3],sample.pixels[0])
    }
    @Test fun packbitsAndCountValueRleProducePixels(){
        val c=RawImageExplorer.Config(width=4,height=1,bits=8,compression=RawImageExplorer.Compression.RLE)
        val gray=RawImagePalettes.colors(c.palette,8)[42]
        assertArrayEquals(IntArray(4){gray},RawImageExplorer.decode(byteArrayOf((-3).toByte(),42),c).pixels)
        assertArrayEquals(IntArray(4){gray},RawImageExplorer.decode(byteArrayOf(4,42),c.copy(compression=RawImageExplorer.Compression.RLE_PAIRS)).pixels)
    }
    @Test fun tiffLzwDecodesRealClearAndLiteralCodes(){
        val codes=listOf(256,1,2,3,4,257)
        val compressed=pack(codes,9)
        val c=RawImageExplorer.Config(width=4,height=1,bits=8,compression=RawImageExplorer.Compression.LZW)
        val pal=RawImagePalettes.colors(c.palette,8)
        assertArrayEquals(intArrayOf(pal[1],pal[2],pal[3],pal[4]),RawImageExplorer.decode(compressed,c).pixels)
    }
    @Test fun truncatedRawAndInvalidRunsAreRejected(){
        val c=RawImageExplorer.Config(width=4,height=1,bits=8)
        assertThrows(IllegalArgumentException::class.java){RawImageExplorer.decode(byteArrayOf(1),c)}
        assertThrows(IllegalArgumentException::class.java){RawImageExplorer.decode(byteArrayOf(4),c.copy(compression=RawImageExplorer.Compression.RLE_PAIRS))}
        assertArrayEquals(IntArray(4){0xff010101.toInt()},RawImageExplorer.decode(byteArrayOf(8,1),c.copy(compression=RawImageExplorer.Compression.RLE_PAIRS)).pixels)
        assertThrows(IllegalArgumentException::class.java){c.copy(width=4096,height=4096).validate()}
    }
    @Test fun bmpAutoDetectionGetsPaletteStrideAndOrientationFromBytes(){
        val bytes=ByteArray(70)
        val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0]=66;bytes[1]=77
        header.putInt(10,54);header.putInt(14,40);header.putInt(18,2);header.putInt(22,2);header.putShort(26,1);header.putShort(28,24)
        val detected=RawImageExplorer.detect(bytes,bytes.size.toLong()).single()
        assertTrue(detected.certain);assertEquals(54L,detected.config.offset);assertEquals(8,detected.config.stride)
        assertTrue(detected.config.bottomUp);assertTrue(detected.config.bgr)
    }
    @Test fun headerlessCandidatesAreLabeledGuesses(){
        val choices=RawImageExplorer.detect(byteArrayOf(),8000)
        assertTrue(choices.isNotEmpty());assertTrue(choices.none{it.certain})
        assertTrue(choices.any{it.config.width==320&&it.config.height==200&&it.config.bits==1})
    }
    @Test fun retroAndArtifactPresetsAreDeterministic(){
        for(preset in RawImagePalettes.Preset.entries)for(bits in listOf(1,2,4,6,8)){
            val first=RawImagePalettes.colors(preset,bits)
            assertTrue(first.isNotEmpty());assertArrayEquals(first,RawImagePalettes.colors(preset,bits))
            assertTrue(first.all{(it ushr 24)==255})
        }
        assertEquals(0xff880000.toInt(),RawImagePalettes.colors(RawImagePalettes.Preset.C64,4)[2])
        val phases=listOf(RawImagePalettes.Preset.CGA_NTSC_0,RawImagePalettes.Preset.CGA_NTSC_90,RawImagePalettes.Preset.CGA_NTSC_180,RawImagePalettes.Preset.CGA_NTSC_270)
        assertEquals(4,phases.map{RawImagePalettes.colors(it,4)[1]}.toSet().size)
    }
    @Test fun artifactModeUsesNeighborBitsForOneBitData(){
        val config=RawImageExplorer.Config(width=8,height=1,bits=1,palette=RawImagePalettes.Preset.CGA_NTSC_0)
        val pixels=RawImageExplorer.decode(byteArrayOf(0x1f),config).pixels
        assertTrue(pixels.take(4).distinct().size==1);assertEquals(0xffffffff.toInt(),pixels[7])
        assertNotEquals(0xff000000.toInt(),pixels[0])
    }
    @Test fun arbitraryWidthsUseOnlyCompleteRowsAtEveryDepth(){
        for(bits in RawImageExplorer.depths)for(bytes in listOf(1L,2,7,128,8000,16000,48000,64000,192000)){
            val choices=RawImageExplorer.dimensions(bytes,bits)
            assertEquals(choices.sortedBy{it.width},choices)
            assertEquals(choices.size,choices.map{it.width}.distinct().size)
            for(choice in choices){
                val config=RawImageExplorer.Config(width=choice.width,height=choice.height,bits=bits)
                config.validate();assertTrue(config.byteCount<=bytes)
                assertTrue(config.byteCount>0)
                assertEquals(config.byteCount*8,((choice.width.toLong()*bits+7)/8*8)*choice.height)
                if(choice.height<4096 && choice.width.toLong()*(choice.height+1)<=RawImageExplorer.MAX_PIXELS &&
                    config.rowStride.toLong()*(choice.height+1)<=RawImageExplorer.MAX_BYTES)
                    assertTrue(config.byteCount+config.rowStride>bytes)
            }
        }
        assertTrue(RawImageExplorer.dimensions(16000,2).contains(RawImageExplorer.Dimensions(320,200)))
        assertTrue(RawImageExplorer.dimensions(16000,1).contains(RawImageExplorer.Dimensions(640,200)))
        assertTrue(RawImageExplorer.dimensions(48000,6).contains(RawImageExplorer.Dimensions(320,200)))
    }
    @Test fun dimensionChoicesRespectPaddingStrideAndSafetyLimits(){
        val padded=RawImageExplorer.dimensions(32,24,stride=16)
        assertEquals((1..5).map{RawImageExplorer.Dimensions(it,2)},padded)
        assertEquals((1..5).map{RawImageExplorer.Dimensions(it,1)},RawImageExplorer.dimensions(31,24,stride=16))
        assertTrue(RawImageExplorer.dimensions(0,8).isEmpty())
        assertTrue(RawImageExplorer.dimensions(1,24).isEmpty())
        assertTrue(RawImageExplorer.dimensions(4099,8).contains(RawImageExplorer.Dimensions(319,12)))
        assertTrue(RawImageExplorer.dimensions(16384,2).contains(RawImageExplorer.Dimensions(320,204)))
        for(choice in RawImageExplorer.dimensions(Long.MAX_VALUE,32)){
            val c=RawImageExplorer.Config(width=choice.width,height=choice.height,bits=32)
            c.validate();assertTrue(c.byteCount<=RawImageExplorer.MAX_BYTES)
        }
        for(choice in RawImageExplorer.dimensions(RawImageExplorer.MAX_BYTES.toLong(),32))
            assertTrue(choice.width.toLong()*choice.height<=RawImageExplorer.MAX_PIXELS)
    }
    @Test fun compressedSizesMatchRealDecoderOutputAndRejectIncompleteStreams(){
        val cases=listOf(
            RawImageExplorer.Compression.RLE to byteArrayOf((-3).toByte(),42),
            RawImageExplorer.Compression.RLE_PAIRS to byteArrayOf(4,42),
            RawImageExplorer.Compression.LZW to pack(listOf(256,42,42,42,42,257),9))
        for((compression,bytes)in cases){
            val size=RawImageExplorer.decodedSize(bytes,compression)
            assertEquals(4L,size)
            for(choice in RawImageExplorer.dimensions(size,8)){
                val image=RawImageExplorer.decode(bytes,RawImageExplorer.Config(width=choice.width,height=choice.height,bits=8,compression=compression))
                assertArrayEquals(IntArray(choice.width*choice.height){0xff2a2a2a.toInt()},image.pixels)
            }
        }
        assertThrows(IllegalArgumentException::class.java){RawImageExplorer.decodedSize(byteArrayOf(3,1),RawImageExplorer.Compression.RLE)}
        assertThrows(IllegalArgumentException::class.java){RawImageExplorer.decodedSize(byteArrayOf(4),RawImageExplorer.Compression.RLE_PAIRS)}
        assertThrows(IllegalArgumentException::class.java){RawImageExplorer.decodedSize(pack(listOf(256,1,2),9),RawImageExplorer.Compression.LZW)}
        val oversized=ByteArray((RawImageExplorer.MAX_BYTES/256+1)*2)
        assertThrows(IllegalArgumentException::class.java){RawImageExplorer.decodedSize(oversized,RawImageExplorer.Compression.RLE_PAIRS)}
    }
    @Test fun paletteChoicesMatchDepthAndIncludeAllSixCgaFourColorVariants(){
        for(bits in RawImageExplorer.depths){
            val choices=RawImagePalettes.available(bits)
            if(bits>8)assertTrue(choices.isEmpty())
            for(preset in choices)if(!RawImagePalettes.isArtifact(preset))
                assertEquals(1 shl bits,RawImagePalettes.colors(preset,bits).size)
        }
        val cgafour=RawImagePalettes.available(2).filter{it!=RawImagePalettes.Preset.MONO&&!RawImagePalettes.isArtifact(it)}
        assertEquals(6,cgafour.size)
        assertEquals(6,cgafour.map{RawImagePalettes.colors(it,2).toList()}.distinct().size)
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xff00aa00.toInt(),0xffaa0000.toInt(),0xffaa5500.toInt()),
            RawImagePalettes.colors(RawImagePalettes.Preset.CGA_0_LOW,2))
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xff55ff55.toInt(),0xffff5555.toInt(),0xffffff55.toInt()),
            RawImagePalettes.colors(RawImagePalettes.Preset.CGA_0_HIGH,2))
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xff00aaaa.toInt(),0xffaa0000.toInt(),0xffaaaaaa.toInt()),
            RawImagePalettes.colors(RawImagePalettes.Preset.CGA_5_LOW,2))
        assertTrue(RawImagePalettes.Preset.EGA in RawImagePalettes.available(4))
        assertTrue(RawImagePalettes.Preset.EGA in RawImagePalettes.available(6))
        assertFalse(RawImagePalettes.Preset.EGA in RawImagePalettes.available(8))
        assertFalse(RawImagePalettes.Preset.VGA in RawImagePalettes.available(4))
    }
    @Test fun shortEmbeddedPaletteRejectsMissingIndexesInsteadOfWrappingColors(){
        val c=RawImageExplorer.Config(width=2,height=1,bits=8,filePalette=listOf(0xff000000.toInt(),0xffffffff.toInt()))
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xffffffff.toInt()),RawImageExplorer.decode(byteArrayOf(0,1),c).pixels)
        assertThrows(IllegalArgumentException::class.java){RawImageExplorer.decode(byteArrayOf(0,2),c)}
    }
    @Test fun dosEgaPaletteAndPackedPixelsMatchEveryStandardColorIndex(){
        // DOS/BIOS indexes 0–15: brown at 6, dark gray at 8, bright colors at 9–15.
        val expected=intArrayOf(0x000000,0x0000aa,0x00aa00,0x00aaaa,0xaa0000,0xaa00aa,0xaa5500,0xaaaaaa,
            0x555555,0x5555ff,0x55ff55,0x55ffff,0xff5555,0xff55ff,0xffff55,0xffffff)
            .map{it or 0xff000000.toInt()}.toIntArray()
        assertArrayEquals(expected,RawImagePalettes.colors(RawImagePalettes.Preset.EGA,4))
        val packed=byteArrayOf(0x01,0x23,0x45,0x67,0x89.toByte(),0xab.toByte(),0xcd.toByte(),0xef.toByte())
        val c=RawImageExplorer.Config(width=16,height=1,bits=4,palette=RawImagePalettes.Preset.EGA)
        assertArrayEquals(expected,RawImageExplorer.decode(packed,c).pixels)
    }
    @Test fun detectionCombinesStandardGeometryDepthAndCurrentDepthHint(){
        data class Expected(val bytes:Long,val bits:Int,val width:Int,val height:Int,val palette:RawImagePalettes.Preset)
        for(e in listOf(
            Expected(16000,2,320,200,RawImagePalettes.Preset.CGA),
            Expected(16000,1,640,200,RawImagePalettes.Preset.CGA_MONO),
            Expected(32000,4,320,200,RawImagePalettes.Preset.EGA),
            Expected(112000,4,640,350,RawImagePalettes.Preset.EGA),
            Expected(64000,8,320,200,RawImagePalettes.Preset.VGA_STANDARD))){
            val detected=RawImageExplorer.detect(byteArrayOf(),e.bytes,e.bits).first()
            assertFalse(detected.certain);assertTrue(detected.description.startsWith("Guess:"))
            assertEquals(e.bits,detected.config.bits);assertEquals(e.width,detected.config.width)
            assertEquals(e.height,detected.config.height);assertEquals(e.palette,detected.config.palette)
        }
    }
    @Test fun standardCgaGeometryAllowsSmallTrailingPaddingWithoutRestrictingManualWidths(){
        val detected=RawImageExplorer.detect(byteArrayOf(),16384,2).first()
        assertEquals(320,detected.config.width);assertEquals(200,detected.config.height)
        assertEquals(2,detected.config.bits);assertTrue(detected.description.contains("384 trailing bytes"))
        val widths=RawImageExplorer.dimensions(16384,2).map{it.width}
        assertTrue(319 in widths);assertTrue(320 in widths);assertTrue(321 in widths)
    }
    @Test fun dosVgaMatchesIndependentBiosDacReferenceAtAll256Indexes(){
        // This fixture contains reference DAC facts, independently of the hue-ring implementation.
        val input=checkNotNull(javaClass.getResourceAsStream("/palettes/dos-vga-rgb6.hex"))
        val expected=input.bufferedReader().useLines{lines->lines.filter{it.isNotBlank()&&!it.startsWith("#")}.map{hex->
            fun channel(at:Int)=(hex.substring(at,at+2).toInt(16)*255.0/63).let{kotlin.math.round(it).toInt()}
            0xff000000.toInt() or (channel(0) shl 16) or (channel(2) shl 8) or channel(4)
        }.toList().toIntArray()}
        assertEquals(256,expected.size)
        val palette=RawImagePalettes.colors(RawImagePalettes.Preset.VGA_STANDARD,8)
        assertArrayEquals(expected,palette)
        val bytes=ByteArray(256){it.toByte()}
        val image=RawImageExplorer.decode(bytes,RawImageExplorer.Config(width=16,height=16,bits=8,palette=RawImagePalettes.Preset.VGA_STANDARD))
        assertArrayEquals(expected,image.pixels)
        assertEquals(0xff0000ff.toInt(),palette[32]);assertEquals(0xff141414.toInt(),palette[17])
        assertTrue(palette.takeLast(8).all{it==0xff000000.toInt()})
        assertEquals(0xff000000.toInt(),RawImagePalettes.colors(RawImagePalettes.Preset.VGA,8)[32])
    }
}
