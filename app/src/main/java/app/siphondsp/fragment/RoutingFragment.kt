package app.siphondsp.fragment

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import app.siphondsp.view.RoutingDiagramView
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/**
 * Dedicated Routing workspace. Swipes between two pages: the routing matrix diagram (plus the
 * Output all-pass entry point) on its own, and the 8 Output routing percentage sliders --
 * previously stacked on one scrolling page, split so the diagram isn't competing with the
 * sliders for space and doesn't need to be scrolled past to reach them.
 */
class RoutingFragment : Fragment() {
    private lateinit var container: FrameLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        this.container = FrameLayout(requireContext())
        rebuild()
        return this.container
    }

    override fun onResume() {
        super.onResume()
        // NativeBmwDspValues is loaded once into a local array and captured by closures below;
        // rebuild from disk on every resume so edits made elsewhere (Output all-pass, a restored
        // preset/profile/backup) aren't silently overwritten by this screen's stale snapshot the
        // next time a slider here is touched.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        val values = NativeBmwDspValues.load(requireContext())
        val onChanged: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }

        fun page(build: CrossoverDashboardBuilder.() -> Unit): View {
            val pageRoot = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(14))
            }
            CrossoverDashboardBuilder(requireContext(), pageRoot, values, onChanged).build()
            return NestedScrollView(requireContext()).apply {
                isFillViewport = true
                addView(pageRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val diagramPage = page {
            sectionCard("Signal path", "The routing matrix blends the two inputs into four processing bands, which are then summed back to a stereo output") {
                addCustomView(RoutingDiagramView(requireContext()))
                addCustomView(TextView(requireContext()).apply {
                    text = "Each of the four band outputs above can also carry up to two cascaded all-pass filters, for phase/time alignment between them."
                    textSize = 11.5f
                    setTextColor(Color.rgb(178, 187, 198))
                    setPadding(0, dp(2), 0, dp(10))
                })
                addCustomView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.action_output_allpass)
                    isAllCaps = false
                    setOnClickListener {
                        startActivity(Intent(requireContext(), CrossoverTiltActivity::class.java).apply {
                            putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_ALLPASS)
                        })
                        activity?.finish()
                    }
                }, topMarginDp = 0, bottomMarginDp = 4)
            }
        }

        val routingPage = page {
            sectionCard("Output routing") {
                addSliderRow("Input L → Low Left", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                addSliderRow("Input R → Low Left", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
                addSliderRow("Input L → Low Right", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                addSliderRow("Input R → Low Right", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
                addSliderRow("Input L → Mid Left", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                addSliderRow("Input R → Mid Left", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
                addSliderRow("Input L → Mid Right", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                addSliderRow("Input R → Mid Right", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
            }
        }

        container.removeAllViews()
        container.addView(
            DspPager.build(requireContext(), listOf(diagramPage, routingPage)),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
