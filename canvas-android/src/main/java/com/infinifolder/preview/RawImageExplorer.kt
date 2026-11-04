package com.infinifolder.preview

import kotlin.math.*

/** Explicit raw raster interpretations; no filename/MIME guesses or filesystem access. */
object RawImageExplorer {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_PIXELS = 4 * 1024 * 1024
    val depths = listOf(1, 2, 4, 6, 8, 12, 16, 24, 32)
    enum class Compression(val title: String) { RAW("Raw"), RLE("RLE: PackBits"), RLE_PAIRS("RLE: count/value"), LZW("LZW: TIFF/MSB") }
    data class Config(val offset: Long = 0, val width: Int = 128, val height: Int = 128, val bits: Int = 8,
        val palette: RawImagePalettes.Preset = RawImagePalettes.Preset.MONO,
        val compression: Compression = Compression.RAW, val stride: Int = 0,
        val littleEndian: Boolean = true, val bgr: Boolean = false, val bottomUp: Boolean = false,
        val alpha: Boolean = false, val filePalette: List<Int>? = null) {
        val rowBytes get() = (width * bits + 7) / 8
        val rowStride get() = if (stride == 0) rowBytes else stride
        val byteCount get() = rowStride.toLong() * height
        fun validate() {
            require(offset >= 0 && width in 1..4096 && height in 1..4096 && width.toLong() * height <= MAX_PIXELS) { "Use 1–4096 pixels per side, up to 4 megapixels" }
            require(bits in depths && stride >= 0 && rowStride >= rowBytes && byteCount <= MAX_BYTES) { "Invalid pixel depth/stride or more than 16 MiB of image data" }
            require(filePalette == null || filePalette.size in 1..256) { "Invalid file palette" }
        }
    }
    data class Raster(val width: Int, val height: Int, val pixels: IntArray)
    data class Detection(val config: Config, val description: String, val certain: Boolean)
    data class Dimensions(val width: Int, val height: Int)

    /** Every width that can hold a complete row, paired with its maximum safe height. */
    fun dimensions(bytes: Long, bits: Int, stride: Int = 0): List<Dimensions> {
        require(bits in depths && stride >= 0)
        if (bytes <= 0) return emptyList()
        return (1..4096).mapNotNull { width ->
            val packed = (width * bits + 7) / 8
            val row = if (stride == 0) packed else stride
            if (row < packed) return@mapNotNull null
            val height = minOf(bytes / row, MAX_BYTES.toLong() / row, MAX_PIXELS.toLong() / width, 4096L)
            if (height < 1) null else Dimensions(width, height.toInt())
        }
    }

