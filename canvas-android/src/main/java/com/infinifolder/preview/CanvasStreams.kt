package com.infinifolder.preview

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import io.github.gretchycat.infinistream.*

/** Bitmap remains a platform frame; metadata does not force a GPU/CPU byte roundtrip. */
class BitmapFrame @JvmOverloads constructor(val bitmap: Bitmap, private val ownsBitmap: Boolean = false,
    provenance: Provenance = Provenance(), warnings: List<String> = emptyList()) : StreamHandle {
    override val metadata = StreamMetadata(ContentType(DataKind.IMAGE_FRAME, "image/x-android-bitmap", "android-bitmap"),
        finiteness = Finiteness.FINITE, ownership = if (ownsBitmap) Ownership.OWNED else Ownership.BORROWED,
        attributes = buildMap {
            put("width", bitmap.width.toString()); put("height", bitmap.height.toString())
            put("pixelFormat", bitmap.config?.name ?: "unknown")
            if (android.os.Build.VERSION.SDK_INT >= 26) bitmap.colorSpace?.name?.let { put("colorSpace", it) }
        }, provenance = provenance, warnings = warnings.toList())
    private var closed = false
    override fun close() { if (!closed) { closed = true; if (ownsBitmap) bitmap.recycle() } }
}

object CanvasStreams {
    val descriptor = ProcessorDescriptor("canvas.android.decode",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("frame", setOf(DataKind.IMAGE_FRAME))), options = setOf("targetPixels"))

    /** Generic byte sources use the existing bounded stream decoder (32 MiB), never implicit unbounded spooling. */
    fun decode(resolver: ContentResolver, resource: SourceResource, targetPixels: Int): BitmapFrame? {
        val uri = Uri.Builder().scheme("infinistream").authority("image").appendPath(resource.metadata.name ?: "image").build()
        val decoder = ThumbnailDecoder(resolver) { resource.open().asInputStream() }
        return decoder.decode(uri, targetPixels)?.let { BitmapFrame(it,
            provenance = resource.metadata.provenance.copy(transformations = resource.metadata.provenance.transformations + "image.decode"), warnings = resource.metadata.warnings) }
    }
}

fun ThumbnailDecoder.decodeFrame(uri: Uri, targetPixels: Int): BitmapFrame? = decode(uri, targetPixels)?.let {
    BitmapFrame(it, provenance = Provenance(source = uri.toString(), transformations = listOf("image.decode")))
}
