package com.infinifolder.preview

/** A single worker owns each renderer. Hosts publish the borrowed buffer on the UI thread. */
interface BitmapAnimation {
    val width:Int
    val height:Int
    val description:String
    /** Zero for animations without a single finite timeline (e.g. independent IFF cycles). */
    val durationNanos:Long get()=0
    /** Large seeks may need multiple bounded worker passes, even while paused. */
    val needsMoreRendering:Boolean get()=false
    /** Static interactive states render only after input, rather than polling forever. */
    val needsContinuousFrames:Boolean get()=true
    fun render(elapsedNanos:Long,output:IntArray,force:Boolean=false):Boolean
}
