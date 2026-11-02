package com.infinifolder.preview

import android.graphics.Bitmap
import kotlin.math.*

/** Static RIPscrip 1.54 graphics. No network, filesystem, BBS replies or external icon loads. */
object RipScriptDecoder {
    const val MAX_BYTES=2*1024*1024
    enum class Mode { EGA, HIGH_RESOLUTION }
    data class Scene(val pixels:IntArray,val warnings:List<String>,val width:Int=640,val height:Int=350)
    data class Image(val bitmap:Bitmap,val warnings:List<String>)
    fun decode(bytes:ByteArray,targetPx:Int,mode:Mode=Mode.EGA):Image? = try {
        val scale=if(mode==Mode.EGA)1 else ceil(targetPx/640.0).toInt().coerceIn(1,4)
        val scene=render(bytes,scale)
        val native=Bitmap.createBitmap(scene.pixels,scene.width,scene.height,Bitmap.Config.ARGB_8888)
        val width=minOf(targetPx.coerceAtLeast(1),scene.width)
        val image=Bitmap.createScaledBitmap(native,width,(width*3/4).coerceAtLeast(1),false)
        if(image!==native)native.recycle()
        Image(image,scene.warnings)
    } catch(_:Exception) { null }

    /** Indexed EGA surface so palette changes recolour earlier drawings and XOR is exact. */
    fun render(bytes:ByteArray,resolutionScale:Int=1):Scene {
        require(resolutionScale in 1..4)
        require(bytes.size in 1..MAX_BYTES) {"RIPscrip source exceeds 2 MiB"}
        return Renderer(resolutionScale).run(String(bytes,Charsets.ISO_8859_1))
    }
    private class Args(val text:String,val scale:Int) {
        var offset=0
        fun number(width:Int=2):Int {
            val end=minOf(offset+width,text.length)
            require(end>offset) {"Missing RIPscrip parameter"}
            val digits=text.substring(offset,end)
            require(digits.all {it in '0'..'9' || it in 'A'..'Z'}) {"Invalid RIPscrip MegaNum"}
            val value=digits.toInt(36)
            offset=end;return value
        }
        fun numbers(count:Int)=IntArray(count){number()}
        fun coord()=number()*scale
        fun coords(count:Int)=IntArray(count){coord()}
        fun string()=text.substring(offset).replace(Regex("\\\\([!|\\\\])"),"$1")
    }
    private class Renderer(val scale:Int) {
        val width=640*scale;val height=350*scale
        val indices=IntArray(width*height)
        var palette=defaultPalette()
        var colour=15;var fillColour=15;var fillPattern=1;var customFill=IntArray(8){255}
        var linePattern=65535;var thick=scale;var xor=false
        var left=0;var top=0;var right=width-1;var bottom=height-1
        var x=0;var y=0;var font=0;var fontSize=1;var vertical=false
        var clipboard:IntArray?=null;var clipboardWidth=0;var clipboardHeight=0
        val warnings=linkedSetOf<String>()
        private val tty=RipTextWindow(indices,width,scale,{checkWork(it)},{warnings.add(it)})
        var work=0L;var commands=0;var recognised=0
        val deadline=System.nanoTime()+4_000_000_000L
        var nextDeadlineCheck=0L
        fun checkWork(amount:Int=1) {
            work+=amount
            if(work>100_000_000L)error("RIPscrip pixel-work limit exceeded")
            if(work>=nextDeadlineCheck) {
                nextDeadlineCheck=work+4096
                if(Thread.currentThread().isInterrupted || System.nanoTime()>deadline)error("RIPscrip rendering limit or cancellation")
            }
        }
        fun pixel(px:Int,py:Int,c:Int=colour,useXor:Boolean=xor) {
            checkWork()
            if(px !in left..right || py !in top..bottom || px !in 0 until width || py !in 0 until height)return
            val p=py*width+px
            indices[p]=if(useXor)indices[p] xor (c and 15) else c and 15
        }
        fun line(x0:Int,y0:Int,x1:Int,y1:Int,pattern:Int=linePattern,useXor:Boolean=xor) {
            var xx=x0;var yy=y0;val dx=abs(x1-x0);val dy=-abs(y1-y0)
            val sx=if(x0<x1)1 else -1;val sy=if(y0<y1)1 else -1;var err=dx+dy;var step=0;val touched=if(useXor)HashSet<Int>() else null
            while(true) {
                if(pattern and (1 shl (15-((step/scale) and 15)))!=0) {
                    val radius=thick/2
                    for(oy in -radius..radius)for(ox in -radius..radius) {
                        val px=xx+ox;val py=yy+oy
                        if(touched==null || (px in 0 until width && py in 0 until height && touched.add(py*width+px)))pixel(px,py,colour,useXor)
                    }
                }
                checkWork();if(xx==x1 && yy==y1)break
                val twice=2*err;if(twice>=dy){err+=dy;xx+=sx};if(twice<=dx){err+=dx;yy+=sy};step++
            }
        }
        fun fillPixel(xx:Int,yy:Int) {
            val pattern=if(fillPattern==12)customFill else patterns[fillPattern.coerceIn(0,11)]
            pixel(xx,yy,if(pattern[(yy/scale) and 7] and (128 shr ((xx/scale) and 7))!=0)fillColour else 0,false)
        }
        fun polygon(points:List<Pair<Int,Int>>,filled:Boolean,closed:Boolean=true) {
            if(points.isEmpty())return
            if(filled) for(yy in maxOf(top,points.minOf{it.second},0)..minOf(bottom,points.maxOf{it.second},height-1)) {
                val hits=ArrayList<Double>()
                for(i in points.indices) {
                    val a=points[i];val b=points[(i+1)%points.size]
                    if((a.second<=yy && b.second>yy)||(b.second<=yy && a.second>yy))
                        hits.add(a.first+(yy-a.second).toDouble()*(b.first-a.first)/(b.second-a.second))
                }
                checkWork(points.size);hits.sort()
                for(i in 0 until hits.size-1 step 2)
                    for(xx in maxOf(left,ceil(hits[i]).toInt(),0)..minOf(right,floor(hits[i+1]).toInt(),width-1))fillPixel(xx,yy)
            }
            for(i in 0 until points.size-1)line(points[i].first,points[i].second,points[i+1].first,points[i+1].second,useXor=if(filled)false else xor)
            if(closed && points.size>1)line(points.last().first,points.last().second,points.first().first,points.first().second,useXor=if(filled)false else xor)
        }
        fun arc(cx:Int,cy:Int,rx:Int,ry:Int,start:Int,end:Int,pie:Boolean=false,filled:Boolean=false) {
            var sweep=(end-start)%360;if(sweep<=0)sweep+=360
            val count=(max(rx,ry)*sweep*PI/180).ceilToInt().coerceIn(12,8192)
            val points=(0..count).map {i->val angle=(start+sweep*i.toDouble()/count)*PI/180
                (cx+rx*cos(angle)).roundToInt() to (cy-ry*sin(angle)).roundToInt()}
            val old=linePattern;val oldXor=xor;linePattern=65535;xor=false
            polygon(if(pie)listOf(cx to cy)+points else points,filled,pie||sweep==360)
            linePattern=old;xor=oldXor
        }
        fun Double.ceilToInt()=ceil(this).toInt()
        fun text(value:String) {
            // BGI stroke sizes are fractional magnifications (size 4 is native),
            // unlike DEFAULT_FONT's integer 8x8 magnification. LITT is only 9px high.
            // Measured per-character BGI advances prevent long Triplex labels from overflowing.
            // Shapes remain a reported CP437 fallback; measurements are in RipFontMetrics.
            val strokeFactors=doubleArrayOf(1.0,0.6,2.0/3,0.75,1.0,4.0/3,5.0/3,2.0,2.5,3.0,4.0)
            val factor=strokeFactors[fontSize]
            val strokeFont=if(font==0)null else BgiStrokeFonts.get(font)
            if(strokeFont!=null) {
                val oldThickness=thick;thick=scale
                try {
                    for(ch in value) {
                        val glyph=strokeFont.glyph(ch.code) ?: continue
                        var previous:Pair<Int,Int>?=null
                        for(vertex in glyph.vertices) {
                            val gx=(vertex.x*factor*scale).toInt()
                            val gy=(strokeFont.ascender*factor*scale).toInt()-(vertex.y*factor*scale).toInt()
                            val point=if(vertical)(x+gy) to (y-gx) else (x+gx) to (y+gy)
                            if(vertex.draw && previous!=null)line(previous.first,previous.second,point.first,point.second,65535)
                            previous=point
                            checkWork()
                        }
                        val advance=(glyph.advance*factor*scale).toInt()
                        if(vertical)y-=advance else x+=advance
                    }
                } finally {thick=oldThickness}
                return
            }
            val glyphHeight=if(font==0)8*fontSize*scale else
                (RipFontMetrics.height(font)*factor*scale).toInt().coerceAtLeast(1)
            if(font!=0)warnings.add("BGI stroke font unavailable; using metric-scaled CP437 fallback")
            for(ch in value) {
                val glyphWidth=if(font==0)8*fontSize*scale else {
                    (RipFontMetrics.advance(font,ch.code)*factor*scale).toInt().coerceAtLeast(1)
                }
                for(gy in 0 until glyphHeight)for(gx in 0 until glyphWidth) {
                    val sourceX=gx*8/glyphWidth;val sourceY=gy*8/glyphHeight
                    if(Cp437Font.isForeground(ch.code,sourceX,sourceY*2)) {
                        if(vertical)pixel(x+gy,y-gx)else pixel(x+gx,y+gy)
                    }
                }
                if(vertical)y-=glyphWidth else x+=glyphWidth
                checkWork(glyphWidth*glyphHeight)
            }
        }
        fun flood(sx:Int,sy:Int,border:Int) {
            if(sx !in left..right || sy !in top..bottom || sx !in 0 until width || sy !in 0 until height)return
            val seen=BooleanArray(indices.size);val queue=IntArray(indices.size);var head=0;var tail=0
            fun enqueue(xx:Int,yy:Int) {
                if(xx !in left..right || yy !in top..bottom || xx !in 0 until width || yy !in 0 until height)return
                val p=yy*width+xx
                if(!seen[p] && indices[p]!=border){seen[p]=true;queue[tail++]=p}
            }
            enqueue(sx,sy)
            while(head<tail){val p=queue[head++];val xx=p%width;val yy=p/width;fillPixel(xx,yy)
                enqueue(xx-1,yy);enqueue(xx+1,yy);enqueue(xx,yy-1);enqueue(xx,yy+1)}
        }
        fun clear() { for(yy in maxOf(top,0)..minOf(bottom,height-1))for(xx in maxOf(left,0)..minOf(right,width-1))pixel(xx,yy,0,false) }
        private data class ButtonStyle(val width:Int=0,val height:Int=0,val orientation:Int=2,val flags:Int=256,
            val bevel:Int=0,val fore:Int=15,val shadow:Int=0,val bright:Int=15,val dark:Int=8,val surface:Int=7,
            val flags2:Int=0,val underline:Int=15,val corner:Int=7) {
            fun has(flag:Int)=flags and flag!=0
        }
        private var buttonStyle=ButtonStyle()
        private fun textExtent(label:String):Pair<Int,Int> {
            val factor=doubleArrayOf(1.0,0.6,2.0/3,0.75,1.0,4.0/3,5.0/3,2.0,2.5,3.0,4.0)[fontSize]
            val stroke=if(font==0)null else BgiStrokeFonts.get(font)
            val w=if(font==0)label.length*8*fontSize*scale else label.sumOf {
                ((stroke?.glyph(it.code)?.advance ?: RipFontMetrics.advance(font,it.code))*factor*scale).toInt()}
            val h=if(font==0)8*fontSize*scale else (RipFontMetrics.height(font)*factor*scale).toInt()
            return if(vertical)h to w else w to h
        }
        private fun solidBox(x0:Int,y0:Int,x1:Int,y1:Int,c:Int) {
            for(yy in maxOf(top,y0,0)..minOf(bottom,y1,height-1))
                for(xx in maxOf(left,x0,0)..minOf(right,x1,width-1))pixel(xx,yy,c,false)
        }
        private fun frame(x0:Int,y0:Int,x1:Int,y1:Int,highlight:Int,shadow:Int,corner:Int) {
            if(x0>x1 || y0>y1)return
            colour=highlight;line(x0,y1,x0,y0,65535,false);line(x0,y0,x1,y0,65535,false)
            colour=shadow;line(x1,y0,x1,y1,65535,false);line(x1,y1,x0,y1,65535,false)
            pixel(x0,y1,corner,false);pixel(x1,y0,corner,false)
        }
        private fun unavailableIcon(px:Int,py:Int,name:String) {
            warnings.add("External ICN artwork not available: ${name.take(80)}")
            val oldColour=colour;val oldThick=thick;val oldXor=xor;val oldPattern=linePattern
            val oldX=x;val oldY=y;val oldFont=font;val oldSize=fontSize;val oldVertical=vertical
            try {
                thick=scale;xor=false;linePattern=65535
                val label="Missing ICN: "+name.take(22)
                val right=minOf(width-1,px+label.length*8*scale+4*scale)
                frame(px,py,right,minOf(height-1,py+14*scale),15,15,15)
                colour=15;font=0;fontSize=1;vertical=false;x=px+2*scale;y=py+3*scale;text(label)
            } finally {
                colour=oldColour;thick=oldThick;xor=oldXor;linePattern=oldPattern
                x=oldX;y=oldY;font=oldFont;fontSize=oldSize;vertical=oldVertical
            }
        }
        private fun button(n:IntArray,hotkey:Int,instanceFlags:Int,parts:List<String>) {
            val style=buttonStyle
            val bx=n[0];val by=n[1]
            val br=if(style.width>0 && style.height>0)bx+style.width*scale-1 else n[2]
            val bb=if(style.width>0 && style.height>0)by+style.height*scale-1 else n[3]
            require(br>=bx && bb>=by){"Invalid RIP button bounds"}
            val label=parts.getOrElse(1){""}
            val oldColour=colour;val oldThick=thick;val oldXor=xor;val oldPattern=linePattern;val oldX=x;val oldY=y
            try {
                thick=scale;xor=false;linePattern=65535
                val selected=instanceFlags and 1!=0
                val hi=if(selected)style.dark else style.bright;val lo=if(selected)style.bright else style.dark
                when {
                    style.has(128)->unavailableIcon(bx,by,parts.firstOrNull().orEmpty())
                    style.has(1)-> {
                        val copied=clipboard
                        if(copied==null)warnings.add("RIP clipboard button has no source image")
                        else copied.forEachIndexed {i,c->pixel(bx+i%clipboardWidth,by+i/clipboardWidth,c,false)}
                    }
                    style.has(256)->solidBox(bx,by,br,bb,style.surface)
                    else->warnings.add("Unsupported RIP button base style")
                }
                val bevel=if(style.has(512))style.bevel*scale else 0
                require(bevel<=64*scale){"RIP button bevel exceeds limit"}
                for(d in 1..bevel)frame(bx-d,by-d,br+d,bb+d,hi,lo,style.corner)
                if(style.has(32768))frame(bx+scale,by+scale,br-scale,bb-scale,lo,hi,style.corner)
                if(style.has(8)) {
                    val inset=(minOf(br-bx,bb-by)/8).coerceAtMost(10*scale).coerceAtLeast(2*scale)
                    frame(bx+inset,by+inset,br-inset,bb-inset,lo,hi,style.corner)
                    frame(bx+inset+scale,by+inset+scale,br-inset-scale,bb-inset-scale,hi,lo,style.corner)
                }
                if(style.has(16)) {
                    frame(bx-bevel-scale,by-bevel-scale,br+bevel+scale,bb+bevel+scale,0,0,0)
                    frame(bx-bevel-2*scale,by-bevel-2*scale,br+bevel+2*scale,bb+bevel+2*scale,lo,hi,style.corner)
                }
                if(style.has(64))warnings.add("RIP automatic button clipboard capture is not supported")
                if(label.isNotEmpty()) {
                    val (tw,th)=textExtent(label)
                    var tx=(bx+br-tw)/2;var ty=(by+bb-th)/2
                    when(style.orientation) {
                        0->ty=by-th-2*scale
                        1->tx=bx-tw-2*scale
                        3->tx=br+2*scale
                        4->ty=bb+2*scale
                    }
                    if(style.orientation in setOf(0,2,4)) {
                        val inset=if(style.has(8))20*scale else 10*scale
                        if(style.flags2 and 8!=0)tx=bx+inset
                        else if(style.flags2 and 16!=0)tx=br-inset-tw
                    }
                    if(selected){tx+=scale;ty+=scale}
                    if(style.has(32)){colour=style.shadow;x=tx+scale;y=ty+scale;text(label)}
                    colour=style.fore;x=tx;y=ty;text(label)
                    val index=label.indexOfFirst {it.equals(hotkey.toChar(),true)}
                    if(index>=0) {
                        val advance=textExtent(label.substring(0,index)).first
                        val hotWidth=textExtent(label[index].toString()).first
                        if(style.flags2 and 2!=0){colour=style.underline;x=tx+advance;y=ty;text(label[index].toString())}
                        if(style.has(2048)){colour=style.underline;line(tx+advance,ty+th,tx+advance+hotWidth-1,ty+th,65535,false)}
                    }
                }
            } finally {colour=oldColour;thick=oldThick;xor=oldXor;linePattern=oldPattern;x=oldX;y=oldY}
        }
        private fun emit(token:String) {
            try {command(token)}catch(error:IllegalArgumentException) {
                val code=token.takeWhile {it.isDigit()}.let {prefix->token.take(prefix.length+1)}
                warnings.add("Malformed RIP command $code skipped: ${error.message?.take(100)}")
            }
        }
        fun command(token:String) {
            if(token.isEmpty())return
            if(++commands>30000)error("RIPscrip command limit exceeded");checkWork()
            val level=token[0].digitToIntOrNull() ?: 0
            val code=if(level==0)token.take(1) else token.take(2)
            val a=Args(token.substring(code.length),scale)
            when(code) {
                "*"->{left=0;top=0;right=width-1;bottom=height-1;indices.fill(0);palette=defaultPalette();clipboard=null;x=0;y=0;tty.reset()}
                "v"->{val n=a.coords(4);left=n[0];top=n[1];right=n[2]+scale-1;bottom=n[3]+scale-1;if(n.all{it==0})right=-1}
                "E"->clear()
                "w"->{val bounds=a.numbers(4);val wrap=a.number(1);val size=a.number(1);tty.configure(bounds,wrap,size)}
                "e"->tty.clear()
                "g"->{val tx=a.number();val ty=a.number();tty.go(tx,ty)}
                "H"->tty.home()
                ">"->tty.eraseToEnd()
                "c"->colour=a.number().also{require(it<16)}
                "Q"->palette=IntArray(16){ega(a.number().also{require(it<64)})}
                "a"->{val index=a.number();val value=a.number();require(index<16 && value<64);palette[index]=ega(value)}
                "W"->xor=a.number().also{require(it in 0..1)}==1
                "m"->{x=a.coord();y=a.coord()}
                "T"->text(a.string())
                "@"->{x=a.coord();y=a.coord();text(a.string())}
                "Y"->{font=a.number();vertical=a.number()==1;fontSize=a.number().coerceIn(1,10);a.number()}
                "X"->{val n=a.coords(2);for(dy in 0 until scale)for(dx in 0 until scale)pixel(n[0]+dx,n[1]+dy,colour,false)}
                "L"->{val n=a.coords(4);line(n[0],n[1],n[2],n[3])}
                "R","B"->{val n=a.coords(4)
                    if(code=="B")for(yy in maxOf(n[1],top,0)..minOf(n[3],bottom,height-1))for(xx in maxOf(n[0],left,0)..minOf(n[2],right,width-1))fillPixel(xx,yy)
                    else polygon(listOf(n[0] to n[1],n[2] to n[1],n[2] to n[3],n[0] to n[3]),false)}
                "C"->{val n=a.coords(3);arc(n[0],n[1],n[2],(n[2]*350.0/480).roundToInt(),0,360)}
                "O","V","i"->{val n=a.numbers(6);for(j in intArrayOf(0,1,4,5))n[j]*=scale;arc(n[0],n[1],n[4],n[5],n[2],n[3],code=="i",code=="i")}
                "o"->{val n=a.coords(4);arc(n[0],n[1],n[2],n[3],0,360,filled=true)}
                "A","I"->{val n=a.numbers(5);for(j in intArrayOf(0,1,4))n[j]*=scale;arc(n[0],n[1],n[4],(n[4]*350.0/480).roundToInt(),n[2],n[3],code=="I",code=="I")}
                "P","p","l"->{val count=a.number();require(count in 1..512);val points=List(count){a.coord() to a.coord()};polygon(points,code=="p",code!="l")}
                "Z"->{val n=a.numbers(9);for(j in 0..7)n[j]*=scale;val count=n[8].coerceIn(1,1024)
                    val points=(0..count).map {i->val t=i.toDouble()/count;val u=1-t
                        (u*u*u*n[0]+3*u*u*t*n[2]+3*u*t*t*n[4]+t*t*t*n[6]).roundToInt() to
                        (u*u*u*n[1]+3*u*u*t*n[3]+3*u*t*t*n[5]+t*t*t*n[7]).roundToInt()};polygon(points,false,false)}
                "F"->{val n=a.numbers(3);n[0]*=scale;n[1]*=scale;require(n[2]<16);flood(n[0],n[1],n[2])}
                "="->{val style=a.number();val user=a.number(4);thick=a.number().also{require(it==1||it==3)}*scale
                    linePattern=when(style){0->65535;1->0x3333;2->0x1e3f;3->0x1f1f;4->user and 65535;else->error("Invalid line style")}}
                "S"->{fillPattern=a.number().also{require(it in 0..11)};fillColour=a.number().also{require(it<16)}}
                "s"->{customFill=IntArray(8){a.number().also{require(it<256)}};fillColour=a.number().also{require(it<16)};fillPattern=12}
                "1C"->{val n=a.coords(4);n[2]+=scale-1;n[3]+=scale-1;a.number(1);require(n[0]<=n[2] && n[1]<=n[3] && n[2]<width && n[3]<height)
                    clipboardWidth=n[2]-n[0]+1;clipboardHeight=n[3]-n[1]+1
                    clipboard=IntArray(clipboardWidth*clipboardHeight){indices[(n[1]+it/clipboardWidth)*width+n[0]+it%clipboardWidth]}}
                "1P"->{val px=a.coord();val py=a.coord();val mode=a.number();a.number(1);require(mode in 0..4)
                    clipboard?.forEachIndexed {i,c->val xx=px+i%clipboardWidth;val yy=py+i/clipboardWidth
                        if(xx in 0 until width && yy in 0 until height){val old=indices[yy*width+xx];pixel(xx,yy,when(mode){1->old xor c;2->old or c;3->old and c;4->c xor 15;else->c},false)}}}
                "1B"->{
                    val w=a.number();val h=a.number();val orient=a.number();val flags=a.number(4);val bevel=a.number()
                    val colors=a.numbers(5);a.number();val flags2=a.number();val underline=a.number();val corner=a.number();a.number(6)
                    require(orient in 0..4 && colors.all {it in 0..15} && underline<16 && corner<16){"Invalid button style"}
                    buttonStyle=ButtonStyle(w,h,orient,flags,bevel,colors[0],colors[1],colors[2],colors[3],colors[4],flags2,underline,corner)
                }
                "1U"->{val n=a.coords(4);val hot=a.number();val flags=a.number(1);a.number(1);button(n,hot,flags,a.string().split("<>",limit=3))}
                "1I"->{val px=a.coord();val py=a.coord();a.number();a.number(1);a.number();unavailableIcon(px,py,a.string())}
                "1K","#"->Unit // Interaction metadata / end marker; no session side effects.
                else->{warnings.add("Unsupported RIPscrip command: ${code.replace("\u001b","<ESC>")}");return}
            }
            recognised++
        }
        fun run(source:String):Scene {
            val logical=StringBuilder();var continued=false
            val physicalLines=source.substringBefore('\u001a').replace("\r\n","\n").replace('\r','\n').split('\n')
            for((lineIndex,rawPhysical) in physicalLines.withIndex()) {
                var physical=rawPhysical
                if(logical.isEmpty()) {
                    val alternate=physical.indices.firstOrNull {i->(physical[i]=='\u0001' || physical[i]=='\u0002') && physical.getOrNull(i+1)=='|'}
                    if(alternate!=null){tty.write(physical.substring(0,alternate));physical="!"+physical.substring(alternate+1)}
                }
                if(logical.isEmpty() && !physical.startsWith("!|")) {
                    tty.write(physical+if(lineIndex<physicalLines.lastIndex)"\r\n" else "")
                    continue
                }
                val line=physical.trimEnd('\u001a')
                val slashes=line.reversed().takeWhile{it=='\\'}.length
                logical.append(if(slashes%2==1)line.dropLast(1)else line)
                continued=slashes%2==1
                if(continued)continue
                val row=logical.toString();logical.setLength(0)
                if(!row.startsWith("!|")) {
                    tty.write(row+"\r\n")
                    continue
                }
                val token=StringBuilder();var escaped=false
                for(ch in row.substring(2)) {
                    if(ch=='|' && !escaped){emit(token.toString());token.setLength(0)}else token.append(ch)
                    escaped=ch=='\\' && !escaped
                }
                emit(token.toString())
            }
            require(!continued){"Truncated RIPscrip continuation"}
            require(recognised>0){"No supported RIPscrip graphics commands"}
            return Scene(IntArray(indices.size){palette[indices[it]]},warnings.toList(),width,height)
        }
    }
    private fun ega(value:Int):Int {
        val r=(if(value and 4!=0)170 else 0)+(if(value and 32!=0)85 else 0)
        val g=(if(value and 2!=0)170 else 0)+(if(value and 16!=0)85 else 0)
        val b=(if(value and 1!=0)170 else 0)+(if(value and 8!=0)85 else 0)
        return 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    }
    private fun defaultPalette()=intArrayOf(0,1,2,3,4,5,20,7,56,57,58,59,60,61,62,63).map(::ega).toIntArray()
    private val patterns=arrayOf(
        intArrayOf(0,0,0,0,0,0,0,0),IntArray(8){255},intArrayOf(255,255,0,0,255,255,0,0),
        intArrayOf(1,2,4,8,16,32,64,128),intArrayOf(224,193,131,7,14,28,56,112),
        intArrayOf(240,120,60,30,15,135,195,225),intArrayOf(165,210,105,180,90,45,150,75),
        intArrayOf(255,136,136,136,255,136,136,136),intArrayOf(129,66,36,24,24,36,66,129),
        intArrayOf(204,51,204,51,204,51,204,51),intArrayOf(128,0,8,0,128,0,8,0),intArrayOf(136,0,34,0,136,0,34,0))
}
