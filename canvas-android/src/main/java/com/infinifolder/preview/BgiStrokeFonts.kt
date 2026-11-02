package com.infinifolder.preview

import java.util.concurrent.ConcurrentHashMap

/** Reads the original bounded BGI CHR stroke format. No platform font substitution. */
internal object BgiStrokeFonts {
    data class Vertex(val x:Int,val y:Int,val draw:Boolean)
    data class Glyph(val advance:Int,val vertices:List<Vertex>)
    class Font(val ascender:Int,val descender:Int,private val glyphs:Map<Int,Glyph>) {
        fun glyph(code:Int):Glyph?=glyphs[code] ?: glyphs['?'.code]
    }
    private val names=listOf("TRIP","LITT","SANS","GOTH","SCRI","SIMP","TSCR","LCOM","EURO","BOLD")
    private val fonts=ConcurrentHashMap<Int,Font>()
    private val missing=ConcurrentHashMap.newKeySet<Int>()
    fun get(id:Int):Font? {
        if(id !in 1..10 || id in missing)return null
        fonts[id]?.let {return it}
        val parsed=runCatching {
            val bytes=BgiStrokeFonts::class.java.getResourceAsStream("/bgi-fonts/${names[id-1]}.CHR")?.use {it.readBytes()}
                ?: return@runCatching null
            parse(bytes)
        }.getOrNull()
        if(parsed==null)missing.add(id) else fonts[id]=parsed
        return parsed
    }
    fun parse(bytes:ByteArray):Font {
        require(bytes.size in 32..131072){"Invalid BGI font length"}
        fun u8(p:Int)=bytes[p].toInt() and 255
        fun u16(p:Int)=u8(p) or (u8(p+1) shl 8)
        require(u8(0)==80 && u8(1)==75 && u8(2)==8 && u8(3)==8){"Invalid BGI signature"}
        val marker=(4 until minOf(bytes.size-12,512)).firstOrNull {u8(it)==26} ?: error("Missing BGI header")
        val header=u16(marker+1)
        require(header>=marker+12 && header+16<=bytes.size && u8(header)==43){"Invalid BGI stroke header"}
        val count=u16(header+1);val first=u8(header+4);val start=header+u16(header+5)
        val widths=header+16+count*2
        require(count in 1..256 && first+count<=256 && widths+count<=start && start<bytes.size){"Invalid BGI tables"}
        val ascender=bytes[header+8].toInt();val descender=bytes[header+10].toInt()
        require(ascender in 1..127 && descender in -127..0){"Invalid BGI metrics"}
        fun signed(value:Int)=if(value and 64!=0)(value and 127)-128 else value and 127
        val glyphs=HashMap<Int,Glyph>()
        for(i in 0 until count) {
            var cursor=start+u16(header+16+i*2)
            require(cursor>=start && cursor+1<bytes.size){"Invalid BGI glyph offset"}
            val vertices=ArrayList<Vertex>()
            var ended=false
            var steps=0
            while(cursor+1<bytes.size && steps++<4096) {
                val a=u8(cursor);val b=u8(cursor+1);cursor+=2
                val op=((a ushr 7) shl 1) or (b ushr 7)
                if(op==0){ended=true;break}
                if(op>=2)vertices.add(Vertex(signed(a),signed(b),op==3))
            }
            require(ended){"Unterminated BGI glyph"}
            glyphs[first+i]=Glyph(u8(widths+i),vertices)
        }
        return Font(ascender,descender,glyphs)
    }
}