    /** Measures the actual decoded stream; encoded byte length is not an image pixel budget. */
    fun decodedSize(source: ByteArray, compression: Compression): Long {
        require(source.size <= MAX_BYTES) { "Compressed input exceeds 16 MiB" }
        if (compression == Compression.RAW) return source.size.toLong()
        if (compression == Compression.LZW) return lzw(source).size.toLong()
        var at = 0
        var size = 0L
        while (at < source.size) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Image sizing cancelled")
            if (compression == Compression.RLE_PAIRS) {
                require(at + 1 < source.size) { "Incomplete count/value RLE stream" }
                size += (source[at].toInt() and 255).let { if (it == 0) 256 else it }
                at += 2
            } else {
                val code = source[at++].toInt()
                if (code >= 0) {
                    val count = code + 1
                    require(count <= source.size - at) { "Incomplete PackBits literal" }
                    size += count; at += count
                } else if (code != -128) {
                    require(at < source.size) { "Incomplete PackBits run" }
                    size += 1 - code; at++
                }
            }
            require(size <= MAX_BYTES) { "Decoded image exceeds 16 MiB" }
        }
        return size
    }
    private fun lzw(source:ByteArray):ByteArray {
        require(source.size<=MAX_BYTES){"Compressed input exceeds 16 MiB"}
        return TiffAndRawImageDecoder.decodeTiffLzw(source,0,source.size,MAX_BYTES+1,requireEnd=true)
            .also{require(it.size<=MAX_BYTES){"Decoded image exceeds 16 MiB"}}
    }
    fun decode(source: ByteArray, config: Config, target: Int = 0): Raster {
        return decode(source,config,target,cropSquare=false)
    }
    /** Thin raw images fill an icon with a central square before sampling loses narrow-side detail. */
    fun decodeThumbnail(source:ByteArray,config:Config,target:Int):Raster {
        require(target>0){"Thumbnail size must be positive"}
        val thin=maxOf(config.width,config.height)>=2*minOf(config.width,config.height)
        return decode(source,config,target,cropSquare=thin)
    }
    private fun decode(source:ByteArray,config:Config,target:Int,cropSquare:Boolean):Raster {
        config.validate()
        val expected = config.byteCount.toInt()
        val data = when (config.compression) {
            Compression.RAW -> { require(source.size >= expected) { "Not enough bytes for this resolution/depth" }; source }
            Compression.RLE -> ImageDecodeUtils.decodePackBits(source, 0, decodedSize(source,config.compression).toInt()).first
            Compression.LZW -> lzw(source).also {
                require(it.size >= expected) { "Incomplete TIFF-style LZW stream; try another offset or compression" }
            }
            Compression.RLE_PAIRS -> {
                val out = ByteArray(decodedSize(source,config.compression).toInt()); var at = 0; var pos = 0
                while (at < out.size) {
                    require(pos + 1 < source.size) { "Incomplete count/value RLE stream" }
                    val count = (source[pos++].toInt() and 255).let { if (it == 0) 256 else it }
                    require(count <= out.size - at) { "RLE run exceeds the requested image size" }
                    out.fill(source[pos++], at, at + count); at += count
                }
                out
            }
        }
        require(data.size>=expected){"Not enough decoded bytes for this resolution/depth"}
        val cropWidth=if(cropSquare)minOf(config.width,config.height)else config.width
        val cropHeight=if(cropSquare)cropWidth else config.height
        val left=(config.width-cropWidth)/2
        val top=(config.height-cropHeight)/2
        val step = if (target <= 0) 1 else maxOf(1, ceil(maxOf(cropWidth,cropHeight).toDouble() / target).toInt())
        val width = (cropWidth + step - 1) / step
        val height = (cropHeight + step - 1) / step
        val pixels = IntArray(width * height)
        val palette = config.filePalette?.toIntArray() ?: RawImagePalettes.colors(config.palette, config.bits)
        fun bitsAt(row: Int, bit: Int, count: Int): Int {
            var value = 0
            for (n in 0 until count) value = (value shl 1) or ((data[row + (bit + n) / 8].toInt() ushr (7 - (bit + n) % 8)) and 1)
            return value
        }
        for (y in 0 until height) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Image render cancelled")
            val sy = top+y * step
            val row = (if (config.bottomUp) config.height - 1 - sy else sy) * config.rowStride
            for (x in 0 until width) {
                val sx = left+x * step
                val color = when (config.bits) {
                    1, 2, 4, 6, 8 -> {
                        val index=if(config.bits<=2&&config.filePalette==null&&RawImagePalettes.isArtifact(config.palette)){
                            val groupPixels=4/config.bits
                            val first=(sx/groupPixels)*groupPixels
                            var pattern=0
                            for(n in 0 until groupPixels)pattern=(pattern shl config.bits) or
                                (if(first+n<config.width)bitsAt(row,(first+n)*config.bits,config.bits)else 0)
                            pattern
                        }else bitsAt(row,sx*config.bits,config.bits)
                        require(config.filePalette==null || index<palette.size) { "Pixel index is outside the file palette" }
                        palette[index%palette.size]
                    }
                    12 -> {
                        val value = bitsAt(row, sx * 12, 12)
                        rgb(((value ushr 8) and 15) * 17, ((value ushr 4) and 15) * 17, (value and 15) * 17, config.bgr)
                    }
                    16 -> {
                        val p = row + sx * 2
                        val v = if (config.littleEndian) ImageDecodeUtils.u16le(data, p) else ImageDecodeUtils.u16be(data, p)
                        rgb(((v ushr 11) and 31) * 255 / 31, ((v ushr 5) and 63) * 255 / 63, (v and 31) * 255 / 31, config.bgr)
                    }
                    else -> {
                        val p = row + sx * (config.bits / 8)
                        val a = if (config.bits == 32 && config.alpha) data[p + 3].toInt() and 255 else 255
                        (rgb(data[p].toInt() and 255, data[p + 1].toInt() and 255, data[p + 2].toInt() and 255, config.bgr) and 0xffffff) or (a shl 24)
                    }
                }
                pixels[y * width + x] = color
            }
        }
        return Raster(width, height, pixels)
    }
    private fun rgb(r: Int, g: Int, b: Int, swap: Boolean = false) = 0xff000000.toInt() or
        ((if (swap) b else r) shl 16) or (g shl 8) or (if (swap) r else b)

    /** BMP headers provide facts; headerless candidates are labeled hypotheses for user selection. */
    fun detect(prefix: ByteArray, total: Long, preferredBits: Int? = null): List<Detection> {
        if (prefix.size >= 54 && prefix[0] == 66.toByte() && prefix[1] == 77.toByte()) {
            val dib = ImageDecodeUtils.u32le(prefix, 14)
            val w = ImageDecodeUtils.s32le(prefix, 18); val h = ImageDecodeUtils.s32le(prefix, 22)
            val bits = ImageDecodeUtils.u16le(prefix, 28)
            val compression = ImageDecodeUtils.u32le(prefix, 30)
            if (dib >= 40 && ImageDecodeUtils.u16le(prefix,26)==1 && w in 1..4096 && h != Int.MIN_VALUE && abs(h) in 1..4096 && bits in listOf(1, 4, 8, 16, 24, 32) && compression == 0L) {
                // BI_RGB 16-bit uses RGB555, which this explorer does not silently treat as RGB565.
                if (bits == 16) return emptyList()
                val paletteStart = 14L + dib
                val count = if (bits <= 8) (ImageDecodeUtils.u32le(prefix, 46).takeIf { it > 0 } ?: (1L shl bits)).coerceAtMost(256).toInt() else 0
                val palette = if (count > 0 && paletteStart + count * 4 <= prefix.size) List(count) { i ->
                    val p = paletteStart.toInt() + i * 4
                    rgb(prefix[p + 2].toInt() and 255, prefix[p + 1].toInt() and 255, prefix[p].toInt() and 255)
                } else null
                val config = Config(ImageDecodeUtils.u32le(prefix, 10), w, abs(h), bits, stride = ((w * bits + 31) / 32) * 4,
                    bgr = true, bottomUp = h > 0, filePalette = palette)
                if ((bits>8 || palette!=null) && config.offset>=paletteStart+count*4 &&
                    runCatching { config.validate(); config.offset <= total && config.byteCount <= total - config.offset }.getOrDefault(false))
                    return listOf(Detection(config, "BMP header: ${w}×${abs(h)}, $bits bpp", true))
            }
        }
        data class Guess(val detection:Detection,val standard:Boolean,val remainder:Long,val priority:Int)
        val candidates = ArrayList<Guess>()
        fun candidate(width:Int,height:Int,bits:Int,palette:RawImagePalettes.Preset,standard:Boolean,priority:Int,label:String){
            val config=Config(width=width,height=height,bits=bits,palette=palette)
            if(runCatching{config.validate()}.isFailure || config.byteCount>total)return
            val remainder=total-config.byteCount
            if(standard && remainder>maxOf(512L,total/64))return
            val colors=if(bits<=8)" (${1 shl bits} colors)" else ""
            val tail=if(remainder==0L)"exact size" else "$remainder trailing bytes"
            candidates.add(Guess(Detection(config,"Guess: $label ${width}×${height}, $bits bpp$colors · $tail",false),standard,remainder,priority))
        }
        // Mode geometry and indexed depth are considered together; size alone cannot prove a format.
        candidate(320,200,2,RawImagePalettes.Preset.CGA,true,0,"CGA")
        candidate(640,200,1,RawImagePalettes.Preset.CGA_MONO,true,1,"CGA")
        for((w,h)in listOf(320 to 200,640 to 200,640 to 350,640 to 480))
            candidate(w,h,4,RawImagePalettes.Preset.EGA,true,if(w==320||h>=350)0 else 1,"DOS EGA/VGA")
        for((w,h)in listOf(320 to 200,320 to 240,640 to 480,800 to 600,1024 to 768))
            candidate(w,h,8,RawImagePalettes.Preset.VGA_STANDARD,true,0,"VGA/indexed")
        for((w,h)in listOf(320 to 200,320 to 240,640 to 480,800 to 600,1024 to 768,1280 to 720,1280 to 1024,1920 to 1080))
            for(bits in listOf(6,12,16,24,32))candidate(w,h,bits,
                if(bits==6)RawImagePalettes.Preset.EGA else RawImagePalettes.Preset.MONO,true,2,if(bits==6)"Indexed" else "RGB")
        for (bits in depths) for (width in listOf(320, 640, 256, 128, 512, 64, 1024)) {
            val row = (width * bits + 7) / 8
            if (total > 0 && total % row == 0L && total / row in 1..4096) {
                candidate(width,(total/row).toInt(),bits,when(bits){
                    1->RawImagePalettes.Preset.CGA_MONO;2->RawImagePalettes.Preset.CGA
                    4,6->RawImagePalettes.Preset.EGA;8->RawImagePalettes.Preset.VGA_STANDARD;else->RawImagePalettes.Preset.MONO
                },false,3,"Raw")
            }
        }
        return candidates.sortedWith(compareBy<Guess>{!it.standard}.thenBy{it.remainder!=0L}
            .thenBy{if(it.detection.config.bits==preferredBits)0 else 1}.thenBy{it.priority}
            .thenBy{it.remainder.toDouble()/total.coerceAtLeast(1)}
            .thenBy{abs(ln(it.detection.config.width.toDouble()/it.detection.config.height/(4.0/3)))})
            .distinctBy{it.detection.config}.take(32).map{it.detection}
    }
}

