package com.infinifolder.preview

import android.graphics.Bitmap
import com.infinifolder.preview.ImageDecodeUtils.u8
import com.infinifolder.preview.ImageDecodeUtils.u16be
import com.infinifolder.preview.ImageDecodeUtils.u32be
import java.io.InterruptedIOException

/** Palette animation format facts: AmigaOS ILBM specification, CRNG/CCRT and DPaint IV DRNG.
 * https://wiki.amigaos.net/wiki/ILBM_IFF_Interleaved_Bitmap
 * BODY decoding stays in RetroImageDecoder; frames retain indices, never encoded streams. */
object IffColorCycling {
    const val MAX_ANIMATION_PIXELS=4*1024*1024
    data class Decoded(val bitmap:Bitmap,val animation:Animation?,val warnings:List<String>)
    internal data class Cell(val position:Int,val register:Int=-1,val rgb:Int=0)
    internal data class Range(val kind:String,val first:Int,val last:Int,val hz:Double,
        val reverse:Boolean,val cells:List<Cell>)

    /** One worker owns this renderer; output is borrowed until the next call. */
    class Animation internal constructor(private val indexed:Indexed,override val width:Int,override val height:Int,
        private val ranges:List<Range>):BitmapAnimation {
        val rangeCount get()=ranges.size
        override val description get()="$rangeCount cycling range(s)"
        private var previousPalette:IntArray?=null
        override fun render(elapsedNanos:Long,output:IntArray,force:Boolean):Boolean {
            require(output.size>=width*height)
            val palette=indexed.palette.copyOf()
            for(range in ranges){
                val length=range.last-range.first+1
                val colors=IntArray(length)
                val defined=BooleanArray(length)
                for(cell in range.cells){
                    val at=cell.position-range.first
                    colors[at]=if(cell.register>=0)palette[cell.register]else cell.rgb
                    defined[at]=true
                }
                // DRNG virtual cells between explicit anchors interpolate around the ring.
                for(at in colors.indices)if(!defined[at]){
                    var before=1
                    while(!defined[Math.floorMod(at-before,length)])before++
                    var after=1
                    while(!defined[(at+after)%length])after++
                    val left=colors[Math.floorMod(at-before,length)];val right=colors[(at+after)%length]
                    fun channel(shift:Int)=((((left ushr shift)and 255)*after+
                        ((right ushr shift)and 255)*before)/(before+after)) shl shift
                    colors[at]=0xff000000.toInt() or channel(16) or channel(8) or channel(0)
                }
                val steps=((elapsedNanos.coerceAtLeast(0)/1_000_000_000.0*range.hz).toLong()%length).toInt()
                val direction=if(range.reverse)-1 else 1
                for(cell in range.cells)if(cell.register>=0)
                    palette[cell.register]=colors[Math.floorMod(cell.position-range.first-direction*steps,length)]
            }
            if(indexed.ehb)for(i in 0 until 32)
                palette[i+32]=0xff000000.toInt() or ((palette[i] and 0xfefefe) ushr 1)
            if(!force&&previousPalette?.contentEquals(palette)==true)return false
            indexed.render(palette,width,height,output)
            previousPalette=palette
            return true
        }
    }

    internal class Indexed(val width:Int,val height:Int,val palette:IntArray,val paletteSize:Int,
        private val indices:ByteArray,private val opacity:ByteArray?,private val planes:Int,
        private val ham:Boolean,val ehb:Boolean) {
        val registers get()=if(ham)1 shl (planes-2)else if(ehb)32 else minOf(paletteSize,1 shl planes)
        fun render(colors:IntArray,outWidth:Int,outHeight:Int,output:IntArray){
            for(y in 0 until outHeight){
                if(Thread.currentThread().isInterrupted)throw InterruptedIOException("IFF cycling cancelled")
                val row=(y.toLong()*height/outHeight).toInt()*width
                if(!ham){
                    for(x in 0 until outWidth){
                        val source=row+(x.toLong()*width/outWidth).toInt()
                        val color=colors[indices[source].toInt() and 255]
                        output[y*outWidth+x]=if(opacity!=null&&opacity[source].toInt()==0)color and 0xffffff else color
                    }
                }else {
                    var held=colors[0];var outX=0
                    for(x in 0 until width){
                        val source=row+x
                        held=hamColor(indices[source].toInt() and 255,planes,colors,held)
                        while(outX<outWidth&&(outX.toLong()*width/outWidth).toInt()==x){
                            output[y*outWidth+outX]=if(opacity!=null&&opacity[source].toInt()==0)held and 0xffffff else held
                            outX++
                        }
                    }
                }
            }
        }
    }

    internal fun hamColor(value:Int,planes:Int,palette:IntArray,previous:Int):Int {
        val bits=planes-2
        val data=value and ((1 shl bits)-1)
        val component=if(bits==4)data*17 else (data shl 2) or (data ushr 4)
        return when(value ushr bits and 3){
            0->palette[data]
            1->(previous and 0xffffff00.toInt()) or component
            2->(previous and 0xff00ffff.toInt()) or (component shl 16)
            else->(previous and 0xffff00ff.toInt()) or (component shl 8)
        }
    }

