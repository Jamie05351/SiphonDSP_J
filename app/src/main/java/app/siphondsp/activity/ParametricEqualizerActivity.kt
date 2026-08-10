package app.siphondsp.activity

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import app.siphondsp.R
import app.siphondsp.databinding.ActivityParametricEqBinding
import app.siphondsp.fragment.ParametricEqualizerFragment
import app.siphondsp.view.BmwDashboardSkin
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

        // The existing left Graph/List icons remain visible and authoritative. The page headers
        // add a second, touch-first path between those same two states. We deliberately keep the
        // gesture off the response graph itself because horizontal filter dragging changes Hz.
        binding.root.post {
            BmwDashboardSkin.styleWorkspace(binding.root)
            bindPeqPageSwipe(findViewById(R.id.preview_title))
            bindPeqPageSwipe(findViewById(R.id.edit_card_title))
        }
    }

    private fun bindPeqPageSwipe(view: View?) {
        view ?: return
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) >= dp(72) && abs(dx) > abs(dy) * 1.35f) {
                        if (dx < 0f) {
                            findViewById<View>(R.id.list_mode_tab)?.performClick()
                        } else {
                            findViewById<View>(R.id.graph_mode_tab)?.performClick()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
