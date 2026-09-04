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
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import app.siphondsp.view.MbcBandGrMeter
import kotlin.math.roundToInt

/**
 * The pre-crossover multiband compressor screen: a full-bleed [DspPager] whose first page is
 * the [CompressorSurface] visualiser plus a slim master strip (MBC enable + dry/wet mix),
 * followed by four band pages (enable, threshold, ratio, knee, attack, release, makeup,
 * stereo-link + a compact GR meter) and a Driver-protection page (per-bus brick-wall limiters).
 *
 * The visualiser used to be a pinned card above the pager, but on a short head-unit display
 * that left each band page almost no room; giving it its own page lets every page use the full
 * content area.
 *
 * Every control writes straight into the [NativeBmwDspValues] array via
 * [CrossoverDashboardBuilder], the same index-driven card/slider builder the other DSP
 * workspace screens use. The legacy per-output compressor this screen used to edit is retired
 * and force-disabled on load (see NativeBmwDspValues.migrateDisableLegacyCompressorIfNeeded).
 */
class NativeBmwCompressorFragment : Fragment() {

    private var surface: CompressorSurface? = null
    private lateinit var pagerContainer: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private val bandGrMeters = arrayOfNulls<MbcBandGrMeter>(NativeBmwDspValues.MBC_BAND_COUNT)
    private var lowBusGrMeter: MbcBandGrMeter? = null
    private var midBusGrMeter: MbcBandGrMeter? = null

    private val meterTick = object : Runnable {
        override fun run() {
            RootlessAudioProcessorService.nativeBmwMbcMeter()?.let { meter ->
                surface?.setMbcMeter(meter)
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
            surface?.setSystemValues(updated)
        }

        bandGrMeters.fill(null)
        lowBusGrMeter = null
        midBusGrMeter = null
        surface = null

        val pages = buildList {
            add(buildVisualiserPage(ctx, values, onChanged))
            for (band in 0 until NativeBmwDspValues.MBC_BAND_COUNT) {
                add(buildBandPage(ctx, band, values, onChanged))
            }
            add(buildDriverPage(ctx, values, onChanged))
        }

        pagerContainer.removeAllViews()
        pagerContainer.addView(
            DspPager.build(
                ctx,
                pages,
                toggleContainer = requireActivity().findViewById(R.id.dsp_page_toggle_slot),
            ),
        )
    }

    /** Page 1: the pinned-no-more [CompressorSurface] plus the slim MBC enable + mix strip. */
    private fun buildVisualiserPage(
        ctx: Context,
        values: FloatArray,
        onChanged: (FloatArray) -> Unit,
    ): View {
        val page = layoutInflater.inflate(R.layout.page_compressor_visualiser, pagerContainer, false)
        val surfaceView = page.findViewById<CompressorSurface>(R.id.compressor_surface)
        BmwDashboardSkin.styleCard(page.findViewById(R.id.compressor_surface_card))
        surface = surfaceView
        surfaceView.setSystemValues(values)

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
        page.findViewById<FrameLayout>(R.id.compressor_master_container).addView(masterRoot)
        return page
    }

    private fun buildBandPage(
        ctx: Context,
        band: Int,
        values: FloatArray,
        onChanged: (FloatArray) -> Unit,
    ): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val meter = MbcBandGrMeter(ctx)
        bandGrMeters[band] = meter

        fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)

        // Blank dashboardPanel title/subtitle: the frequency-range subheading was dropped, and
        // the band's own title now lives in titleRowWithSwitches below, alongside its enable and
        // Stereo link switches.
        CrossoverDashboardBuilder(ctx, root, values, onChanged).dashboardPanel("", null) {
            titleRowWithSwitches(
                "Band ${band + 1}",
                enabledIndex = idx(NativeBmwDspValues.MBC_FIELD_ENABLED),
                secondLabel = "Stereo link",
                secondIndex = idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK),
            )
            addCustomView(meter)
            addSliderRow("Threshold", idx(NativeBmwDspValues.MBC_FIELD_THRESHOLD), -48f, 0f, .5f, "dB")
            addSliderRow("Ratio", idx(NativeBmwDspValues.MBC_FIELD_RATIO), 1f, 20f, .1f, ":1")
            addSliderRow("Soft knee", idx(NativeBmwDspValues.MBC_FIELD_KNEE), 0f, 24f, 1f, "dB")
            addSliderRow("Attack", idx(NativeBmwDspValues.MBC_FIELD_ATTACK), 1f, 200f, 1f, "ms")
            addSliderRow("Release", idx(NativeBmwDspValues.MBC_FIELD_RELEASE), 20f, 1000f, 5f, "ms")
            addSliderRow("Makeup", idx(NativeBmwDspValues.MBC_FIELD_MAKEUP), 0f, 12f, .1f, "dB")
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