    fun decode(bytes:ByteArray,targetPixels:Int):Decoded? {
        if(bytes.size<12||String(bytes,0,4,Charsets.US_ASCII)!="FORM"||
            String(bytes,8,4,Charsets.US_ASCII) !in setOf("ILBM","PBM "))return null
        val warnings=mutableListOf<String>()
        val candidates=readRanges(bytes,warnings)
        var indexed:Indexed?=null
        val bitmap=RetroImageDecoder.decodeAmigaIlbm(bytes,targetPixels,
            if(candidates.isEmpty())null else {image->indexed=image})?:return null
        val image=indexed
        if(candidates.isNotEmpty()&&image==null)warnings+="Color cycling requires a complete supported indexed layout of at most 4 megapixels"
        val supported=if(image==null)emptyList()else candidates.filter {range->
            val valid=range.cells.all{it.register<image.registers}
            if(!valid)warnings+="${range.kind} range refers to an unavailable palette register"
            valid
        }
        // Some writers store CCRT compatibility copies of their CRNG ranges.
        val ranges=supported.filter {range->range.kind!="CCRT"||supported.none {
            it.kind=="CRNG"&&it.first==range.first&&it.last==range.last
        }}
        val animation=if(image!=null&&ranges.isNotEmpty())Animation(image,bitmap.width,bitmap.height,ranges)else null
        if(animation!=null){
            val mutable=runCatching{bitmap.copy(Bitmap.Config.ARGB_8888,true)}.getOrNull()
            if(mutable==null)return Decoded(bitmap,null,warnings+"Could not allocate cycling frame")
            if(mutable!==bitmap)bitmap.recycle()
            val output=IntArray(mutable.width*mutable.height)
            animation.render(0,output)
            mutable.setPixels(output,0,mutable.width,0,0,mutable.width,mutable.height)
            return Decoded(mutable,animation,warnings.distinct())
        }
        return Decoded(bitmap,animation,warnings.distinct())
    }

    private fun readRanges(bytes:ByteArray,warnings:MutableList<String>):List<Range>{
        val ranges=mutableListOf<Range>()
        val declared=u32be(bytes,4)+8
        val end=minOf(bytes.size.toLong(),declared).toInt()
        var offset=12
        var scanlinePalette=false
        while(offset+8<=end){
            if(Thread.currentThread().isInterrupted)throw InterruptedIOException("IFF decoding cancelled")
            val tag=String(bytes,offset,4,Charsets.US_ASCII)
            val length=u32be(bytes,offset+4)
            val start=offset+8
            if(length>end-start){warnings+="Truncated IFF $tag chunk";break}
            val size=length.toInt()
            if(tag=="PCHG"||tag=="SHAM"||tag=="CTBL"||tag=="DYCP"){
                scanlinePalette=true;warnings+="$tag scanline palettes are unsupported; color cycling disabled"
            }
            if(tag=="CRNG"||tag=="CCRT"||tag=="DRNG"){
                if(ranges.size>=256){warnings+="More than 256 color-cycle ranges are unsupported";break}
                val range=when(tag){
                    "CRNG"->{
                        if(size<8){warnings+="Truncated CRNG range";null}else {
                            val rate=u16be(bytes,start+2);val flags=u16be(bytes,start+4)
                            regular(tag,u8(bytes,start+6),u8(bytes,start+7),rate*60.0/16384,
                                flags and 2!=0,flags and 1!=0&&rate>0&&rate!=36,warnings)
                        }
                    }
                    "CCRT"->{
                        if(size<14){warnings+="Truncated CCRT range";null}else {
                            val direction=u16be(bytes,start).toShort().toInt()
                            val seconds=u32be(bytes,start+4).toInt()
                            val micros=u32be(bytes,start+8).toInt()
                            val period=seconds*1_000_000L+micros
                            if(direction!=0&&(direction !in listOf(-1,1)||seconds<0||micros !in 0..999999||period<=0)){
                                warnings+="Invalid CCRT timing";null
                            }else regular(tag,u8(bytes,start+2),u8(bytes,start+3),
                                if(period>0)1_000_000.0/period else 0.0,direction<0,direction!=0,warnings)
                        }
                    }
                    else->dynamicRange(bytes,start,size,warnings)
                }
                if(range!=null)ranges+=range
            }
            offset=(start+length+(length and 1)).coerceAtMost(end.toLong()).toInt()
        }
        return if(scanlinePalette)emptyList()else ranges
    }
    private fun regular(kind:String,first:Int,last:Int,hz:Double,reverse:Boolean,active:Boolean,
        warnings:MutableList<String>):Range? {
        if(!active)return null
        if(first==last)return null
        if(first>last){warnings+="Invalid $kind palette range";return null}
        return Range(kind,first,last,hz,reverse,(first..last).map{Cell(it,it)})
    }
    private fun dynamicRange(bytes:ByteArray,start:Int,size:Int,warnings:MutableList<String>):Range? {
        if(size<8){warnings+="Truncated DRNG range";return null}
        val first=u8(bytes,start);val last=u8(bytes,start+1)
        val rate=u16be(bytes,start+2);val flags=u16be(bytes,start+4)
        val trueCount=u8(bytes,start+6);val registerCount=u8(bytes,start+7)
        if(flags and 1==0||rate==0)return null
        if(flags and 0xfffe!=0){warnings+="Unsupported DRNG flags";return null}
        if(first>=last||registerCount==0||8+trueCount*4+registerCount*2>size){warnings+="Invalid DRNG cells";return null}
        val cells=mutableListOf<Cell>()
        var at=start+8
        repeat(trueCount){
            cells+=Cell(u8(bytes,at),rgb=0xff000000.toInt() or (u8(bytes,at+1) shl 16) or
                (u8(bytes,at+2) shl 8) or u8(bytes,at+3));at+=4
        }
        repeat(registerCount){cells+=Cell(u8(bytes,at),u8(bytes,at+1));at+=2}
        if(cells.any{it.position !in first..last}||cells.map{it.position}.distinct().size!=cells.size||cells.size<2){
            warnings+="Invalid DRNG cell positions";return null
        }
        return Range("DRNG",first,last,rate*60.0/16384,false,cells)
    }
}
