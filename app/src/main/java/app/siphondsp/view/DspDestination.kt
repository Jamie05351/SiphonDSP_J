package app.siphondsp.view

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt
import kotlin.reflect.KClass

enum class DspDestination(
    @StringRes val labelRes: Int,
    @StringRes val sidebarLabelRes: Int,
    @DrawableRes val icon: Int,
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
    val showInPrimaryNav: Boolean = true,
) {
    ROUTING(R.string.action_routing, R.string.sidebar_label_routing, R.drawable.ic_twotone_route_24dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ROUTING),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
}

/** Glossy left-to-right sweep for each sidebar tile's fill -- a smooth highlight peaking around
 *  68% across, then dropping sharply toward the right edge. Grayscale gradient stops sampled
 *  directly off the reference tile design; a plain gradient, not a photo crop, since that
 *  reference gradient is one simple soft highlight, not the busier multi-streak brushed-metal
 *  texture the main workspace background uses (an earlier attempt reused that texture here and
 *  it looked nothing like the reference -- this is a genuinely different, simpler asset). */
private class TileGlossDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        paint.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
            intArrayOf(
                Color.rgb(48, 48, 48),
                Color.rgb(35, 35, 35),
                Color.rgb(117, 117, 117),
                Color.rgb(10, 10, 10),
                Color.rgb(30, 30, 30),
            ),
            floatArrayOf(0f, 0.38f, 0.68f, 0.85f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

object DspCrossNavBar {
    // Sampled from the brushed-metal reference photos' outer border stroke.
    private val SIDEBAR_BORDER_COLOR = Color.rgb(0x90, 0xA3, 0xAC)

    // ~1.78:1 width:height at the sidebar's 120dp column width, measured directly off the
    // reference tile image -- these are noticeably wider-than-tall pills, not tall rectangles.
    private const val TILE_HEIGHT_DP = 67

    fun populate(
        activity: FragmentActivity,
        container: LinearLayout,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        // No background here: the panel behind the whole sidebar is painted once on the shared
        // dsp_sidebar parent by DspWorkspaceActivity.setUpWorkspaceSidebarActions().

        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }

        destinations.forEachIndexed { index, destination ->
            // Small fixed gap before every tile except the first.
            if (index > 0) {
                container.addView(View(activity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(8)))
            }

            val selected = destination == current

            // Brushed-metal panel + a left accent bar that lights up solid blue when selected,
            // replacing the old flat-tinted-button look. Icon and label stay white in both
            // states now -- only the accent bar's image changes -- since that's the whole
            // selected/unselected signal in the reference photos this was designed from.
            val card = MaterialCardView(activity).apply {
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                radius = activity.dp(6).toFloat()
                strokeWidth = activity.dp(1)
                setStrokeColor(ColorStateList.valueOf(SIDEBAR_BORDER_COLOR))
                cardElevation = 0f
                // The default Material3 outlined-card content padding was eating into the
                // already-tight 120dp column, leaving barely any room for the label before it
                // had to ellipsize -- this tile draws all the way to its own stroke instead.
                setContentPadding(0, 0, 0, 0)
                if (selected) {
                    isClickable = false
                } else {
                    setOnClickListener {
                        if (!canNavigate()) return@setOnClickListener
                        val intent = Intent(activity, destination.activityClass.java)
                        destination.workspaceMode?.let { intent.putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
                        activity.startActivity(intent)
                        activity.finish()
                    }
                }
            }

            val gloss = View(activity).apply { background = TileGlossDrawable() }
            card.addView(gloss, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val accentBar = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageResource(if (selected) R.drawable.sidebar_accent_bar_on else R.drawable.sidebar_accent_bar_off)
            }
            content.addView(accentBar, LinearLayout.LayoutParams(activity.dp(12), LinearLayout.LayoutParams.MATCH_PARENT))

            val icon = ImageView(activity).apply {
                setImageDrawable(ContextCompat.getDrawable(activity, destination.icon))
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            content.addView(
                icon,
                LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)).apply { leftMargin = activity.dp(8) },
            )

            val label = TextView(activity).apply {
                text = activity.getString(destination.sidebarLabelRes)
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 11f
                // Wraps instead of ellipsizing: at this column width even the pre-shortened
                // labels ("Comp", "Xover") don't reliably fit on one line once the icon and
                // accent bar are accounted for, and a mid-word "Co..." reads as broken UI.
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = activity.dp(6)
                    rightMargin = activity.dp(4)
                },
            )

            // Thinner mirror of the left accent bar on the tile's right edge -- same pill artwork,
            // narrower, matching the reference design's asymmetric fat-left/thin-right bar pairing.
            val accentBarEnd = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageResource(if (selected) R.drawable.sidebar_accent_bar_on else R.drawable.sidebar_accent_bar_off)
            }
            content.addView(accentBarEnd, LinearLayout.LayoutParams(activity.dp(3), LinearLayout.LayoutParams.MATCH_PARENT))

            card.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            container.addView(
                card,
                // Fixed height, not weighted/stretched to fill the column: the reference tiles
                // are noticeably wider than tall (~1.78:1 at this 120dp column width, measured
                // directly off the reference image), not the taller-than-wide shape stretching to
                // fill produced. Leaves empty space below the 5th tile if the column is taller
                // than 5 tiles' worth -- correct per the reference, not a bug to fill.
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(TILE_HEIGHT_DP)),
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
