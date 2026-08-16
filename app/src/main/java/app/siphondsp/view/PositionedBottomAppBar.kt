package app.siphondsp.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.ActionMenuView
import com.google.android.material.bottomappbar.BottomAppBar
import kotlin.math.roundToInt

/**
 * Bottom app bar that keeps its "more" overflow action away from the display's edge. Its centre
 * is positioned at 90% of the complete bar width, while the power FAB remains centred by
 * BottomAppBar in the cradle gap. Used to also position two full groups of quick-action icons
 * (PEQ/Gains&Delay/Settings on the left, Compressor/Crossovers/overflow on the right) before
 * those all moved to dedicated tiles on the main shortcuts grid, leaving just the one overflow
 * icon here.
 */
class PositionedBottomAppBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {

    private val positions = floatArrayOf(0.90f)

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val actionMenus = (0 until childCount)
            .map(::getChildAt)
            .filterIsInstance<ActionMenuView>()

        actionMenus.forEach { menu ->
            menu.clipChildren = false
            menu.clipToPadding = false

            // Give the menu the complete bar width so its action views can be
            // positioned using percentages of the full display width.
            menu.layout(0, 0, width, height)

            positionVisibleItems(menu, positions)
        }
    }

    private fun positionVisibleItems(menu: ActionMenuView, positions: FloatArray) {
        val items = (0 until menu.childCount)
            .map(menu::getChildAt)
            .filter { it.visibility == View.VISIBLE }

        items.take(positions.size).forEachIndexed { index, item ->
            val itemWidth = item.measuredWidth
            val itemHeight = item.measuredHeight
            val centreX = (width * positions[index]).roundToInt()
            val itemLeft = centreX - itemWidth / 2
            val itemTop = (height - itemHeight) / 2

            item.layout(
                itemLeft,
                itemTop,
                itemLeft + itemWidth,
                itemTop + itemHeight,
            )
        }
    }
}
