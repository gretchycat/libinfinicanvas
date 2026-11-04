package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.zip.CRC32

/** PNG compression/filtering stays in Android's PNG codec. This layer handles animation
 * chunks, timing, disposal and composition. References: W3C PNG 3 and PNG group's MNG 1.0.
 * https://www.w3.org/TR/png-3/ ; https://www.libpng.org/pub/mng/spec/ */
internal object PngAnimationDecoder {
    private const val MAX_PIXELS=4*1024*1024
    private const val MAX_FRAMES=10000
    private val pngSignature=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    private val mngSignature=byteArrayOf(138.toByte(),77,78,71,13,10,26,10)
    data class Decoded(val bitmap:Bitmap,val animation:BitmapAnimation?,val warnings:List<String>)
    private data class Chunk(val type:String,val data:ByteArray)
    private data class Frame(val chunks:List<Chunk>,val w:Int,val h:Int,val x:Int,val y:Int,
        val delay:Long,val dispose:Int=0,val over:Boolean=true,val clear:Int?=null)
    private data class Sequence(val w:Int,val h:Int,val frames:List<Frame>,val plays:Long,val name:String,
        val endAction:Int=0,val warnings:List<String> = emptyList(),val interaction:Interaction?=null)
    private data class StoredObject(var frame:Frame?=null,var visible:Boolean=true,var x:Int=0,var y:Int=0)
    private data class PointerRule(val kind:ImagePointerKind?,val mask:Int,val box:IntArray?,val objectId:Int,
        val index:Int,val state:String)
    private data class Interaction(val states:Map<String,List<Frame>>,val rules:List<PointerRule>,val objects:Map<String,Map<Int,Frame>>)

    fun isMng(bytes:ByteArray)=bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(mngSignature)
    fun decode(bytes:ByteArray,targetPixels:Int):Decoded? {
        val mng=isMng(bytes)
        if(!mng && (bytes.size<8 || !bytes.copyOfRange(0,8).contentEquals(pngSignature)))return null
        if(!mng && !hasAnimationControl(bytes))return null
        val chunks=readChunks(bytes,if(mng)"MEND" else "IEND")
        if(!mng && chunks.none{it.type=="acTL"})return null
        val sequence=if(mng)readMng(chunks)else readApng(chunks)
        requireFormat(sequence.frames.isNotEmpty(),"Animation contains no frames")
        val scale=minOf(1.0,targetPixels.toDouble()/maxOf(sequence.w,sequence.h))
        val renderer=Animation(sequence,maxOf(1,(sequence.w*scale).toInt()),maxOf(1,(sequence.h*scale).toInt()))
        val pixels=IntArray(renderer.width*renderer.height)
        renderer.render(0,pixels,true)
        var passes=1
        while(renderer.needsMoreRendering){
            requireFormat(passes++<8,"Initial animation frame exceeds compositing work limit")
            renderer.render(0,pixels,true)
        }
        val bitmap=Bitmap.createBitmap(renderer.width,renderer.height,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels,0,renderer.width,0,0,renderer.width,renderer.height)
        val player=sequence.interaction?.let{InteractiveMng(sequence,renderer,it)}?:renderer.takeIf{sequence.frames.size>1}
        return Decoded(bitmap,player,sequence.warnings)
    }

    private fun hasAnimationControl(bytes:ByteArray):Boolean {
        var at=8
        while(bytes.size-at>=12){
            val size=u32(bytes,at)
            if(size>bytes.size-at-12)return false
            val tag=String(bytes,at+4,4,Charsets.US_ASCII)
            if(tag=="acTL")return true
            if(tag=="IDAT" || tag=="IEND")return false
            at+=12+size.toInt()
        }
        return false
    }

    private fun readChunks(bytes:ByteArray,endTag:String):List<Chunk>{
        val result=mutableListOf<Chunk>();var at=8;var ended=false
        while(at<bytes.size){
            interrupted();requireFormat(bytes.size-at>=12,"Truncated animation chunk")
            val size=u32(bytes,at)
            requireFormat(size<=bytes.size-at-12,"Invalid animation chunk size")
            val length=size.toInt();val tag=String(bytes,at+4,4,Charsets.US_ASCII)
            val crc=CRC32().apply{update(bytes,at+4,length+4)}.value
            requireFormat(crc==u32(bytes,at+8+length),"Invalid $tag checksum")
            requireFormat(result.size<100000,"Animation has too many chunks")
            result+=Chunk(tag,bytes.copyOfRange(at+8,at+8+length))
            at+=12+length
            if(tag==endTag){ended=true;break}
        }
        requireFormat(ended,"Missing $endTag chunk")
        return result
    }

