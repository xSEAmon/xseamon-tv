package ru.freetv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import ru.freetv.app.R
import ru.freetv.app.data.Channel
import ru.freetv.app.data.Epg
import ru.freetv.app.util.Quality

sealed class MobileItem {
    data class Header(val title: String, val count: Int) : MobileItem()
    data class Chan(val channel: Channel) : MobileItem()
}

/** Сетка каналов для телефона и поиска: 2–4 колонки, заголовки категорий. */
class MobileChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<MobileItem>()

    fun submit(newItems: List<MobileItem>) {
        val old = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val a = old[oldPos]
                val b = newItems[newPos]
                return when {
                    a is MobileItem.Header && b is MobileItem.Header -> a.title == b.title
                    a is MobileItem.Chan && b is MobileItem.Chan -> a.channel.key == b.channel.key
                    else -> false
                }
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is MobileItem.Header

    /** Обновить только видимые «Сейчас в эфире», не инвалидируя весь список. */
    fun refreshEpg() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is MobileItem.Header) TYPE_HEADER else TYPE_CHANNEL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_header_mobile, parent, false) as TextView)
        } else {
            ChannelHolder(inflater.inflate(R.layout.item_channel_mobile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> {
                val header = items[position] as MobileItem.Header
                holder.text.text = header.title + " · " + header.count
            }

            is ChannelHolder -> {
                val item = items[position] as MobileItem.Chan
                holder.bind(item.channel)
            }
        }
    }

    private class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private inner class ChannelHolder(private val view: View) : RecyclerView.ViewHolder(view) {

        private val logo: ImageView = view.findViewById(R.id.card_logo)
        private val initial: TextView = view.findViewById(R.id.card_initial)
        private val title: TextView = view.findViewById(R.id.card_title)
        private val badge: TextView = view.findViewById(R.id.card_badge)
        private val quality: TextView = view.findViewById(R.id.card_quality)
        private val now: TextView = view.findViewById(R.id.card_now)
        private val fav: TextView = view.findViewById(R.id.card_fav)
        private val archive: TextView = view.findViewById(R.id.card_archive)

        fun bind(channel: Channel) {
            runCatching { bindInner(channel) }
                .onFailure { android.util.Log.e("AntonTV", "channel card bind failed", it) }
        }

        private fun bindInner(channel: Channel) {
            title.text = channel.name
            badge.text = channel.number.toString()
            initial.text = initials(channel.name)
            initial.visibility = View.VISIBLE
            fav.visibility = if (channel.favorite) View.VISIBLE else View.GONE
            archive.visibility = if (channel.hasArchive) View.VISIBLE else View.GONE

            if (channel.quality >= 720) {
                quality.visibility = View.VISIBLE
                quality.text = Quality.label(channel.quality)
            } else {
                quality.visibility = View.GONE
            }

            bindLogo(channel.logo)

            val current = Epg.current(channel)
            if (current != null) {
                now.text = Epg.formatTime(current.start) + " · " + current.title
            } else {
                now.text = ""
            }

            view.setOnClickListener { onClick(channel) }
            view.setOnLongClickListener {
                onLongClick(channel)
                true
            }
        }

        private fun bindLogo(url: String?) {
            if (url.isNullOrBlank()) {
                logo.tag = null
                logo.setImageDrawable(null)
                logo.visibility = View.GONE
                initial.visibility = View.VISIBLE
                return
            }
            initial.visibility = View.VISIBLE
            if (logo.tag == url) return
            logo.tag = url
            logo.load(url) {
                size(320, 180)
                diskCachePolicy(CachePolicy.ENABLED)
                memoryCachePolicy(CachePolicy.ENABLED)
                crossfade(false)
                listener(
                    onSuccess = { _, _ ->
                        logo.visibility = View.VISIBLE
                        initial.visibility = View.GONE
                    },
                    onError = { _, _ ->
                        logo.setImageDrawable(null)
                        logo.visibility = View.GONE
                        initial.visibility = View.VISIBLE
                    }
                )
            }
        }

        private fun initials(name: String): String =
            name.split(' ')
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }
}
