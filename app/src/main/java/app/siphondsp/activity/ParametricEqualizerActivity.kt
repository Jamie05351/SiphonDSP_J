package app.siphondsp.activity

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import app.siphondsp.R
import app.siphondsp.databinding.ActivityParametricEqBinding
import app.siphondsp.fragment.ParametricEqualizerFragment
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspOutputLevelView
import app.siphondsp.view.ParametricEqSurface
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

class ParametricEqualizerActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityParametricEqBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, ParametricEqualizerFragment.newInstance())
                .commitNow()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.root.post {
            installRealPeqPager()
            BmwDashboardSkin.styleWorkspace(binding.root)
            installOutputMeter()
        }
    }

    /**
     * Landscape PEQ uses a real ViewPager2 for Graph <-> Filter List. The old Graph/List buttons
     * are hidden; the permanent left DSP navigation column remains untouched.
     */
    private fun installRealPeqPager() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        val cards = findViewById<ConstraintLayout>(R.id.cards) ?: return
        if (cards.findViewWithTag<View>(PEQ_PAGER_TAG) != null) return

        val modeTabs = findViewById<View>(R.id.mode_tab_strip)
        val crossNav = findViewById<View>(R.id.peq_cross_nav)
        val graph = findViewById<MaterialCardView>(R.id.preview_card) ?: return
        val list = findViewById<MaterialCardView>(R.id.edit_card) ?: return

        modeTabs?.visibility = View.GONE
        graph.visibility = View.VISIBLE
        list.visibility = View.VISIBLE
        (graph.parent as? ViewGroup)?.removeView(graph)
        (list.parent as? ViewGroup)?.removeView(list)

        val pager = ViewPager2(this).apply {
            id = View.generateViewId()
            tag = PEQ_PAGER_TAG
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            adapter = ExistingViewPagerAdapter(listOf(graph, list))
            getChildAt(0)?.overScrollMode = View.OVER_SCROLL_NEVER
        }
        cards.addView(pager, ConstraintLayout.LayoutParams(0, 0))

        ConstraintSet().apply {
            clone(cards)
            connect(pager.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(pager.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(pager.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, dp(12))
            if (crossNav != null) {
                clear(crossNav.id, ConstraintSet.TOP)
                connect(crossNav.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dp(6))
                connect(pager.id, ConstraintSet.START, crossNav.id, ConstraintSet.END, dp(8))
            } else {
                connect(pager.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, dp(8))
            }
            applyTo(cards)
        }
    }

    private fun installOutputMeter() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        val surface = findViewById<ParametricEqSurface>(R.id.equalizer_surface) ?: return
        val card = findViewById<MaterialCardView>(R.id.preview_card) ?: return
        surface.showGainMeters = false
        if (card.findViewWithTag<View>(OUTPUT_METER_TAG) != null) return

        val meter = DspOutputLevelView(this).apply {
            tag = OUTPUT_METER_TAG
            background = GradientDrawable().apply {
                setColor(Color.argb(214, 10, 14, 18))
                setStroke(dp(1), Color.rgb(54, 66, 76))
                cornerRadius = dp(3).toFloat()
            }
        }
        card.addView(
            meter,
            FrameLayout.LayoutParams(dp(108), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
                marginEnd = dp(5)
            },
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private class ExistingViewPagerAdapter(
        private val pages: List<View>,
    ) : RecyclerView.Adapter<ExistingViewPagerAdapter.Holder>() {
        class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

        override fun getItemCount(): Int = pages.size
        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return Holder(frame)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.frame.removeAllViews()
            val page = pages[position]
            (page.parent as? ViewGroup)?.removeView(page)
            page.visibility = View.VISIBLE
            holder.frame.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    companion object {
        private const val OUTPUT_METER_TAG = "dsp_output_level_meter"
        private const val PEQ_PAGER_TAG = "peq_real_horizontal_pager"
    }
}
