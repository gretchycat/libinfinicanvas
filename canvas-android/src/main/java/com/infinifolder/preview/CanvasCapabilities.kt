package com.infinifolder.preview

import android.graphics.Bitmap
import android.net.Uri
import io.github.gretchycat.infinistream.*

class ImageDecodeRequest internal constructor(internal val legacy: ThumbnailDecoder, val uri: Uri,
    val targetPixels: Int, val extension: String, internal val cachedBytes: ByteArray?, val mimeType:String?=null,
    private val read: () -> ByteArray?) {
    /** Existing Canvas bounded source policy applies to third-party decoders as well. */
    val bytes: ByteArray? by lazy(read)
}

interface ImageCapabilityHandler : CapabilityHandler {
    override fun describe()=descriptor.copy(attributes=descriptor.attributes+("presentation" to "image"))
    fun decode(request: ImageDecodeRequest): Bitmap?
}

/** Pure encoded-byte adapters need neither an Android resolver nor an application runtime. */
interface ByteImageCapabilityHandler : ImageCapabilityHandler {
    fun decode(bytes:ByteArray,targetPixels:Int,extension:String=""):Bitmap?
    override fun decode(request:ImageDecodeRequest)=request.bytes?.let{decode(it,request.targetPixels,request.extension)}
}

object CanvasCapabilities {
    val registry get() = RuntimeCapabilities.library("canvas")
    val extensions: Set<String> get() = registry.descriptors().filter { Operations.DECODE in it.operations }.flatMap { it.formats }
        .flatMap { it.extensions }.toSet()
    val thumbnailExtensions:Set<String> get()=registry.descriptors().filter{Operations.THUMBNAIL in it.operations}.flatMap{it.formats}.flatMap{it.extensions}.toSet()
    internal fun decode(request: ImageDecodeRequest, pluginsOnly: Boolean = false, operation:String=Operations.THUMBNAIL): Bitmap? {
        val metadata=StreamMetadata(content=ContentType(mimeType=request.mimeType),name="image.${request.extension}")
        val hint=CapabilityResource(metadata,request.uri.toString())
        val needsProbe=registry.descriptors().none {d -> d.formats.any {f -> request.extension in f.extensions || request.mimeType in f.mimeTypes}}
        val bytes=if(needsProbe)request.bytes else null
        val source=bytes?.let {encoded->object:SourceResource {
            override val metadata=metadata
            override fun open():ByteSource=InputStreamSource(encoded.inputStream(),metadata)
        }}
        val resource=hint.copy(source=source)
        for(entry in registry.probe(resource,operation)) {
            if(pluginsOnly && entry.descriptor.id=="canvas.builtin")continue
            val handler=entry.handler.service(ImageCapabilityHandler::class.java)?:continue
            val image=try{handler.decode(request)}catch(cancel:java.io.InterruptedIOException){throw cancel}
                catch(_:Exception){null}catch(_:LinkageError){null}
            if(image!=null)return image
        }
        return null
    }
}

/** Existing bounded decoder pipeline; extensions belong here rather than in the UI. */
class BuiltinCanvasCapabilityProvider : CapabilityProvider {
    override val library="canvas"
    override fun handlers():List<CapabilityHandler> = listOf(BuiltinImageHandler(),
        ByteImageHandler("netpbm",setOf("pbm","pgm","ppm","pnm","pam","pfm"),listOf("5031","5032","5033","5034","5035","5036","5037","5046","5066")) { b,s,_ ->NetpbmDecoder.decode(b,s) },
        ByteImageHandler("lossless",setOf("qoi","ff"),listOf("716f6966","6661726266656c64")) {b,s,_->SimpleLosslessImageDecoder.decode(b,s)},
        ByteImageHandler("tga",setOf("tga","icb","vda","vst")) {b,s,_->TgaDecoder.decode(b.inputStream(),s)},
        ProcessorCapabilityHandler(HandlerDescriptor("canvas.transform",library,"Bitmap transforms",listOf(FormatSupport(setOf("image/x-android-bitmap"))),setOf(Operations.EDIT),
            metadata=setOf("width","height","pixelFormat"),limitations=listOf("Decoded Android BitmapFrame input; crop/resize/orientation options")),ImageTransformProcessor().registration(),::bitmapAvailability),
        ProcessorCapabilityHandler(HandlerDescriptor("canvas.encode.png",library,"PNG encoder",listOf(FormatSupport(setOf("image/x-android-bitmap"))),setOf(Operations.ENCODE),
            limitations=listOf("Still Android BitmapFrame input; encoded PNG output; caller owns publication")),PngImageProcessor().registration(),::bitmapAvailability))
}

private class ByteImageHandler(id:String,extensions:Set<String>,signatures:List<String> = emptyList(),
    private val decodeBytes:(ByteArray,Int,String)->Bitmap?):ByteImageCapabilityHandler {
    override val descriptor=HandlerDescriptor("canvas.$id","canvas",id,listOf(FormatSupport(mimeTypes=when(id){
        "netpbm" -> setOf("image/x-portable-bitmap","image/x-portable-graymap","image/x-portable-pixmap","image/x-portable-anymap","image/x-portable-arbitrarymap","image/x-portable-floatmap")
        "lossless" -> setOf("image/x-qoi","image/x-farbfeld")
        "tga" -> setOf("image/x-tga")
        else -> emptySet()
    },extensions=extensions,magic=signatures.map{MagicSignature(it)})),
        setOf(Operations.DECODE,Operations.THUMBNAIL),metadata=setOf("width","height","pixels"),
        limitations=listOf("Bounded encoded bytes and pixel dimensions; Android Bitmap backend required for display"),priority=10)
    override fun availability()=bitmapAvailability()
    override fun decode(bytes:ByteArray,targetPixels:Int,extension:String)=decodeBytes(bytes,targetPixels,extension)
}

private class BuiltinImageHandler : ImageCapabilityHandler {
    override val descriptor=HandlerDescriptor("canvas.builtin","canvas","Canvas image decoders",
        listOf(FormatSupport(setOf("image/*","video/x-mng") + FlicDecoder.mimeTypes,CanvasFormats.builtInExtensions,
            magic=listOf(MagicSignature("89504e470d0a1a0a"),MagicSignature("8a4d4e470d0a1a0a"),
                MagicSignature("474946383761"),MagicSignature("474946383961")))),
        setOf(Operations.DECODE,Operations.THUMBNAIL),metadata=setOf("width","height","pixelFormat","colorSpace","warnings"),
        limitations=listOf("Platform codec support varies by Android version", "32 MiB custom input; 4 MP custom animation canvas", "MNG/FLIC and legacy graphics support documented subsets only", "Image wildcard is a candidate route; render failure returns null"),
        attributes=mapOf("presentation" to "image"))
    override fun availability()=bitmapAvailability()

    override fun decode(request:ImageDecodeRequest):Bitmap? = request.legacy.decodeLegacyCustomFormat(request.extension,request.uri,request.targetPixels,request.cachedBytes)
        ?: request.legacy.decodePlatform(request.uri,request.targetPixels,request.cachedBytes)
}

private fun bitmapAvailability():HandlerAvailability = if(android.os.Build.VERSION.SDK_INT>=26)
    HandlerAvailability(AvailabilityState.CONDITIONAL,"Android bitmap backend present; specific input/optional codec must be validated on decode",mapOf("AndroidBitmap" to true))
    else HandlerAvailability(AvailabilityState.UNAVAILABLE,"Android Bitmap runtime unavailable",mapOf("AndroidBitmap" to false))
