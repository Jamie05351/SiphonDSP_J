package app.siphondsp.activity

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import app.siphondsp.R
import app.siphondsp.databinding.ActivityParametricEqBinding
import app.siphondsp.fragment.ParametricEqualizerFragment
import com.google.android.material.button.MaterialButtonToggleGroup
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
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Keep Graph/List as a local PEQ control in the top-right of the content area.
        // The graph/editor cards themselves are constrained to the left DSP nav rail,
        // never to this toggle, so moving the toggle cannot collapse their width.
        binding.root.post { movePeqModeToggleToContent() }
    }

    private fun movePeqModeToggleToContent() {
        val cards = findViewById<ConstraintLayout>(R.id.cards) ?: return
        val modeTabs = findViewById<MaterialButtonToggleGroup>(R.id.mode_tab_strip) ?: return
        val crossNav = findViewById<LinearLayout>(R.id.peq_cross_nav)
        val previewCard = findViewById<ViewGroup>(R.id.preview_card)
        val editCard = findViewById<ViewGroup>(R.id.edit_card)

        (modeTabs.parent as? ViewGroup)?.removeView(modeTabs)
        modeTabs.orientation = LinearLayout.HORIZONTAL

        for (index in 0 until modeTabs.childCount) {
            val child = modeTabs.getChildAt(index)
            val params = (child.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(dp(44), dp(40))
            params.width = dp(44)
            params.height = dp(40)
            params.bottomMargin = 0
            child.layoutParams = params
        }

        cards.addView(
            modeTabs,
            ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = dp(6)
                marginEnd = dp(22)
            },
        )

        crossNav?.let { nav ->
            val params = nav.layoutParams as? ConstraintLayout.LayoutParams ?: return@let
            params.topToBottom = ConstraintLayout.LayoutParams.UNSET
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.topMargin = 0
            nav.layoutParams = params
        }

        // These used to start after mode_tab_strip. Once that control moved to the right,
        // that constraint collapsed the content width. Anchor both content cards to the nav.
        val contentStartId = crossNav?.id ?: ConstraintLayout.LayoutParams.PARENT_ID
        listOf(previewCard, editCard).forEach { card ->
            card ?: return@forEach
            val params = card.layoutParams as? ConstraintLayout.LayoutParams ?: return@forEach
            params.startToEnd = if (contentStartId == ConstraintLayout.LayoutParams.PARENT_ID) {
                ConstraintLayout.LayoutParams.UNSET
            } else {
                contentStartId
            }
            params.startToStart = if (contentStartId == ConstraintLayout.LayoutParams.PARENT_ID) {
                ConstraintLayout.LayoutParams.PARENT_ID
            } else {
                ConstraintLayout.LayoutParams.UNSET
            }
            card.layoutParams = params
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
