package com.sticktv.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel, Int) -> Unit,
    private val onLeft: () -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var playingUrl: String? = null

    fun submit(list: List<Channel>, favs: Set<String>, playing: String?) {
        items = list
        favorites = favs
        playingUrl = playing
        notifyDataSetChanged()
    }

    fun setFavorites(favs: Set<String>) {
        favorites = favs
    }

    fun setPlaying(url: String?) {
        if (url == playingUrl) return
        val old = playingUrl
        playingUrl = url
        items.forEachIndexed { i, ch ->
            if (ch.url == old || ch.url == url) notifyItemChanged(i)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.channelNumber)
        val logo: ImageView = view.findViewById(R.id.channelLogo)
        val name: TextView = view.findViewById(R.id.channelName)
        val fav: TextView = view.findViewById(R.id.channelFav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        val vh = VH(view)
        view.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p])
        }
        view.setOnLongClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(items[p], p)
            true
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                onLeft()
                true
            } else {
                false
            }
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.number.text = ch.number.toString()
        holder.name.text = ch.name
        holder.fav.visibility = if (ch.url in favorites) View.VISIBLE else View.GONE
        holder.itemView.isActivated = ch.url == playingUrl
        LogoLoader.load(holder.logo, ch.logo)
    }

    override fun getItemCount(): Int = items.size
}
