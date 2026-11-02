package com.infinifolder.preview

import android.content.Context
import android.graphics.Typeface

/** Same CP437 cells (see bundled font8x8 provenance) as the bitmap previews, exposed for native selectable text. */
object CanvasFonts {
    @Volatile private var cp437Font: Typeface? = null
    fun cp437(context: Context): Typeface = cp437Font ?: synchronized(this) {
        cp437Font ?: Typeface.createFromAsset(context.applicationContext.assets,"fonts/cp437.ttf").also { cp437Font=it }
    }
}