object RawImagePalettes {
    enum class Preset(val title: String) {
        MONO("Monochrome / grayscale"), CGA("CGA palette 1: high intensity"), EGA("EGA (16 / 64 colors)"), VGA("VGA (RGBI + color cube)"),
        C64("Commodore 64"), APPLE2("Apple II"),
        CGA_NTSC_0("CGA NTSC artifact: phase 0° (approx.)"), CGA_NTSC_90("CGA NTSC artifact: phase 90° (approx.)"),
        CGA_NTSC_180("CGA NTSC artifact: phase 180° (approx.)"), CGA_NTSC_270("CGA NTSC artifact: phase 270° (approx.)"),
        CGA_MONO("CGA monochrome"), CGA_RGBI("CGA RGBI (16 colors)"),
        CGA_0_LOW("CGA palette 0: low intensity"), CGA_0_HIGH("CGA palette 0: high intensity"),
        CGA_1_LOW("CGA palette 1: low intensity"),
        CGA_5_LOW("CGA mode 5: low intensity"), CGA_5_HIGH("CGA mode 5: high intensity"),
        VGA_STANDARD("VGA (DOS standard, 256 colors)")
    }
    fun isArtifact(preset:Preset)=when(preset){
        Preset.CGA_NTSC_0,Preset.CGA_NTSC_90,Preset.CGA_NTSC_180,Preset.CGA_NTSC_270 -> true
        else -> false
    }
    fun title(preset:Preset,bits:Int)=when {
        preset==Preset.EGA&&bits==4 -> "DOS EGA (16 colors)"
        preset==Preset.EGA&&bits==6 -> "EGA hardware (64 colors)"
        else -> preset.title
    }
    fun available(bits:Int):List<Preset> = if(bits==8)listOf(Preset.MONO,Preset.VGA_STANDARD,Preset.VGA) else Preset.entries.filter { when(it) {
        Preset.MONO -> bits in listOf(1,2,4,6,8)
        Preset.CGA_MONO -> bits==1
        Preset.CGA, Preset.CGA_0_LOW, Preset.CGA_0_HIGH, Preset.CGA_1_LOW, Preset.CGA_5_LOW, Preset.CGA_5_HIGH -> bits==2
        Preset.CGA_RGBI, Preset.C64, Preset.APPLE2 -> bits==4
        Preset.EGA -> bits==4 || bits==6
        Preset.VGA, Preset.VGA_STANDARD -> bits==8
        else -> bits in listOf(1,2,4)
    } }
    fun c64() = intArrayOf(0x000000, 0xffffff, 0x880000, 0xaaffee, 0xcc44cc, 0x00cc55, 0x0000aa, 0xeeee77,
        0xdd8855, 0x664400, 0xff7777, 0x333333, 0x777777, 0xaaff66, 0x0088ff, 0xbbbbbb).map { it or 0xff000000.toInt() }.toIntArray()
    private fun ega(index: Int): Int {
        val r = (if (index and 4 != 0) 170 else 0) + (if (index and 32 != 0) 85 else 0)
        val g = (if (index and 2 != 0) 170 else 0) + (if (index and 16 != 0) 85 else 0)
        val b = (if (index and 1 != 0) 170 else 0) + (if (index and 8 != 0) 85 else 0)
        return 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    }
    fun colors(preset: Preset, bits: Int): IntArray {
        val count = 1 shl bits.coerceIn(1, 8)
        val ega16 = intArrayOf(0, 1, 2, 3, 4, 5, 20, 7, 56, 57, 58, 59, 60, 61, 62, 63).map(::ega).toIntArray()
        return when (preset) {
            Preset.MONO -> IntArray(count) { val v = it * 255 / (count - 1); 0xff000000.toInt() or (v * 0x010101) }
            Preset.CGA -> if (bits == 1) intArrayOf(ega16[0], ega16[15]) else if (bits == 2) intArrayOf(ega16[0], ega16[11], ega16[13], ega16[15]) else ega16
            Preset.CGA_MONO -> intArrayOf(ega16[0],ega16[15])
            Preset.CGA_RGBI -> ega16
            Preset.CGA_0_LOW -> intArrayOf(ega16[0],ega16[2],ega16[4],ega16[6])
            Preset.CGA_0_HIGH -> intArrayOf(ega16[0],ega16[10],ega16[12],ega16[14])
            Preset.CGA_1_LOW -> intArrayOf(ega16[0],ega16[3],ega16[5],ega16[7])
            Preset.CGA_5_LOW -> intArrayOf(ega16[0],ega16[3],ega16[4],ega16[7])
            Preset.CGA_5_HIGH -> intArrayOf(ega16[0],ega16[11],ega16[12],ega16[15])
            Preset.EGA -> if (bits <= 4) ega16 else IntArray(64, ::ega)
            Preset.VGA_STANDARD -> dosVga(ega16)
            // Standard VGA RGBI entries, a grayscale ramp, a 6×6×6 cube, then black spare entries.
            Preset.VGA -> IntArray(256) { i -> when {
                i < 16 -> ega16[i]
                i < 32 -> (0xff000000.toInt() or (((i - 16) * 255 / 15) * 0x010101))
                i < 248 -> { val n = i - 32; 0xff000000.toInt() or ((n / 36 * 51) shl 16) or ((n / 6 % 6 * 51) shl 8) or (n % 6 * 51) }
                else -> 0xff000000.toInt()
            } }
            Preset.C64 -> c64()
            // Indexed lo-res NTSC reference colors; not Apple II memory-layout emulation.
            Preset.APPLE2 -> intArrayOf(0x000000,0x9d0966,0x2a2ae5,0xc734ff,0x008000,0x808080,0x0da1ff,0xaaaaff,
                0x555500,0xf25e00,0xc0c0c0,0xff89e5,0x38cb00,0xd5d51a,0x62f699,0xffffff).map { it or 0xff000000.toInt() }.toIntArray()
            else -> {
                val phase = (preset.ordinal - Preset.CGA_NTSC_0.ordinal) * PI / 2
                IntArray(16) { pattern ->
                    var y = 0.0; var i = 0.0; var q = 0.0
                    for (n in 0..3) { val v = ((pattern ushr (3 - n)) and 1).toDouble(); y += v / 4; i += v * cos(n * PI / 2 + phase) / 2; q += v * sin(n * PI / 2 + phase) / 2 }
                    fun component(v: Double) = (v * 255).roundToInt().coerceIn(0,255)
                    0xff000000.toInt() or (component(y + .956 * i + .621 * q) shl 16) or
                        (component(y - .272 * i - .647 * q) shl 8) or component(y - 1.106 * i + 1.703 * q)
                }
            }
        }
    }

