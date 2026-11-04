package com.infinifolder.preview

/** Container timing for a native drawable's progress display. Pixel decoding stays in Android.
 * GIF89a: https://www.w3.org/Graphics/GIF/spec-gif89a.txt
 * WebP: https://developers.google.com/speed/webp/docs/riff_container */
internal object NativeAnimationTiming {
    fun durationNanos(bytes:ByteArray,extension:String):Long = runCatching {
        when(extension){"gif"->gif(bytes);"webp"->webp(bytes);else->0}
    }.getOrDefault(0)
    private fun gif(b:ByteArray):Long {
        var at=13;var total=0L;var delay=100_000_000L;var frames=0
        fun byte():Int {require(at<b.size);return b[at++].toInt() and 255}
        fun skip(n:Int){require(n>=0 && n<=b.size-at);at+=n}
        fun blocks(){while(true){val n=byte();if(n==0)break;skip(n)}}
        require(b.size>=13)
        val packed=b[10].toInt() and 255
        if(packed and 128!=0)skip(3*(1 shl ((packed and 7)+1)))
        while(at<b.size){
            require(!Thread.currentThread().isInterrupted)
            when(byte()){
                0x3b->return total
                0x21->{
                    if(byte()==0xf9){
                        require(byte()==4);byte();val ticks=byte() or (byte() shl 8);byte();require(byte()==0)
                        // Android 14+ matches browsers for GIF delays of 0 or 10 ms.
                        delay=if(android.os.Build.VERSION.SDK_INT>=34 && ticks<2)100_000_000 else maxOf(1,ticks)*10_000_000L
                    }else blocks()
                }
                0x2c->{
                    skip(8);val flags=byte()
                    if(flags and 128!=0)skip(3*(1 shl ((flags and 7)+1)))
                    byte();blocks();total+=delay;delay=100_000_000L
                    require(++frames<=10000)
                }
                else->error("Invalid GIF record")
            }
        }
        return total
    }
    private fun webp(b:ByteArray):Long {
        var at=12;var total=0L;var frames=0
        fun le(p:Int,n:Int)=(0 until n).fold(0L){v,i->v or ((b[p+i].toLong() and 255) shl (i*8))}
        while(b.size-at>=8){
            require(!Thread.currentThread().isInterrupted)
            val size=le(at+4,4);require(size<=b.size-at-8)
            if(String(b,at,4,Charsets.US_ASCII)=="ANMF"){
                require(size>=16 && ++frames<=10000)
                val delay=le(at+8+12,3)
                total+=delay*1_000_000
            }
            at+=8+size.toInt()+(size.toInt() and 1)
        }
        return total
    }
}