    private fun readApng(chunks:List<Chunk>):Sequence {
        val header=chunks.firstOrNull()?.takeIf{it.type=="IHDR" && it.data.size==13}?.data
            ?:throw IOException("Missing PNG header")
        val w=dimension(header,0);val h=dimension(header,4);checkSize(w,h)
        val control=chunks.filter{it.type=="acTL"}
        requireFormat(control.size==1 && control[0].data.size==8,"Invalid APNG control")
        val frameCount=u32(control[0].data,0)
        requireFormat(frameCount in 1..MAX_FRAMES.toLong(),"APNG frame limit exceeded")
        val shared=mutableListOf<Chunk>();val frames=mutableListOf<Frame>()
        var frameHeader:ByteArray?=null;var data=mutableListOf<Chunk>();var idatSeen=false
        var sequence=0L;var defaultFrame=false;var controlSeen=false
        fun finish(){
            val f=frameHeader?:return
            requireFormat(data.isNotEmpty(),"APNG frame contains no image data")
            val fw=dimension(f,4);val fh=dimension(f,8);val x=dimension(f,12,true);val y=dimension(f,16,true)
            requireFormat(fw<=w && fh<=h && x<=w-fw && y<=h-fh,"APNG frame exceeds canvas")
            val disposal=f[24].toInt() and 255;val blend=f[25].toInt() and 255
            requireFormat(disposal<=2 && blend<=1,"Invalid APNG disposal/blend")
            val ihdr=header.copyOf();put32(ihdr,0,fw);put32(ihdr,4,fh)
            val numerator=u16(f,20);val denominator=u16(f,22).let{if(it==0)100 else it}
            val delay=maxOf(10_000_000L,numerator*1_000_000_000L/denominator)
            frames+=Frame(listOf(Chunk("IHDR",ihdr))+shared+data+Chunk("IEND",byteArrayOf()),fw,fh,x,y,delay,
                if(frames.isEmpty() && disposal==2)1 else disposal,blend==1)
            requireFormat(frames.size<=MAX_FRAMES,"APNG frame limit exceeded")
            data=mutableListOf()
        }
        for(chunk in chunks){
            interrupted()
            when(chunk.type){
                "acTL"->{requireFormat(!idatSeen,"APNG control follows image data");controlSeen=true}
                "fcTL"->{
                    requireFormat(controlSeen && chunk.data.size==26 && u32(chunk.data,0)==sequence++,"Invalid APNG frame sequence")
                    finish();frameHeader=chunk.data
                    if(!idatSeen){
                        defaultFrame=true
                        requireFormat(u32(chunk.data,4)==w.toLong() && u32(chunk.data,8)==h.toLong() &&
                            u32(chunk.data,12)==0L && u32(chunk.data,16)==0L,"Invalid APNG default frame bounds")
                    }
                }
                "IDAT"->{idatSeen=true;if(defaultFrame){requireFormat(frameHeader!=null && frames.isEmpty(),"Misplaced APNG IDAT");data+=chunk}}
                "fdAT"->{
                    requireFormat(idatSeen && frameHeader!=null && chunk.data.size>=4 && u32(chunk.data,0)==sequence++,"Invalid APNG frame data")
                    data+=Chunk("IDAT",chunk.data.copyOfRange(4,chunk.data.size))
                }
                "PLTE","tRNS","gAMA","cHRM","sRGB","iCCP","sBIT"->{requireFormat(!idatSeen,"Misplaced PNG palette/color data");shared+=chunk}
                "IEND"->finish()
                "IHDR"->Unit
                else->requireFormat(chunk.type[0].isLowerCase(),"Unsupported PNG chunk ${chunk.type}")
            }
        }
        requireFormat(frames.size.toLong()==frameCount,"APNG frame count mismatch")
        return Sequence(w,h,frames,u32(control[0].data,4),"APNG")
    }

