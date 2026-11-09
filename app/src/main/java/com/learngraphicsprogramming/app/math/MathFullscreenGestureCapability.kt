package com.learngraphicsprogramming.app.math

import android.view.View

/** Permission for a future app-owned edge swipe; Android's official Back is always separate. */
interface MathFullscreenGestureCapability {
    val allowEdgeSwipeToDismiss: Boolean
}

/** Unknown surfaces are conservative: only a component declaring that it is safe opts in. */
object MathFullscreenGesturePolicy {
    fun allowEdgeSwipeToDismiss(view: View): Boolean =
        (view as? MathFullscreenGestureCapability)?.allowEdgeSwipeToDismiss == true
}

/** Direct placement owns repeated taps; expansion remains available through its button. */
internal interface MathDirectManipulationSurface
