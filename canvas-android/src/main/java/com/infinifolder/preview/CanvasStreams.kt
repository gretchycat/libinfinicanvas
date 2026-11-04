package com.infinifolder.preview

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import java.io.IOException
import io.github.gretchycat.infinistream.*

/** Bitmap remains a platform frame; metadata does not force a GPU/CPU byte roundtrip. */
class BitmapFrame @JvmOverloads constructor(val bitmap: Bitmap, private val ownsBitmap: Boolean = false,
    provenance: Provenance = Provenance(), warnings: List<String> = emptyList()) : StreamHandle {
    /** Optional decoded palette renderer; the existing bitmap constructor contract stays unchanged. */
    var colorCycling:IffColorCycling.Animation?=null
        internal set
    var animation:BitmapAnimation?=null
        internal set
    var animationDrawable:android.graphics.drawable.Drawable?=null
        internal set
    var animationDurationNanos:Long=0
        internal set
    override val metadata = StreamMetadata(ContentType(DataKind.IMAGE_FRAME, "image/x-android-bitmap", "android-bitmap"),
        finiteness = Finiteness.FINITE, ownership = if (ownsBitmap) Ownership.OWNED else Ownership.BORROWED,
        attributes = buildMap {
            put("width", bitmap.width.toString()); put("height", bitmap.height.toString())
            put("pixelFormat", bitmap.config?.name ?: "unknown")
            if (android.os.Build.VERSION.SDK_INT >= 26) bitmap.colorSpace?.name?.let { put("colorSpace", it) }
        }, provenance = provenance, warnings = warnings.toList())
    private var closed = false
    private var recycleOnClose = ownsBitmap

    /** Transfer the bitmap to a host (e.g. an ImageView); close then leaves its pixels intact.
     * A borrowed/no-op frame can alias the input, whose lifetime remains the caller's responsibility. */
    @Throws(IOException::class)
    fun takeBitmap(): Bitmap {
        val result = requireBitmap()
        recycleOnClose = false
        closed = true
        return result
    }
    internal fun requireBitmap(): Bitmap {
        if (closed || bitmap.isRecycled) throw IOException("Bitmap frame is closed or recycled")
        return bitmap
    }
    override fun close() { if (!closed) { closed = true; if (recycleOnClose) bitmap.recycle() } }
}

object CanvasStreams {
    val descriptor = ProcessorDescriptor("canvas.android.decode",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("frame", setOf(DataKind.IMAGE_FRAME))), options = setOf("targetPixels"))

    /** Generic byte sources use the existing bounded stream decoder (32 MiB), never implicit unbounded spooling. */
    fun decode(resolver: ContentResolver, resource: SourceResource, targetPixels: Int): BitmapFrame? {
        val uri = Uri.Builder().scheme("infinistream").authority("image").appendPath(resource.metadata.name ?: "image").build()
        val decoder = ThumbnailDecoder(resolver) { resource.open().asInputStream() }
        return decoder.decodeImage(uri, targetPixels,resource.metadata.name?.substringAfterLast('.',""),resource.metadata.content.mimeType)?.let { BitmapFrame(it,
            provenance = resource.metadata.provenance.copy(transformations = resource.metadata.provenance.transformations + "image.decode"), warnings = resource.metadata.warnings) }
    }

    /** Full viewer route retains the existing native-URI fast path, sampling and bounds fallback. */
    fun decodeViewer(resolver: ContentResolver, resource: SourceResource, uri: Uri, targetPixels: Int): BitmapFrame? =
        decodeViewer(resolver,resource,uri,targetPixels,RipScriptDecoder.Mode.EGA)

    fun decodeViewer(resolver:ContentResolver,resource:SourceResource,uri:Uri,targetPixels:Int,ripMode:RipScriptDecoder.Mode):BitmapFrame? {
        val input = resource.metadata
        val decoder = ThumbnailDecoder(resolver) { resource.open().asInputStream() }
        // A URI is only an optimization hint; it must never substitute unrelated/transformed bytes.
        val decodeUri = if (canUseNativeUri(input, uri.toString())) uri else
            Uri.Builder().scheme("infinistream").authority("image").appendPath(input.name ?: uri.lastPathSegment ?: "image").build()
        val hint=if(input.content.mimeType=="image/x-rip" || input.name?.endsWith(".rip",true)==true)"rip"
            else if(input.content.mimeType in setOf("image/ilbm","image/x-ilbm","image/x-iff") ||
                input.name?.substringAfterLast('.')?.lowercase() in setOf("iff","ilbm","lbm"))"iff"
            else if(input.content.mimeType in FlicDecoder.mimeTypes)"fli"
            else if(input.content.mimeType in setOf("image/png","image/apng"))"png"
            else if(input.content.mimeType in setOf("video/x-mng","image/x-mng","image/mng","video/mng","application/x-mng"))"mng"
            else if(input.content.mimeType=="image/gif")"gif"
            else if(input.content.mimeType=="image/webp")"webp"
            else if(input.content.mimeType=="image/avif")"avif"
            else if(input.content.mimeType in setOf("image/heif","image/heic"))"heif"
            else input.name?.substringAfterLast('.')?.lowercase()
        val frame = decoder.decodeViewerFrame(decodeUri, targetPixels,hint,ripMode,input.content.mimeType) ?: return null
        return BitmapFrame(frame.bitmap, provenance = input.provenance.copy(
            transformations = input.provenance.transformations + frame.metadata.provenance.transformations), warnings = input.warnings+frame.metadata.warnings)
            .also{it.colorCycling=frame.colorCycling;it.animation=frame.animation;it.animationDrawable=frame.animationDrawable;it.animationDurationNanos=frame.animationDurationNanos}
    }

    internal fun canUseNativeUri(input: StreamMetadata, uri: String): Boolean =
        input.provenance.source == uri && input.provenance.transformations.isEmpty()
}

fun ThumbnailDecoder.decodeFrame(uri: Uri, targetPixels: Int): BitmapFrame? = decode(uri, targetPixels)?.let {
    BitmapFrame(it, provenance = Provenance(source = uri.toString(), transformations = listOf("image.decode")))
}