    /** PNG-based MNG streams: stored objects, grouped frames, global palettes and TERM.
     * Other critical operations are rejected, rather than showing a misleading animation. */
    private fun readMng(chunks:List<Chunk>):Sequence {
        val header=chunks.firstOrNull()?.takeIf{it.type=="MHDR" && it.data.size==28}?.data
            ?:throw IOException("Missing MNG header")
        val w=dimension(header,0);val h=dimension(header,4);checkSize(w,h)
        val ticks=u32(header,8)
        val shared=linkedMapOf<String,Chunk>();val frames=mutableListOf<Frame>()
        var embedded:MutableList<Chunk>?=null
        val objects=mutableMapOf(0 to StoredObject());var objectId=0;var frozen=setOf<Int>()
        val warnings=mutableListOf<String>();val rules=mutableListOf<PointerRule>()
        val states=linkedMapOf<String,List<Frame>>();val stateObjects=mutableMapOf<String,Map<Int,Frame>>()
        var stateReferences=0
        var stateName:String?=null;var stateStart=0;var prologue=emptyList<Frame>();var startsSegment=false;var seenSeek=false
        var mode=1;var background=0;var groupStart=0;var groupActive=false
        var defaultDelay=1L;var delay=1L;var plays=1L;var endAction=0;var repeatDelay=0L
        fun frameDelay()=if(ticks>0)delay*1_000_000_000L/ticks else 0
        fun emit(frame:Frame){
            requireFormat(frames.size<MAX_FRAMES,"MNG frame limit exceeded")
            frames+=frame.copy(delay=if(mode in setOf(1,3))frameDelay()else 0,
                clear=if(frames.isEmpty() || startsSegment || mode==3 || mode==4 && frames.size==groupStart)background else null)
            startsSegment=false
            groupActive=true
        }
        fun finishGroup(){
            if(mode in setOf(2,4) && groupActive){
                if(frames.size==groupStart && mode==4){
                    requireFormat(frames.size<MAX_FRAMES,"MNG frame limit exceeded")
                    frames+=Frame(emptyList(),0,0,0,0,0,clear=background)
                }
                if(frames.size>groupStart)frames[frames.lastIndex]=frames.last().copy(delay=frameDelay())
            }
            groupStart=frames.size;groupActive=false
        }
        fun saveState(){
            stateName?.let{name->
                requireFormat(name !in states,"Duplicate MNG state name")
                stateReferences+=prologue.size+frames.size-stateStart+objects.values.count{it.frame!=null}
                requireFormat(stateReferences<=40000,"MNG interaction state metadata exceeds limits")
                val layers=prologue+frames.subList(stateStart,frames.size).toList()
                states[name]=if(layers.isNotEmpty() && layers.all{it.delay==0L})
                    layers.dropLast(1)+layers.last().copy(delay=100_000_000)else layers
                stateObjects[name]=objects.mapNotNull{(id,state)->state.frame?.let{id to it.copy(x=state.x,y=state.y)}}.toMap()
            }
        }
        chunkLoop@for(chunk in chunks){
            interrupted()
            val d=chunk.data
            if(embedded!=null){
                requireFormat(chunk.type !in setOf("MHDR","MEND","IHDR","DHDR","JHDR"),"Invalid embedded MNG image")
                requireFormat(chunk.type[0].isLowerCase() || chunk.type in setOf("PLTE","IDAT","IEND"),"Unsupported embedded PNG chunk ${chunk.type}")
                embedded.add(chunk)
                if(chunk.type=="IEND"){
                    val ihdr=embedded[0].data
                    requireFormat(ihdr.size==13,"Invalid MNG PNG header")
                    val fw=dimension(ihdr,0);val fh=dimension(ihdr,4);checkSize(fw,fh)
                    requireFormat(ihdr[11]==0.toByte(),"MNG intrapixel differencing is unsupported")
                    val local=embedded.map{it.type}.toSet()
                    val colorSpace=setOf("gAMA","cHRM","sRGB","iCCP")
                    val actual=mutableListOf(embedded.first())
                    if(local.none{it in colorSpace})actual+=shared.values.filter{it.type in colorSpace}
                    for(part in embedded.drop(1)){
                        if(part.type=="PLTE" && part.data.isEmpty()){
                            actual+=shared["PLTE"]?:throw IOException("MNG global palette is missing")
                            if("tRNS" !in local)shared["tRNS"]?.let{actual+=it}
                        }else actual+=part
                    }
                    val state=objects.getValue(objectId)
                    val image=Frame(actual,fw,fh,state.x,state.y,0)
                    if(objectId!=0)state.frame=image
                    if(state.visible)emit(image)
                    embedded=null
                }
                continue
            }
            when(chunk.type){
                "MHDR"->requireFormat(frames.isEmpty(),"Multiple MNG headers")
                "MEND"->Unit
                "IHDR"->{embedded=mutableListOf(chunk)}
                "PLTE","tRNS","gAMA","cHRM","sRGB","iCCP","sBIT"->{if(d.isEmpty())shared.remove(chunk.type)else shared[chunk.type]=chunk}
                "DEFI"->{
                    requireFormat(d.size in setOf(2,3,4,12),"MNG object clipping is unsupported")
                    objectId=u16(d,0)
                    requireFormat(objectId in objects || objects.size<257,"MNG stored-object limit exceeded")
                    requireFormat(objectId==0 || objectId !in frozen,"Cannot redefine a frozen MNG object")
                    val state=objects.getOrPut(objectId){StoredObject()}
                    if(d.size>=3){requireFormat(d[2].toInt() in 0..1,"Invalid MNG visibility");state.visible=d[2].toInt()==0}
                    if(d.size>=12){state.x=u32(d,4).toInt();state.y=u32(d,8).toInt()}
                }
                "SHOW"->{
                    requireFormat(d.size in setOf(0,2,4,5),"Invalid MNG SHOW command")
                    val first=if(d.isEmpty())1 else u16(d,0)
                    val last=if(d.isEmpty())65535 else if(d.size>=4)u16(d,2)else first
                    val show=if(d.isEmpty())2 else if(d.size==5)d[4].toInt() and 255 else 0
                    requireFormat(first>0 && last>0 && show in 0..7,"Invalid MNG SHOW range/mode")
                    val ids=objects.keys.filter{it in minOf(first,last)..maxOf(first,last)}.sorted().let{if(first>last)it.reversed()else it}
                    val viewable=ids.filter{objects.getValue(it).frame!=null}
                    val selected=if(show>=6 && viewable.isNotEmpty()){
                        val previous=viewable.indexOfFirst{objects.getValue(it).visible}
                        viewable[(previous+1)%viewable.size]
                    }else null
                    for(id in ids){
                        val state=objects.getValue(id)
                        when(show){0,3->state.visible=true;1->state.visible=false;4,5->state.visible=!state.visible;6,7->state.visible=id==selected}
                        if(show%2==0 && state.visible)state.frame?.let{emit(it.copy(x=state.x,y=state.y))}
                    }
                }
                "MOVE"->{
                    requireFormat(d.size==13 && d[4].toInt() in 0..1,"Invalid MNG MOVE command")
                    val first=u16(d,0);val last=u16(d,2);requireFormat(first<=last,"Invalid MNG MOVE range")
                    val dx=u32(d,5).toInt();val dy=u32(d,9).toInt()
                    for((id,state) in objects)if(id in first..last){
                        val x=dx.toLong()+if(d[4].toInt()==1)state.x.toLong()else 0L
                        val y=dy.toLong()+if(d[4].toInt()==1)state.y.toLong()else 0L
                        requireFormat(x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE && y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE,"MNG MOVE overflow")
                        state.x=x.toInt();state.y=y.toInt()
                    }
                }
                "SAVE"->frozen=objects.keys.filter{it!=0}.toSet()
                "SEEK"->{
                    finishGroup()
                    saveState()
                    if(!seenSeek)prologue=frames.map{it.copy(delay=0)}
                    requireFormat(d.size<=79,"Oversized MNG seek state")
                    stateName=if(d.isEmpty())null else String(d,Charsets.ISO_8859_1)
                    seenSeek=true;stateStart=frames.size;startsSegment=true
                    objects.keys.retainAll(frozen);objects[0]=StoredObject();objectId=0
                }
                "DISC"->{
                    requireFormat(d.size%2==0,"Invalid MNG DISC command")
                    val ids=if(d.isEmpty())objects.keys.filter{it!=0 && it !in frozen}else (d.indices step 2).map{u16(d,it)}
                    requireFormat(ids.none{it in frozen},"Cannot discard a frozen MNG object")
                    ids.forEach{objects.remove(it)};objects.putIfAbsent(0,StoredObject());if(objectId !in objects)objectId=0
                }
                "evNT"->{rules+=readPointerRules(d);requireFormat(rules.size<=1024,"MNG event limit exceeded")}
                "BACK"->{
                    requireFormat(d.size in setOf(6,7),"MNG background images are unsupported")
                    background=0xff000000.toInt() or ((u16(d,0) ushr 8) shl 16) or ((u16(d,2) ushr 8) shl 8) or (u16(d,4) ushr 8)
                }
                "FRAM"->{
                    finishGroup()
                    delay=defaultDelay
                    if(d.isNotEmpty()){
                        val requested=d[0].toInt() and 255;if(requested!=0)mode=requested
                        requireFormat(mode in 1..4,"Invalid MNG framing mode")
                        val zero=(1 until d.size).firstOrNull{d[it]==0.toByte()}
                        if(zero!=null){
                            requireFormat(d.size-zero>=5,"Truncated MNG frame control")
                            val change=d[zero+1].toInt() and 255
                            requireFormat(change in 0..2 && d[zero+2]==0.toByte() && d[zero+3]==0.toByte() && d[zero+4]==0.toByte(),"MNG timeout/clipping/sync controls are unsupported")
                            if(change!=0){requireFormat(d.size-zero>=9,"Truncated MNG frame delay");delay=u32(d,zero+5);if(change==2)defaultDelay=delay}
                        }
                    }
                    groupActive=true
                }
                "TERM"->{
                    requireFormat(frames.isEmpty() && d.size in setOf(1,10),"Invalid MNG termination")
                    val action=d[0].toInt() and 255
                    requireFormat(action in 0..3,"Invalid MNG termination action")
                    if(action==3){
                        requireFormat(d.size==10 && d[1].toInt() in 0..2,"Invalid MNG repeat control")
                        plays=u32(d,6).let{if(it==0x7fffffffL)0L else maxOf(1,it)}
                        repeatDelay=if(ticks>0)u32(d,2)*1_000_000_000L/ticks else 0
                        endAction=d[1].toInt()
                    }else {requireFormat(d.size==1,"Invalid MNG termination size");endAction=action}
                }
                else->requireFormat(chunk.type[0].isLowerCase(),"Unsupported MNG operation ${chunk.type}")
            }
        }
        finishGroup()
        saveState()
        requireFormat(embedded==null,"Truncated MNG PNG image")
        if(frames.isNotEmpty()){
            frames[frames.lastIndex]=frames.last().copy(delay=frames.last().delay+repeatDelay)
            if(frames.all{it.delay==0L})frames[frames.lastIndex]=frames.last().copy(delay=100_000_000)
        }
        val interaction=if(rules.isNotEmpty()){
            requireFormat(states.isNotEmpty() && states.values.all{it.isNotEmpty()},"MNG events require nonempty named states")
            requireFormat(rules.all{it.state in states},"MNG event targets a missing state")
            requireFormat(rules.filter{it.mask>=2}.all{rule->stateObjects.values.any{rule.objectId in it}},"MNG event targets a missing image object")
            Interaction(states,rules,stateObjects)
        }else null
        val initial=interaction?.states?.values?.first()?:frames
        return Sequence(w,h,initial,if(interaction!=null)0 else plays,"MNG",if(interaction!=null)0 else endAction,warnings,interaction)
    }

