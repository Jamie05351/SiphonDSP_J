package app.siphondsp.adapter

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.databinding.ObservableArrayList
import androidx.databinding.ObservableList
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import app.siphondsp.R
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.view.BmwDashboardSkin
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.pow

/**
 * Each band row is a set of columns lined up under item_peq_band_list_header.xml: Channel /
 * Type / Hz / dB / Q are plain tappable text (no box), each carrying its own value with the
 * unit shown once in the header. Channel/Type open a picker; the Hz, dB and Q numbers each open
 * the value dialog and each also carry inline - / + steppers -- Hz steps by [FREQ_STEP_FACTOR]
 * (1/24 octave, multiplicative) via [onFrequencyStep], dB by 0.5 via [onGainStep], Q by 0.1 via
 * [onQStep], all committing immediately. [accentColor] tints the value text per scope
 * (Low=blue/Mid=yellow/Input Correction=null neutral). A trailing "Add filter" row
 * ([onAddClicked]) is appended while the scope has fewer than [BmwPeqState.MAX_BANDS] bands.
 */
class ParametricEqBandAdapter(val bands: ParametricEqBandList) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val dfFreq = DecimalFormat("0", DecimalFormatSymbols.getInstance())
    private val dfGain = DecimalFormat("0", DecimalFormatSymbols.getInstance())
    private val dfQ = DecimalFormat("0", DecimalFormatSymbols.getInstance())

    init {
        dfFreq.maximumFractionDigits = 1
        dfGain.maximumFractionDigits = 2
        dfQ.maximumFractionDigits = 2
    }

    /** Null renders the default/neutral glass box; set per-scope (Low=blue/Mid=yellow) by the
     *  fragment whenever the selected scope chip changes. */
    var accentColor: Int? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var onItemsChanged: ((ParametricEqBandAdapter) -> Unit)? = null
    var onDeleteClicked: ((ParametricEqBand, Int) -> Unit)? = null
    var onTypeClicked: ((ParametricEqBand, Int) -> Unit)? = null
    var onChannelClicked: ((ParametricEqBand, Int) -> Unit)? = null
    var onFrequencyClicked: ((ParametricEqBand, Int) -> Unit)? = null
    var onGainClicked: ((ParametricEqBand, Int) -> Unit)? = null
    var onQClicked: ((ParametricEqBand, Int) -> Unit)? = null
    /** Inline Hz stepper: factor is 1/[FREQ_STEP_FACTOR] (minus) or [FREQ_STEP_FACTOR] (plus),
     *  applied multiplicatively so a tap moves the same musical interval at any frequency. */
    var onFrequencyStep: ((ParametricEqBand, Int, Double) -> Unit)? = null
    /** Inline dB stepper: delta is -0.5 (minus button) or +0.5 (plus button). */
    var onGainStep: ((ParametricEqBand, Int, Double) -> Unit)? = null
    /** Inline Q stepper: delta is -0.1 (minus button) or +0.1 (plus button). */
    var onQStep: ((ParametricEqBand, Int, Double) -> Unit)? = null
    var onAddClicked: (() -> Unit)? = null

    /** UUID of the band a dialog is currently open for, so its row can show a selection outline. */
    var selectedUuid: java.util.UUID? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, bands.size)
        }

    private val callback = object : ObservableList.OnListChangedCallback<ObservableArrayList<ParametricEqBand>>() {
        @SuppressLint("NotifyDataSetChanged")
        override fun onChanged(sender: ObservableArrayList<ParametricEqBand>?) {
            notifyDataSetChanged()
            onItemsChanged()
        }

        override fun onItemRangeChanged(
            sender: ObservableArrayList<ParametricEqBand>?,
            positionStart: Int,
            itemCount: Int,
        ) {
            notifyItemRangeChanged(positionStart, itemCount)
            onItemsChanged()
        }

        override fun onItemRangeInserted(
            sender: ObservableArrayList<ParametricEqBand>?,
            positionStart: Int,
            itemCount: Int,
        ) {
            // The add-row's position shifts down by one whenever the band count changes, so the
            // whole tail (not just the inserted range) needs to be re-bound.
            notifyItemRangeChanged(positionStart, (bands.size - positionStart) + 1)
            onItemsChanged()
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun onItemRangeMoved(
            sender: ObservableArrayList<ParametricEqBand>?,
            fromPosition: Int,
            toPosition: Int,
            itemCount: Int,
        ) {
            notifyDataSetChanged()
            onItemsChanged()
        }

        override fun onItemRangeRemoved(
            sender: ObservableArrayList<ParametricEqBand>?,
            positionStart: Int,
            itemCount: Int,
        ) {
            // Same tail-shift reasoning as onItemRangeInserted, plus the add-row may need to
            // reappear if this removal brought the scope back under MAX_BANDS.
            notifyItemRangeChanged(positionStart, (bands.size - positionStart) + 1)
            onItemsChanged()
        }
    }

    private fun onItemsChanged() {
        onItemsChanged?.invoke(this)
    }

    class BandViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.index)
        val type: TextView = view.findViewById(R.id.type)
        val channel: TextView = view.findViewById(R.id.channel)
        val freq: TextView = view.findViewById(R.id.freq)
        val freqMinus: MaterialButton = view.findViewById(R.id.freq_minus)
        val freqPlus: MaterialButton = view.findViewById(R.id.freq_plus)
        val gain: TextView = view.findViewById(R.id.gain)
        val gainMinus: MaterialButton = view.findViewById(R.id.gain_minus)
        val gainPlus: MaterialButton = view.findViewById(R.id.gain_plus)
        val qFactor: TextView = view.findViewById(R.id.q_factor)
        val qMinus: MaterialButton = view.findViewById(R.id.q_minus)
        val qPlus: MaterialButton = view.findViewById(R.id.q_plus)
        val deleteButton: MaterialButton = view.findViewById(R.id.delete)
        val selectionOutline: View = view.findViewById(R.id.selection_outline)
    }

    class AddRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.add_row_root)
        val button: MaterialButton = view.findViewById(R.id.add_row_button)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        bands.addOnListChangedCallback(callback)
        super.onAttachedToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        bands.removeOnListChangedCallback(callback)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /** True while the current scope still has room for another filter -- the only condition
     *  that decides whether the trailing add row is shown at all. */
    private fun hasAddRow() = bands.size < BmwPeqState.MAX_BANDS

    override fun getItemViewType(position: Int) =
        if (position < bands.size) VIEW_TYPE_BAND else VIEW_TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_ADD) {
            AddRowViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_peq_band_list_add, parent, false)
            )
        } else {
            BandViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_peq_band_list, parent, false)
            )
        }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddRowViewHolder) {
            // Same solid-accent template as a selected band row: filled with the per-scope accent
            // (neutral blue when the scope has none), foreground colour picked for contrast.
            val fill = accentColor ?: BmwDashboardSkin.LIGHT_BLUE
            val fg = contrastOn(fill)
            holder.button.backgroundTintList = ColorStateList.valueOf(fill)
            holder.button.setTextColor(fg)
            holder.button.iconTint = ColorStateList.valueOf(fg)
            holder.button.setOnClickListener { onAddClicked?.invoke() }
            holder.root.setOnClickListener { onAddClicked?.invoke() }
            return
        }

        holder as BandViewHolder
        val context = holder.itemView.context
        holder.deleteButton.isEnabled = true
        val band = bands[position]
        val accent = accentColor

        val typeLabel = filterTypeLabel(context, band.filterType)
        val channelLabel = band.channel.displayLabel
        val freqText = dfFreq.format(band.frequency)
        val gainText = dfGain.format(band.gain)
        val qText = dfQ.format(band.q)
        holder.index.text = "${position + 1}"
        holder.type.text = typeLabel
        holder.channel.text = channelLabel
        // Values carry no unit -- HZ / dB / Q live in the sticky column header. Keep the units
        // in the row's spoken description so the list still reads correctly with TalkBack.
        holder.itemView.contentDescription =
            "Filter ${position + 1}, $typeLabel, channel $channelLabel, " +
                "$freqText hertz, $gainText decibels, Q $qText"
        holder.freq.text = freqText
        holder.gain.text = gainText
        holder.qFactor.text = qText

        // The row you're working on is filled solid with the per-scope accent (see
        // ParametricEqualizerFragment: any cell tap or -/+ step selects that band). The thin
        // left outline is superseded by the fill, so it stays hidden.
        val isSelected = band.uuid == selectedUuid
        holder.selectionOutline.visibility = View.INVISIBLE
        val fill = accent ?: BmwDashboardSkin.LIGHT_BLUE
        if (isSelected) holder.itemView.setBackgroundColor(fill) else holder.itemView.background = null

        val valueColor = if (isSelected) contrastOn(fill) else (accent ?: BmwDashboardSkin.LIGHT_BLUE)
        listOf(holder.type, holder.channel, holder.freq, holder.gain, holder.qFactor).forEach {
            it.setTextColor(valueColor)
        }
        holder.index.setTextColor(if (isSelected) contrastOn(fill) else INDEX_COLOR)
        // -/+ steppers track the value colour so they stay legible on the solid highlight fill.
        val stepperTint = ColorStateList.valueOf(valueColor)
        listOf(
            holder.freqMinus, holder.freqPlus, holder.gainMinus,
            holder.gainPlus, holder.qMinus, holder.qPlus,
        ).forEach { it.iconTint = stepperTint }
        // Delete stays red on its own; the icon-button style keeps no background of its own.
        holder.deleteButton.iconTint = ColorStateList.valueOf(BmwDashboardSkin.M_RED)

        holder.deleteButton.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it >= 0 && it < bands.size }?.let { pos ->
                bands.getOrNull(pos)?.let { onDeleteClicked?.invoke(it, pos) }
            }
        }
        holder.type.setOnClickListener { withBoundBand(holder) { b, pos -> onTypeClicked?.invoke(b, pos) } }
        holder.channel.setOnClickListener { withBoundBand(holder) { b, pos -> onChannelClicked?.invoke(b, pos) } }
        holder.freq.setOnClickListener { withBoundBand(holder) { b, pos -> onFrequencyClicked?.invoke(b, pos) } }
        holder.freqMinus.setOnClickListener { withBoundBand(holder) { b, pos -> onFrequencyStep?.invoke(b, pos, 1.0 / FREQ_STEP_FACTOR) } }
        holder.freqPlus.setOnClickListener { withBoundBand(holder) { b, pos -> onFrequencyStep?.invoke(b, pos, FREQ_STEP_FACTOR) } }
        holder.gain.setOnClickListener { withBoundBand(holder) { b, pos -> onGainClicked?.invoke(b, pos) } }
        holder.gainMinus.setOnClickListener { withBoundBand(holder) { b, pos -> onGainStep?.invoke(b, pos, -0.5) } }
        holder.gainPlus.setOnClickListener { withBoundBand(holder) { b, pos -> onGainStep?.invoke(b, pos, 0.5) } }
        holder.qFactor.setOnClickListener { withBoundBand(holder) { b, pos -> onQClicked?.invoke(b, pos) } }
        holder.qMinus.setOnClickListener { withBoundBand(holder) { b, pos -> onQStep?.invoke(b, pos, -0.1) } }
        holder.qPlus.setOnClickListener { withBoundBand(holder) { b, pos -> onQStep?.invoke(b, pos, 0.1) } }
    }

    private fun filterTypeLabel(context: android.content.Context, type: ParametricEqFilterType): String =
        context.getString(
            when (type) {
                ParametricEqFilterType.PEAKING -> R.string.peq_filter_type_peaking
                ParametricEqFilterType.LOW_SHELF -> R.string.peq_filter_type_low_shelf
                ParametricEqFilterType.HIGH_SHELF -> R.string.peq_filter_type_high_shelf
                ParametricEqFilterType.NOTCH -> R.string.peq_filter_type_notch
            }
        )

    private inline fun withBoundBand(holder: BandViewHolder, action: (ParametricEqBand, Int) -> Unit) {
        holder.bindingAdapterPosition.takeIf { it >= 0 && it < bands.size }?.let { pos ->
            bands.getOrNull(pos)?.let { action(it, pos) }
        }
    }

    override fun getItemCount() = bands.size + if (hasAddRow()) 1 else 0

    companion object {
        private const val VIEW_TYPE_BAND = 0
        private const val VIEW_TYPE_ADD = 1

        /** Neutral grey for the "#" index column, matching the header's textColorSecondary. */
        private val INDEX_COLOR = Color.rgb(0x9A, 0xA1, 0xAB)

        /** Black or white -- whichever reads better on top of [fill] (a solid accent). */
        private fun contrastOn(fill: Int): Int =
            if (ColorUtils.calculateLuminance(fill) > 0.5) Color.rgb(0x0A, 0x0B, 0x0E) else Color.WHITE

        /** One inline Hz stepper tap = 1/24 octave, applied as a multiplier so the perceived
         *  interval is the same whether the band sits at 40 Hz or 4 kHz. */
        val FREQ_STEP_FACTOR = 2.0.pow(1.0 / 24.0)
    }
}
