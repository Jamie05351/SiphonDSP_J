package app.siphondsp.activity

import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import app.siphondsp.R
import app.siphondsp.databinding.ActivityParametricEqBinding
import app.siphondsp.fragment.ParametricEqualizerFragment
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.ParametricEqSurface
import kotlin.math.abs
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

        // Keep fragment-owned PEQ views in the hierarchy they were inflated into. Reparenting the
        // edit/preview cards into a runtime-created ViewPager2 breaks the fragment's binding and
        // lifecycle assumptions when a DSP destination is opened or left. Horizontal paging is
        // implemented as a non-consuming gesture over the existing two display modes instead.
        binding.root.post {
            BmwDashboardSkin.styleWorkspace(binding.root)
            installSafePeqSwipe()
        }
    }

    private fun installSafePeqSwipe() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return

        // The old Graph/List buttons remain as the fragment's stable state machinery but are no
        // longer part of the visible navigation rail. Swipe gestures invoke those same actions.
        findViewById<View>(R.id.mode_tab_strip)?.visibility = View.GONE

        val surface = findViewById<ParametricEqSurface>(R.id.equalizer_surface)
        val graphTargets = listOfNotNull<View>(
            surface,
            findViewById(R.id.preview_title),
        )
        val listTargets = listOfNotNull<View>(
            findViewById(R.id.edit_card_title),
            findViewById(R.id.band_list),
        )

        graphTargets.forEach { target ->
            bindNonConsumingSwipe(target, surface) { direction ->
                if (direction < 0) findViewById<View>(R.id.list_mode_tab)?.performClick()
            }
        }
        listTargets.forEach { target ->
            bindNonConsumingSwipe(target, surface) { direction ->
                if (direction > 0) findViewById<View>(R.id.graph_mode_tab)?.performClick()
            }
        }
    }

    /**
     * Observe a horizontal gesture without consuming it. This keeps RecyclerView vertical scroll
     * and ParametricEqSurface node dragging intact. A graph swipe is ignored while the surface has
     * an active PEQ/tilt draft, so a frequency drag cannot accidentally change pages.
     */
    private fun bindNonConsumingSwipe(
        view: View,
        surface: ParametricEqSurface?,
        onSwipe: (direction: Int) -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        var graphClaimedGesture = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    graphClaimedGesture = surface?.hasActiveDraft() == true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (surface?.hasActiveDraft() == true) graphClaimedGesture = true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!graphClaimedGesture &&
                        abs(dx) >= dp(72) &&
                        abs(dx) > abs(dy) * 1.35f
                    ) {
                        onSwipe(if (dx > 0f) 1 else -1)
                    }
                    graphClaimedGesture = false
                }
                MotionEvent.ACTION_CANCEL -> graphClaimedGesture = false
            }
            false
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
