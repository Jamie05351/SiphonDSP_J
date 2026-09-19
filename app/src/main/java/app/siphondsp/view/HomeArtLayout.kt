package app.siphondsp.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Lays each child over the region of the front-page artwork named by its `android:tag` (a key of
 * [HomeArt]), using the same centerCrop mapping the backdrop ImageView uses. A child with no
 * known tag simply fills the layout. Non-clickable areas stay transparent to touch, so the
 * pager underneath still swipes.
 */
class HomeArtLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val px = pxFor(child, w, h)
            child.measure(
                MeasureSpec.makeMeasureSpec(px.right - px.left, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(px.bottom - px.top, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val px = pxFor(child, w, h)
            child.layout(px.left, px.top, px.right, px.bottom)
        }
    }

    private fun pxFor(child: View, w: Int, h: Int): HomeArt.Px {
        val frac = (child.tag as? String)?.let(HomeArt::frac)
        return if (frac != null) HomeArt.map(frac, w, h) else HomeArt.Px(0, 0, w, h)
    }
}
