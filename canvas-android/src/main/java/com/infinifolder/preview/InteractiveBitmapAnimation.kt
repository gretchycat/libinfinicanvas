package com.infinifolder.preview

enum class ImagePointerKind { ENTER, MOVE, EXIT, DOWN, UP }

/** Pointer coordinates are normalized to the displayed image, independent of sampling/zoom.
 * Methods which change state run on the same worker as BitmapAnimation.render. */
interface InteractiveBitmapAnimation:BitmapAnimation {
    val stateNames:List<String>
    val currentStateName:String
    fun selectState(name:String):Boolean
    fun pointerEvent(kind:ImagePointerKind,x:Float,y:Float):Boolean
}
