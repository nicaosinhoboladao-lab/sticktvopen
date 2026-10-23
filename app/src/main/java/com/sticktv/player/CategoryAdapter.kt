package com.sticktv.player

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CategoryAdapter(
    private val onFocused: (String) -> Unit,
    private val onLongPressed: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var items: List<String> = emptyList()
    private var selected: String? = null
    private var reordering: String? = null

    fun submit(list: List<String>, selectedItem: String) {
        items = list
        selected = selectedItem
        notifyDataSetChanged()
    }

    fun setSelected(item: String) {
        val oldIdx = items.indexOf(selected)
        selected = item
        val newIdx = items.indexOf(item)
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        if (newIdx >= 0 && newIdx != oldIdx) notifyItemChanged(newIdx)
    }

    fun setReordering(item: String?) {
        val oldIndex = items.indexOf(reordering)
        reordering = item
        val newIndex = items.indexOf(item)
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false) as TextView
        val vh = VH(tv)
        tv.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val p = vh.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onFocused(items[p])
            }
        }
        tv.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onFocused(items[p])
        }
        tv.setOnLongClickListener {
            val p = vh.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) false else {
                onLongPressed(items[p])
                true
            }
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = if (item == reordering) "\u2195  $item" else item
        holder.text.isActivated = item == selected
    }

    override fun getItemCount(): Int = items.size
}