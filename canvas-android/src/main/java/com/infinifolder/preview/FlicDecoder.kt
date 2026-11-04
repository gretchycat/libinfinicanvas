package com.infinifolder.preview

import android.graphics.Bitmap
import java.io.IOException
import java.io.InterruptedIOException

/** Original implementation of the standard indexed Autodesk Animator FLI/FLC formats.
 * Format reference: https://www.compuphase.com/flic.htm (no decoder source copied). */
object FlicDecoder {
    private const val MAX_PIXELS=4*1024*1024
    private const val MAX_FRAMES=10000
    data class Decoded(val bitmap:Bitmap,val animation:BitmapAnimation,val warnings:List<String>)
    internal data class Frame(val offset:Int,val end:Int,val delay:Long)

    fun recognizes(bytes:ByteArray)=bytes.size>=128 && u16(bytes,4) in setOf(0xaf11,0xaf12)

    fun decode(bytes:ByteArray,targetPixels:Int,animate:Boolean=true):Decoded? {
        if(!recognizes(bytes))return null
        require(targetPixels>0)
        val declaredSize=u32(bytes,0)
        val recovered=declaredSize==0L || u16(bytes,6)==0 || u16(bytes,8)==0 || u16(bytes,10)==0
        val size=if(declaredSize==0L)bytes.size.toLong()else declaredSize
        requireFormat(size in 128..bytes.size.toLong(),"Truncated FLIC file")
        var count=u16(bytes,6);var w=u16(bytes,8);var h=u16(bytes,10)
        requireFormat(count<=MAX_FRAMES,"FLIC frame count exceeds limits")
        requireFormat(u16(bytes,12) in setOf(0,8),"Only indexed 8-bit FLI/FLC is supported")
        if(recovered){
            val recoveredFrames=scanFrames(bytes,size.toInt())
            requireFormat(recoveredFrames.isNotEmpty(),"Incomplete FLIC header and no complete frames")
            if(w==0 || h==0){
                val geometry=recoverGeometry(bytes,recoveredFrames.first())
                    ?:throw IOException("FLIC dimensions are missing and cannot be recovered unambiguously")
                requireFormat((w==0 || w==geometry.first) && (h==0 || h==geometry.second),"Conflicting FLIC geometry")
                w=geometry.first;h=geometry.second
            }
            if(count==0)count=recoveredFrames.size
            requireFormat(count in 1..MAX_FRAMES && w.toLong()*h*count<=64L*1024*1024,"FLIC recovery work exceeds limits")
        }
        requireFormat(count in 1..MAX_FRAMES && w>0 && h>0 && w.toLong()*h<=MAX_PIXELS,"FLIC dimensions/frame count exceed limits")
        val fli=u16(bytes,4)==0xaf11
        val speed=if(fli)u16(bytes,16).toLong()*1_000_000_000/70 else u32(bytes,16)*1_000_000
        // Zero speed has no useful playback clock; use the historical 70 Hz tick.
        val defaultDelay=if(speed>0)speed else 1_000_000_000L/70
        val warnings=linkedSetOf<String>()
        if(recovered)warnings+="Recovered incomplete FLIC header from image data: $w × $h, $count frames"
        val frames=mutableListOf<Frame>()
        var at=128
        while(at<size && frames.size<count){
            interrupted()
            requireFormat(size-at>=6,"Truncated FLIC chunk")
            val length=u32(bytes,at)
            requireFormat(length>=6 && length<=size-at,"Invalid FLIC chunk size")
            val end=at+length.toInt()
            when(u16(bytes,at+4)){
                0xf1fa->{
                    requireFormat(length>=16,"Truncated FLIC frame")
                    requireFormat((u16(bytes,at+12)==0 || u16(bytes,at+12)==w) &&
                        (u16(bytes,at+14)==0 || u16(bytes,at+14)==h),"FLIC frame-size overrides are unsupported")
                    val delay=if(!fli && u16(bytes,at+8)>0)u16(bytes,at+8)*1_000_000L else defaultDelay
                    frames+=Frame(at,end,delay)
                    if(!animate && !recovered)break
                }
                0xf100->Unit // Animator Pro prefix/settings, not a displayed frame.
                else->throw IOException("Unsupported FLIC top-level chunk 0x${u16(bytes,at+4).toString(16)}")
            }
            at=end
        }
        requireFormat(frames.isNotEmpty(),"FLIC contains no frames")
        if(animate && frames.size<count)warnings+="Truncated FLIC frame sequence"
        if(speed==0L && frames.any{fli || u16(bytes,it.offset+8)==0})
            warnings+="Frame timing is missing; using a 70 frames/s fallback"
        val scale=minOf(1.0,targetPixels.toDouble()/maxOf(w,h))
        val animation=Animation(bytes,w,h,maxOf(1,(w*scale).toInt()),maxOf(1,(h*scale).toInt()),frames,warnings)
        if(recovered)animation.validateRecoveredFrames()
        val output=IntArray(animation.width*animation.height)
        animation.render(0,output,true)
        val bitmap=Bitmap.createBitmap(animation.width,animation.height,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(output,0,animation.width,0,0,animation.width,animation.height)
        return Decoded(bitmap,animation,warnings.toList())
    }

    /** Recovery never guesses an aspect ratio. Legacy BRUN packet counts must describe
     * a complete set of equally wide rows; modern zero packet-count streams cannot qualify. */
    private fun recoverGeometry(bytes:ByteArray,frame:Frame):Pair<Int,Int>? {
        var at=frame.offset+16
        repeat(u16(bytes,frame.offset+6)){
            requireFormat(frame.end-at>=6,"Truncated recovery subchunk")
            val length=u32(bytes,at)
            requireFormat(length>=6 && length<=frame.end-at,"Invalid recovery subchunk")
            val end=at+length.toInt()
            if(u16(bytes,at+4)==15){
                val input=Cursor(bytes,at+6,end);var width=0;var rows=0
                while(input.at<end){
                    interrupted()
                    if(end-input.at==1 && bytes[input.at]==0.toByte())break // Even-byte chunk padding.
                    val packets=input.byte();if(packets==0)return null
                    var columns=0
                    repeat(packets){
                        val run=input.signedByte();if(run==0)return null
                        val count=kotlin.math.abs(run)
                        input.skip(if(run>0)1 else count);columns+=count
                        if(columns>65535)return null
                    }
                    if(width==0)width=columns else if(width!=columns)return null
                    rows++;if(width.toLong()*rows>MAX_PIXELS)return null
                }
                return if(width>0 && rows>=2)width to rows else null
            }
            at=end
        }
        return null
    }
    private fun scanFrames(bytes:ByteArray,end:Int):List<Frame>{
        val frames=mutableListOf<Frame>();var at=128
        while(at<end){
            interrupted();requireFormat(end-at>=6,"Truncated recovery frame")
            val size=u32(bytes,at);requireFormat(size>=6 && size<=end-at,"Invalid recovery frame")
            when(u16(bytes,at+4)){
                0xf1fa->{requireFormat(size>=16 && frames.size<MAX_FRAMES,"FLIC recovery frame limit exceeded");frames+=Frame(at,at+size.toInt(),0)}
                0xf100->Unit
                else->throw IOException("Unsupported chunk in incomplete FLIC file")
            }
            at+=size.toInt()
        }
        return frames
    }

    private class Animation(val bytes:ByteArray,val sourceWidth:Int,val sourceHeight:Int,
        override val width:Int,override val height:Int,val frames:List<Frame>,val warnings:MutableSet<String>):BitmapAnimation {
        override val description="${frames.size} FLIC frames"
        val indices=ByteArray(sourceWidth*sourceHeight)
        val palette=IntArray(256){0xff000000.toInt()}
        val ends=LongArray(frames.size).also{array->var total=0L;frames.forEachIndexed{i,f->
            requireFormat(f.delay>0 && total<=Long.MAX_VALUE-f.delay,"FLIC duration overflow")
            total+=f.delay;array[i]=total
        }}
        override val durationNanos get()=ends.last()
        var current=-1
        var targetFrame=0
        override val needsMoreRendering get()=current<targetFrame
        var firstIndices:ByteArray?=null
        var firstPalette:IntArray?=null
        fun validateRecoveredFrames(){
            // At most 64 MP of indexed frame work, checked before entering recovery.
            frames.forEach{interrupted();decodeFrame(it)}
            indices.fill(0);palette.fill(0xff000000.toInt())
        }
        override fun render(elapsedNanos:Long,output:IntArray,force:Boolean):Boolean {
            require(output.size>=width*height)
            val time=elapsedNanos.coerceAtLeast(0)%ends.last()
            var low=0;var high=ends.lastIndex
            while(low<high){val middle=(low+high) ushr 1;if(time<ends[middle])high=middle else low=middle+1}
            val target=low
            targetFrame=target
            if(target==current && !force)return false
            if(target<current){firstIndices!!.copyInto(indices);firstPalette!!.copyInto(palette);current=0}
            // Catch-up after a long pause/seek remains bounded and cancellable.
            var budget=(8*1024*1024/indices.size).coerceIn(1,64)
            while(current<target && budget-->0 || current<0){
                interrupted();decodeFrame(frames[current+1]);current++
                if(current==0){firstIndices=indices.copyOf();firstPalette=palette.copyOf()}
            }
            for(y in 0 until height){
                interrupted()
                val row=(y.toLong()*sourceHeight/height).toInt()*sourceWidth
                for(x in 0 until width)output[y*width+x]=palette[indices[row+(x.toLong()*sourceWidth/width).toInt()].toInt() and 255]
            }
            return true
        }
        private fun decodeFrame(frame:Frame){
            var at=frame.offset+16
            repeat(u16(bytes,frame.offset+6)){
                interrupted()
                requireFormat(frame.end-at>=6,"Truncated FLIC subchunk")
                val length=u32(bytes,at)
                requireFormat(length>=6 && length<=frame.end-at,"Invalid FLIC subchunk size")
                val end=at+length.toInt();val type=u16(bytes,at+4)
                val input=Cursor(bytes,at+6,end)
                when(type){
                    4,11->{
                        var register=0
                        repeat(input.word()){
                            register+=input.byte();val count=input.byte().let{if(it==0)256 else it}
                            requireFormat(register+count<=256,"FLIC palette overflow")
                            repeat(count){
                                fun component():Int {val value=input.byte();requireFormat(type!=11 || value<=63,"Invalid 6-bit FLIC color");return if(type==11)(value shl 2) or (value ushr 4) else value}
                                palette[register++]=0xff000000.toInt() or (component() shl 16) or (component() shl 8) or component()
                            }
                        }
                    }
                    13->indices.fill(0)
                    16->input.copy(indices,0,indices.size)
                    15->for(y in 0 until sourceHeight){
                        interrupted();input.byte() // Legacy packet count is not authoritative in FLC.
                        var x=0
                        while(x<sourceWidth){
                            val count=input.signedByte()
                            requireFormat(count!=0,"Zero-length FLIC byte run")
                            val length=kotlin.math.abs(count);checkSpan(x,length)
                            if(count>0)indices.fill(input.byte().toByte(),y*sourceWidth+x,y*sourceWidth+x+length)
                            else input.copy(indices,y*sourceWidth+x,length)
                            x+=length
                        }
                    }
                    12->{
                        val start=input.word();val lines=input.word()
                        requireFormat(start<=sourceHeight && lines<=sourceHeight-start,"FLIC line delta overflow")
                        for(y in start until start+lines){
                            interrupted();var x=0
                            repeat(input.byte()){
                                x+=input.byte();val count=input.signedByte();val length=kotlin.math.abs(count);checkSpan(x,length)
                                if(count<0)indices.fill(input.byte().toByte(),y*sourceWidth+x,y*sourceWidth+x+length)
                                else input.copy(indices,y*sourceWidth+x,length)
                                x+=length
                            }
                        }
                    }
                    7->{
                        val lines=input.word();requireFormat(lines<=sourceHeight,"FLIC word delta overflow")
                        var y=0
                        repeat(lines){
                            interrupted();var packets:Int
                            while(true){
                                val op=input.word()
                                when(op ushr 14){
                                    0->{packets=op;break}
                                    2->{requireFormat(y<sourceHeight,"FLIC last-pixel overflow");indices[y*sourceWidth+sourceWidth-1]=op.toByte()}
                                    3->{y-=op.toShort().toInt();requireFormat(y<sourceHeight,"FLIC line-skip overflow")}
                                    else->throw IOException("Invalid FLIC delta opcode")
                                }
                            }
                            requireFormat(y<sourceHeight,"FLIC delta row overflow")
                            var x=0
                            repeat(packets){
                                x+=input.byte();val count=input.signedByte();val length=kotlin.math.abs(count)*2;checkSpan(x,length)
                                if(count<0){val a=input.byte().toByte();val b=input.byte().toByte()
                                    repeat(-count){indices[y*sourceWidth+x++]=a;indices[y*sourceWidth+x++]=b}
                                }else {input.copy(indices,y*sourceWidth+x,length);x+=length}
                            }
                            y++
                        }
                    }
                    18,31,41->Unit // Postage stamps and labels do not change displayed pixels.
                    else->throw IOException("Unsupported FLIC image chunk $type")
                }
                at=end
            }
        }
        private fun checkSpan(x:Int,length:Int)=requireFormat(x in 0..sourceWidth && length<=sourceWidth-x,"FLIC pixel run exceeds row")
    }
    private class Cursor(val bytes:ByteArray,var at:Int,val end:Int){
        fun byte():Int {requireFormat(at<end,"Truncated FLIC packet");return bytes[at++].toInt() and 255}
        fun signedByte()=byte().toByte().toInt()
        fun word()=byte() or (byte() shl 8)
        fun skip(length:Int){requireFormat(length<=end-at,"Truncated FLIC run");at+=length}
        fun copy(output:ByteArray,offset:Int,length:Int){requireFormat(length<=end-at,"Truncated FLIC pixels");bytes.copyInto(output,offset,at,at+length);at+=length}
    }
    private fun u16(b:ByteArray,p:Int)=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun u32(b:ByteArray,p:Int)=(0..3).fold(0L){n,i->n or ((b[p+i].toLong() and 255) shl (i*8))}
    private fun requireFormat(value:Boolean,message:String){if(!value)throw IOException(message)}
    private fun interrupted(){if(Thread.currentThread().isInterrupted)throw InterruptedIOException("FLIC decoding cancelled")}
}
