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
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspSwipePager
import app.siphondsp.view.RoutingDiagramView
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

class RoutingFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val values = NativeBmwDspValues.load(requireContext())
        val host = FrameLayout(requireContext())
        val persist: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }

        fun page(build: CrossoverDashboardBuilder.() -> Unit): View = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
            CrossoverDashboardBuilder(requireContext(), this, values, persist).build()
        }

        val pager = DspSwipePager.create(host, listOf(
            {
                page {
                    sectionCard(
                        "SIGNAL PATH",
                        "Swipe left for Low and Mid routing",
                    ) {
                        addCustomView(RoutingDiagramView(requireContext()))
                        addCustomView(TextView(requireContext()).apply {
                            text = "Each output can also carry up to two cascaded all-pass filters for phase/time alignment."
                            textSize = 11.5f
                            setTextColor(Color.rgb(178, 187, 198))
                            setPadding(0, dp(2), 0, dp(8))
                        })
                        addCustomView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            text = getString(R.string.action_output_allpass)
                            isAllCaps = false
                            setOnClickListener {
                                startActivity(Intent(requireContext(), CrossoverTiltActivity::class.java).apply {
                                    putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_ALLPASS)
                                })
                            }
                        }, topMarginDp = 0, bottomMarginDp = 2)
                    }
                }
            },
            {
                page {
                    sectionCard("LOW ROUTING", "Swipe right for Signal Path or left for Mid Routing") {
                        addSliderRow("Input L → Low Left", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                        addSliderRow("Input R → Low Left", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
                        addSliderRow("Input L → Low Right", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                        addSliderRow("Input R → Low Right", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
                    }
                }
            },
            {
                page {
                    sectionCard("MID ROUTING", "Swipe right for Low Routing") {
                        addSliderRow("Input L → Mid Left", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                        addSliderRow("Input R → Mid Left", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
                        addSliderRow("Input L → Mid Right", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
                        addSliderRow("Input R → Mid Right", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
                    }
                }
            },
        ))
        host.addView(pager)
        return host
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