    /** BIOS default DAC data: RGBI, 16 grays, nine 24-color hue rings, eight black spare slots.
     * Palette facts: DOSBox Staging src/ints/int10_modes.cpp, palette.vga.
     * The legacy VGA enum remains the old RGBI/cube preset for saved-metadata compatibility. */
    private fun dosVga(ega16:IntArray):IntArray {
        val result=IntArray(256){0xff000000.toInt()}
        ega16.copyInto(result)
        fun channel(value:Int)=(value*255+31)/63
        fun rgb(r:Int,g:Int,b:Int)=0xff000000.toInt() or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        val grays=intArrayOf(0,5,8,11,14,17,20,24,28,32,36,40,45,50,56,63)
        grays.forEachIndexed{n,v->result[n+16]=rgb(v,v,v)}
        val ramps=arrayOf(intArrayOf(0,16,31,47,63),intArrayOf(31,39,47,55,63),intArrayOf(45,49,54,58,63),
            intArrayOf(0,7,14,21,28),intArrayOf(14,17,21,24,28),intArrayOf(20,22,24,26,28),
            intArrayOf(0,4,8,12,16),intArrayOf(8,10,12,14,16),intArrayOf(11,12,13,15,16))
        var at=32
        for(ramp in ramps){
            val low=ramp[0];val high=ramp[4]
            repeat(4){result[at++]=rgb(ramp[it],low,high)}
            repeat(4){result[at++]=rgb(high,low,ramp[4-it])}
            repeat(4){result[at++]=rgb(high,ramp[it],low)}
            repeat(4){result[at++]=rgb(ramp[4-it],high,low)}
            repeat(4){result[at++]=rgb(low,high,ramp[it])}
            repeat(4){result[at++]=rgb(low,ramp[4-it],high)}
        }
        return result
    }
}
