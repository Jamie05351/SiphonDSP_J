package app.siphondsp.fragment

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CompressorSurface
import app.siphondsp.view.CompressorSurfaceMath
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import app.siphondsp.view.MbcBandGrMeter
import kotlin.math.roundToInt

/**
 * The pre-crossover multiband compressor screen: a pinned [CompressorSurface] visualiser, a
 * slim master strip (MBC enable + dry/wet mix), then a [DspPager] of four band pages
 * (enable, threshold, ratio, knee, attack, release, makeup, stereo-link + a compact GR meter)
 * and a Driver-protection page (per-bus brick-wall limiters).
 *
 * Every control writes straight into the [NativeBmwDspValues] array via
 * [CrossoverDashboardBuilder], the same index-driven card/slider builder the other DSP
 * workspace screens use. The legacy per-output compressor this screen used to edit is retired
 * and force-disabled on load (see NativeBmwDspValues.migrateDisableLegacyCompressorIfNeeded).
 */
class NativeBmwCompressorFragment : Fragment() {

    private lateinit var surface: CompressorSurface
    private lateinit var masterContainer: FrameLayout
    private lateinit var pagerContainer: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private val bandGrMeters = arrayOfNulls<MbcBandGrMeter>(NativeBmwDspValues.MBC_BAND_COUNT)
    private var lowBusGrMeter: MbcBandGrMeter? = null
    private var midBusGrMeter: MbcBandGrMeter? = null

    private val meterTick = object : Runnable {
        override fun run() {
            RootlessAudioProcessorService.nativeBmwMbcMeter()?.let { meter ->
                surface.setMbcMeter(meter)
                for (band in bandGrMeters.indices) {
                    bandGrMeters[band]?.setGainReductionDb(meter[band * 3 + 2])
                }
            }
            RootlessAudioProcessorService.nativeBmwBusLimiterMeter()?.let { meter ->
                lowBusGrMeter?.setGainReductionDb(meter[0])
                midBusGrMeter?.setGainReductionDb(meter[1])
            }
            handler.postDelayed(this, 33L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_native_bmw_compressor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        surface = view.findViewById(R.id.compressor_surface)
        BmwDashboardSkin.styleCard(view.findViewById(R.id.compressor_surface_card))
        masterContainer = view.findViewById(R.id.compressor_master_container)
        pagerContainer = view.findViewById(R.id.compressor_slider_pager_container)
    }

    override fun onStart() {
        super.onStart()
        // (Re)build from disk on every entry so edits made elsewhere -- and the load-time
        // legacy-compressor migration -- aren't shadowed by a stale snapshot; then poll meters.
        rebuild()
        handler.post(meterTick)
    }

    override fun onStop() {
        handler.removeCallbacks(meterTick)
        super.onStop()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val values = NativeBmwDspValues.load(ctx)
        val onChanged: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(ctx, updated)
            NativeBmwDspValues.broadcast(ctx, updated)
            surface.setSystemValues(updated)
        }
        surface.setSystemValues(values)

        masterContainer.removeAllViews()
        val masterRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        CrossoverDashboardBuilder(ctx, masterRoot, values, onChanged).dashboardPanel("") {
            addSegmentedSwitchRow(
                "Multiband compressor",
                "Pre-crossover, 4 bands. Off = fully bypassed.",
                NativeBmwDspValues.INDEX_MBC_ENABLED,
            )
            addSliderRow("Mix", NativeBmwDspValues.INDEX_MBC_MIX, 0f, 100f, 1f, "%")
        }
        masterContainer.addView(masterRoot)

        val splits = CompressorSurfaceMath.splitFrequencies(values)
        bandGrMeters.fill(null)
        lowBusGrMeter = null
        midBusGrMeter = null
        val pages = (0 until NativeBmwDspValues.MBC_BAND_COUNT).map { band ->
            buildBandPage(ctx, band, splits, values, onChanged)
        } + buildDriverPage(ctx, values, onChanged)

        pagerContainer.removeAllViews()
        pagerContainer.addView(DspPager.build(ctx, pages))
    }

    private fun buildBandPage(
        ctx: Context,
        band: Int,
        splits: DoubleArray,
        values: FloatArray,
        onChanged: (FloatArray) -> Unit,
    ): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val meter = MbcBandGrMeter(ctx)
        bandGrMeters[band] = meter
        val (lowHz, highHz) = CompressorSurfaceMath.bandRange(band, splits)

        fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)

        CrossoverDashboardBuilder(ctx, root, values, onChanged).dashboardPanel(
            "Band ${band + 1}",
            "${lowHz.roundToInt()}–${highHz.roundToInt()} Hz",
        ) {
            addSegmentedSwitchRow("Band active", null, idx(NativeBmwDspValues.MBC_FIELD_ENABLED))
            addCustomView(meter)
            addSliderRow("Threshold", idx(NativeBmwDspValues.MBC_FIELD_THRESHOLD), -48f, 0f, .5f, "dB")
            addSliderRow("Ratio", idx(NativeBmwDspValues.MBC_FIELD_RATIO), 1f, 20f, .1f, ":1")
            addSliderRow("Soft knee", idx(NativeBmwDspValues.MBC_FIELD_KNEE), 0f, 24f, 1f, "dB")
            addSliderRow("Attack", idx(NativeBmwDspValues.MBC_FIELD_ATTACK), 1f, 200f, 1f, "ms")
            addSliderRow("Release", idx(NativeBmwDspValues.MBC_FIELD_RELEASE), 20f, 1000f, 5f, "ms")
            addSliderRow("Makeup", idx(NativeBmwDspValues.MBC_FIELD_MAKEUP), 0f, 12f, .1f, "dB")
            addSegmentedSwitchRow(
                "Stereo link",
                "Detect on max(L,R), one gain cell for both.",
                idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK),
            )
        }
        return NestedScrollView(ctx).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildDriverPage(
        ctx: Context,
        values: FloatArray,
        onChanged: (FloatArray) -> Unit,
    ): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val lowMeter = MbcBandGrMeter(ctx).also { lowBusGrMeter = it }
        val midMeter = MbcBandGrMeter(ctx).also { midBusGrMeter = it }
        CrossoverDashboardBuilder(ctx, root, values, onChanged).dashboardPanel(
            "Driver protection",
            "Brick-wall limiter on each output bus, right before the driver gain.",
        ) {
            sectionHeader("Low bus", BmwDashboardSkin.M_BLUE)
            addSegmentedSwitchRow("Limiter active", null, NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED)
            addSliderRow("Threshold", NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_THRESHOLD, -24f, 0f, .5f, "dB")
            addSliderRow("Release", NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_RELEASE, 20f, 800f, 5f, "ms")
            addCustomView(lowMeter)
            sectionHeader("Mid bus", BmwDashboardSkin.MID_BAND_YELLOW)
            addSegmentedSwitchRow("Limiter active", null, NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED)
            addSliderRow("Threshold", NativeBmwDspValues.INDEX_BUS_LIMITER_MID_THRESHOLD, -24f, 0f, .5f, "dB")
            addSliderRow("Release", NativeBmwDspValues.INDEX_BUS_LIMITER_MID_RELEASE, 20f, 800f, 5f, "ms")
            addCustomView(midMeter)
        }
        return NestedScrollView(ctx).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