    /** Dynamic MNG evNT wire fields, as documented by libmng's public event API/chunk reader.
     * https://github.com/LuaDist/libmng/blob/master/libmng.h (format facts only). */
    private fun readPointerRules(data:ByteArray):List<PointerRule>{
        var at=0;val result=mutableListOf<PointerRule>()
        fun byte():Int{requireFormat(at<data.size,"Truncated MNG event");return data[at++].toInt() and 255}
        fun word()=(byte() shl 8) or byte()
        fun integer():Int=(byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        while(at<data.size){
            interrupted();requireFormat(result.size<1024,"MNG event limit exceeded")
            val event=byte();val mask=byte();requireFormat(event in 0..5 && mask in 0..5,"Unsupported MNG event/mask")
            val kind=if(event==0)null else ImagePointerKind.entries[event-1]
            val box=if(mask in setOf(1,4,5))IntArray(4){integer()}else null
            if(box!=null)requireFormat(box[0]<=box[1] && box[2]<=box[3],"Invalid MNG event rectangle")
            val id=if(mask in 2..5)word()else 0
            val index=if(mask in setOf(3,5))byte()else -1
            val start=at;while(at<data.size && data[at]!=0.toByte())at++
            requireFormat(at-start in 1..79,"Invalid MNG event state name")
            val name=String(data,start,at-start,Charsets.ISO_8859_1)
            if(at<data.size){at++;requireFormat(at<data.size,"MNG events end with an empty record")}
            result+=PointerRule(kind,mask,box,id,index,name)
        }
        return result
    }

    private class InteractiveMng(val original:Sequence,val renderer:Animation,val interaction:Interaction):InteractiveBitmapAnimation {
        override val width get()=renderer.width
        override val height get()=renderer.height
        override val description="Interactive MNG · ${interaction.states.size} states"
        override val stateNames=interaction.states.keys.toList()
        override val needsContinuousFrames=interaction.states.values.any{frames->frames.count{it.delay>0}>1}
        @Volatile override var currentStateName=stateNames.first()
            private set
        override val needsMoreRendering get()=renderer.needsMoreRendering
        private var changed=false
        private var activatedAt=0L
        private val indexMasks=object:LinkedHashMap<Frame,ByteArray>(16,0.75f,true){}
        private var maskPixels=0
        override fun selectState(name:String):Boolean {
            val frames=interaction.states[name]?:return false
            if(name!=currentStateName){
                renderer.activate(original.copy(frames=frames,interaction=null));currentStateName=name;changed=true
            }
            return true
        }
        override fun pointerEvent(kind:ImagePointerKind,x:Float,y:Float):Boolean {
            if(!x.isFinite() || !y.isFinite())return false
            val px=kotlin.math.floor(x*original.w).toInt();val py=kotlin.math.floor(y*original.h).toInt()
            val rule=interaction.rules.firstOrNull {rule->
                if(rule.kind!=kind)return@firstOrNull false
                if(rule.box?.let{px<it[0] || px>=it[1] || py<it[2] || py>=it[3]}==true)return@firstOrNull false
                if(rule.mask<2)return@firstOrNull true
                val frame=interaction.objects[currentStateName]?.get(rule.objectId)?:return@firstOrNull false
                val sx=px.toLong()-frame.x;val sy=py.toLong()-frame.y
                if(sx !in 0 until frame.w.toLong() || sy !in 0 until frame.h.toLong())return@firstOrNull false
                val offset=sy.toInt()*frame.w+sx.toInt()
                if(rule.index>=0){
                    val mask=indexMasks[frame]?:renderer.indexMask(frame).also{mask->
                        while(maskPixels+mask.size>4*1024*1024 && indexMasks.isNotEmpty()){
                            val entry=indexMasks.entries.iterator();maskPixels-=entry.next().value.size;entry.remove()
                        }
                        indexMasks[frame]=mask;maskPixels+=mask.size
                    }
                    (mask[offset].toInt() and 255)==rule.index
                }else (renderer.patch(frame)[offset] ushr 24)!=0
            }?:return false
            return selectState(rule.state)
        }
        override fun render(elapsedNanos:Long,output:IntArray,force:Boolean):Boolean {
            val switched=changed
            if(switched){activatedAt=elapsedNanos;changed=false}
            return renderer.render((elapsedNanos-activatedAt).coerceAtLeast(0),output,force||switched)
        }
    }

    private class Animation(var sequence:Sequence,override val width:Int,override val height:Int):BitmapAnimation {
        override val description="${sequence.frames.size} ${sequence.name} frames"
        val pixels=IntArray(sequence.w*sequence.h)
        var previous:IntArray?=null
        var current=-1
        var targetFrame=0
        override val needsMoreRendering get()=current<targetFrame
        var terminal=false
        private fun timeline(frames:List<Frame>)=LongArray(frames.size).also{a->var t=0L;frames.forEachIndexed{i,f->
            requireFormat(f.delay>=0 && t<=Long.MAX_VALUE-f.delay,"PNG animation duration overflow")
            t+=f.delay;a[i]=t
        };requireFormat(t>0,"Animation has no positive frame duration")}
        var ends=timeline(sequence.frames)
        fun activate(next:Sequence){
            sequence=next;ends=timeline(next.frames)
            current=-1;targetFrame=0;terminal=false;previous=null;pixels.fill(0)
        }
        override val durationNanos get()=ends.last()
        val cache=object:LinkedHashMap<Frame,IntArray>(16,0.75f,true){}
        var cachedPixels=0
        override fun render(elapsedNanos:Long,output:IntArray,force:Boolean):Boolean {
            require(output.size>=width*height)
            val elapsed=elapsedNanos.coerceAtLeast(0)
            val done=sequence.plays>0 && elapsed/ends.last()>=sequence.plays
            val time=if(done)ends.last()-1 else elapsed%ends.last()
            var low=0;var high=ends.lastIndex
            while(low<high){val mid=(low+high) ushr 1;if(time<ends[mid])high=mid else low=mid+1}
            val target=if(done && sequence.endAction==2)0 else if(done)ends.lastIndex else low
            targetFrame=target
            if(current==target && done==terminal && !force)return false
            if(target<current || terminal && !done){pixels.fill(0);previous=null;current=-1}
            var budget=8*1024*1024
            while(current<target && budget>0){
                interrupted()
                if(current>=0){
                    val old=sequence.frames[current]
                    when(old.dispose){1->clear(old,0);2->previous?.copyInto(pixels)}
                }
                val index=current+1;val frame=sequence.frames[index]
                budget-=maxOf(pixels.size,frame.w*frame.h)
                previous=if(frame.dispose==2)pixels.copyOf()else null
                frame.clear?.let{pixels.fill(it)}
                val patch=patch(frame)
                val firstY=maxOf(0L,-frame.y.toLong()).coerceAtMost(frame.h.toLong()).toInt()
                val lastY=minOf(frame.h.toLong(),sequence.h.toLong()-frame.y).coerceAtLeast(0).toInt()
                val firstX=maxOf(0L,-frame.x.toLong()).coerceAtMost(frame.w.toLong()).toInt()
                val lastX=minOf(frame.w.toLong(),sequence.w.toLong()-frame.x).coerceAtLeast(0).toInt()
                for(py in firstY until lastY){
                    interrupted()
                    val dy=py.toLong()+frame.y;if(dy !in 0 until sequence.h.toLong())continue
                    for(px in firstX until lastX){
                        val dx=px.toLong()+frame.x;if(dx !in 0 until sequence.w.toLong())continue
                        val at=dy.toInt()*sequence.w+dx.toInt();val color=patch[py*frame.w+px]
                        pixels[at]=if(frame.over)over(color,pixels[at])else color
                    }
                }
                current=index
            }
            terminal=done
            if(done && sequence.endAction==1)pixels.fill(0)
            for(y in 0 until height){
                interrupted();val sy=(y.toLong()*sequence.h/height).toInt()
                for(x in 0 until width)output[y*width+x]=pixels[sy*sequence.w+(x.toLong()*sequence.w/width).toInt()]
            }
            return true
        }
        private fun clear(f:Frame,color:Int){
            for(y in maxOf(0,f.y) until minOf(sequence.h.toLong(),f.y.toLong()+f.h).toInt()){
                interrupted();val start=maxOf(0,f.x);val end=minOf(sequence.w.toLong(),f.x.toLong()+f.w).toInt()
                if(start<end)pixels.fill(color,y*sequence.w+start,y*sequence.w+end)
            }
        }
        fun patch(frame:Frame):IntArray {
            if(frame.chunks.isEmpty())return IntArray(0) // A real background-only MNG layer.
            cache[frame]?.let{return it}
            val encoded=ByteArrayOutputStream().apply{
                write(pngSignature)
                for(chunk in frame.chunks)writeChunk(this,chunk)
            }.toByteArray()
            val bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.size,BitmapFactory.Options().apply{
                inPreferredConfig=Bitmap.Config.ARGB_8888;inScaled=false
            })?:throw IOException("Cannot decode ${sequence.name} frame")
            val result=try{
                requireFormat(bitmap.width==frame.w && bitmap.height==frame.h,"Animation frame dimensions mismatch")
                IntArray(frame.w*frame.h).also{bitmap.getPixels(it,0,frame.w,0,0,frame.w,frame.h)}
            }finally{bitmap.recycle()}
            if(result.size<=2*1024*1024){
                while(cachedPixels+result.size>2*1024*1024 && cache.isNotEmpty()){
                    val first=cache.entries.iterator();cachedPixels-=first.next().value.size;first.remove()
                }
                cache[frame]=result;cachedPixels+=result.size
            }
            return result
        }
        fun indexMask(frame:Frame):ByteArray {
            val header=frame.chunks.firstOrNull{it.type=="IHDR"}?.data?:throw IOException("MNG event image has no PNG header")
            requireFormat(header[9].toInt()==3,"MNG index event requires an indexed PNG object")
            // Identity palette turns each original sample into its own exact blue value.
            // Android still owns PNG filtering/interlace/inflation; no second PNG decoder.
            val depth=header[8].toInt() and 255
            requireFormat(depth in setOf(1,2,4,8),"Invalid indexed MNG event depth")
            val identity=ByteArray((1 shl depth)*3){if(it%3==2)(it/3).toByte()else 0}
            val chunks=frame.chunks.filterNot{it.type in setOf("tRNS","gAMA","cHRM","sRGB","iCCP","sBIT")}
                .map{if(it.type=="PLTE")Chunk("PLTE",identity)else it}
            val encoded=ByteArrayOutputStream().apply{write(pngSignature);chunks.forEach{writeChunk(this,it)}}.toByteArray()
            val bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.size,BitmapFactory.Options().apply{inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888})
                ?:throw IOException("Cannot decode MNG event mask")
            return try{
                requireFormat(bitmap.width==frame.w && bitmap.height==frame.h,"MNG event mask dimensions mismatch")
                val row=IntArray(frame.w);val mask=ByteArray(frame.w*frame.h)
                for(y in 0 until frame.h){interrupted();bitmap.getPixels(row,0,frame.w,0,y,frame.w,1)
                    for(x in row.indices)mask[y*frame.w+x]=row[x].toByte()}
                mask
            }finally{bitmap.recycle()}
        }
        private fun over(source:Int,destination:Int):Int {
            val sa=source ushr 24;if(sa==255)return source;if(sa==0)return destination
            val da=destination ushr 24;val inverse=255-sa
            val alpha=sa*255+da*inverse
            fun channel(shift:Int)=(((((source ushr shift) and 255)*sa*255+((destination ushr shift) and 255)*da*inverse+alpha/2)/alpha) shl shift)
            return (((alpha+127)/255) shl 24) or channel(16) or channel(8) or channel(0)
        }
    }
    private fun writeChunk(out:ByteArrayOutputStream,chunk:Chunk){
        val tag=chunk.type.toByteArray(Charsets.US_ASCII)
        DataOutputStream(out).apply{writeInt(chunk.data.size);write(tag);write(chunk.data)
            writeInt(CRC32().apply{update(tag);update(chunk.data)}.value.toInt())}
    }
    private fun checkSize(w:Int,h:Int)=requireFormat(w>0 && h>0 && w.toLong()*h<=MAX_PIXELS,"Animation exceeds 4 megapixel limit")
    private fun dimension(b:ByteArray,at:Int,zero:Boolean=false):Int {
        val n=u32(b,at);requireFormat(n in (if(zero)0L else 1L)..Int.MAX_VALUE.toLong(),"Invalid animation dimension");return n.toInt()
    }
    private fun u16(b:ByteArray,p:Int)=((b[p].toInt() and 255) shl 8) or (b[p+1].toInt() and 255)
    private fun u32(b:ByteArray,p:Int)=(0..3).fold(0L){n,i->(n shl 8) or (b[p+i].toLong() and 255)}
    private fun put32(b:ByteArray,p:Int,value:Int){repeat(4){b[p+it]=(value ushr (24-it*8)).toByte()}}
    private fun requireFormat(value:Boolean,message:String){if(!value)throw IOException(message)}
    private fun interrupted(){if(Thread.currentThread().isInterrupted)throw InterruptedIOException("PNG animation cancelled")}
}
