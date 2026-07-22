package app.siphondsp.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.databinding.ObservableArrayList
import androidx.databinding.ObservableList
import androidx.recyclerview.widget.RecyclerView
import app.siphondsp.R
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

class ParametricEqBandAdapter(var bands: ParametricEqBandList) :
    RecyclerView.Adapter<ParametricEqBandAdapter.ViewHolder>() {

    private val dfFreq = DecimalFormat("0", DecimalFormatSymbols.getInstance())
    private val dfGain = DecimalFormat("0", DecimalFormatSymbols.getInstance())
    private val dfQ = DecimalFormat("0", DecimalFormatSymbols.getInstance())

    init {
        dfFreq.maximumFractionDigits = 1
        dfGain.maximumFractionDigits = 2
        dfQ.maximumFractionDigits = 2
    }

    var onItemsChanged: ((ParametricEqBandAdapter) -> Unit)? = null
    var onItemClicked: ((ParametricEqBand, Int) -> Unit)? = null

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
            notifyItemRangeInserted(positionStart, itemCount)
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
            notifyItemRangeRemoved(positionStart, itemCount)
            onItemsChanged()
        }
    }

    private fun onItemsChanged() {
        onItemsChanged?.invoke(this)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val filterType: TextView = view.findViewById(R.id.filter_type)
        val freq: TextView = view.findViewById(R.id.freq)
        val gain: TextView = view.findViewById(R.id.gain)
        val qFactor: TextView = view.findViewById(R.id.q_factor)
        val deleteButton: Button = view.findViewById(R.id.delete)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        bands.addOnListChangedCallback(callback)
        super.onAttachedToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        bands.removeOnListChangedCallback(callback)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_peq_band_list, parent, false))

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.deleteButton.isEnabled = true
        val band = bands[position]
        holder.filterType.text = "${band.filterType.displayLabel} · ${band.channel.displayLabel}"
        holder.filterType.contentDescription = "${band.filterType.displayLabel}, channel ${band.channel.displayLabel}"
        holder.freq.text = "${dfFreq.format(band.frequency)}Hz"
        holder.gain.text = "${dfGain.format(band.gain)}dB"
        holder.qFactor.text = "Q${dfQ.format(band.q)}"

        holder.deleteButton.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { bands.removeAt(it) }
            holder.deleteButton.isEnabled = false
        }
        holder.itemView.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { pos ->
                bands.getOrNull(pos)?.let { onItemClicked?.invoke(it, pos) }
            }
        }
    }

    override fun getItemCount() = bands.size
}
