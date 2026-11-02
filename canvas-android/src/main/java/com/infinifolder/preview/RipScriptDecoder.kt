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
        var work=0L;var commands=0;var recognised=0
        val deadline=System.nanoTime()+4_000_000_000L
        fun checkWork(amount:Int=1) {
            work+=amount
            if(work>100_000_000L || Thread.currentThread().isInterrupted || System.nanoTime()>deadline)
                error("RIPscrip rendering limit or cancellation")
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
            // Metrics: DosWorld/dwbgi DOCS/CHR.MD; shapes remain a reported fallback.
            val strokeFactors=doubleArrayOf(1.0,0.6,2.0/3,0.75,1.0,4.0/3,5.0/3,2.0,2.5,3.0,4.0)
            val nominalHeights=intArrayOf(8,31,9,32,32,37,35,31,35,55,60)
            val factor=strokeFactors[fontSize]
            val glyphHeight=if(font==0)8*fontSize*scale else
                (nominalHeights[font.coerceIn(1,10)]*factor*scale).roundToInt().coerceAtLeast(1)
            if(font!=0)warnings.add("BGI vector fonts use a metric-scaled CP437 bitmap fallback")
            for(ch in value) {
                val glyphWidth=if(font==0)8*fontSize*scale else {
                    val nativeWidth=if(font==2)6.0 else nominalHeights[font.coerceIn(1,10)]*0.65
                    (nativeWidth*factor*scale).roundToInt().coerceAtLeast(1)
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
        fun command(token:String) {
            if(token.isEmpty())return
            require(++commands<=30000){"Too many RIPscrip commands"};checkWork()
            val level=token[0].digitToIntOrNull() ?: 0
            val code=if(level==0)token.take(1) else token.take(2)
            val a=Args(token.substring(code.length),scale)
            when(code) {
                "*"->{left=0;top=0;right=width-1;bottom=height-1;indices.fill(0);palette=defaultPalette();clipboard=null;x=0;y=0}
                "v"->{val n=a.coords(4);left=n[0];top=n[1];right=n[2]+scale-1;bottom=n[3]+scale-1;if(n.all{it==0})right=-1}
                "E"->clear()
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
                "1K","#"->Unit // Interaction metadata / end marker; no session side effects.
                else->{warnings.add("Unsupported RIPscrip command: $code");return}
            }
            recognised++
        }
        fun run(source:String):Scene {
            val logical=StringBuilder();var continued=false
            for(physical in source.replace("\r\n","\n").replace('\r','\n').lineSequence()) {
                val line=physical.trimEnd('\u001a')
                val slashes=line.reversed().takeWhile{it=='\\'}.length
                logical.append(if(slashes%2==1)line.dropLast(1)else line)
                continued=slashes%2==1
                if(continued)continue
                val row=logical.toString();logical.setLength(0)
                if(!row.startsWith("!|")) {
                    if(row.isNotBlank())warnings.add("TTY text outside graphics commands is not rendered")
                    continue
                }
                val token=StringBuilder();var escaped=false
                for(ch in row.substring(2)) {
                    if(ch=='|' && !escaped){command(token.toString());token.setLength(0)}else token.append(ch)
                    escaped=ch=='\\' && !escaped
                }
                command(token.toString())
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
