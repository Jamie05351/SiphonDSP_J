package app.siphondsp.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * A FrameLayout whose children never receive touches. Used to host read-only displays (the front
 * page's PEQ graph) so a stray tap or drag over them does nothing, and the gesture falls through
 * to whatever is underneath instead.
 */
class NoTouchFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false
}
