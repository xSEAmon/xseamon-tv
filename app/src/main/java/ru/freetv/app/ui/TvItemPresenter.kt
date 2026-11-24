package ru.freetv.app.ui

import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import coil.request.CachePolicy
import ru.freetv.app.R
import ru.freetv.app.data.Epg
import ru.freetv.app.util.Quality

class TvItemPresenter(
    private val onClick: (TvItem) -> Unit,
    private val onLongClick: (TvItem) -> Unit = {}
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_channel, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        val density = view.resources.displayMetrics.density
        view.setOnFocusChangeListener { focusedView, focused ->
            focusedView.animate().cancel()
            focusedView.animate()
                .scaleX(if (focused) 1.08f else 1f)
                .scaleY(if (focused) 1.08f else 1f)
                .setDuration(120L)
                .start()
            focusedView.elevation = if (focused) 8 * density else 0f
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        runCatching { bind(viewHolder, item) }
            .onFailure { Log.e("AntonTV", "TV card bind failed", it) }
    }

    private fun bind(viewHolder: ViewHolder, item: Any) {
        val view = viewHolder.view
        val tvItem = item as TvItem
        if (!view.isFocused) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.elevation = 0f
        }

        val logo = view.findViewById<ImageView>(R.id.card_logo)
        val initial = view.findViewById<TextView>(R.id.card_initial)
        val title = view.findViewById<TextView>(R.id.card_title)
        val badge = view.findViewById<TextView>(R.id.card_badge)
        val quality = view.findViewById<TextView>(R.id.card_quality)
        val action = view.findViewById<TextView>(R.id.card_action)
        val now = view.findViewById<TextView>(R.id.card_now)
        val fav = view.findViewById<TextView>(R.id.card_fav)
        val archive = view.findViewById<TextView>(R.id.card_archive)

        when (tvItem) {
            is TvItem.Chan -> {
                val channel = tvItem.channel
                title.text = channel.name
                badge.visibility = View.VISIBLE
                badge.text = channel.number.toString()
                initial.visibility = View.VISIBLE
                initial.text = initials(channel.name)
                logo.visibility = View.VISIBLE
                action.visibility = View.GONE
                fav.visibility = if (channel.favorite) View.VISIBLE else View.GONE
                archive.visibility = if (channel.hasArchive) View.VISIBLE else View.GONE

                if (channel.quality >= 720) {
                    quality.visibility = View.VISIBLE
                    quality.text = Quality.label(channel.quality)
                } else {
                    quality.visibility = View.GONE
                }

                bindLogo(logo, initial, channel.logo, view)

                val current = Epg.current(channel)
                if (current != null) {
                    now.text = Epg.formatTime(current.start) + " · " + current.title
                    now.visibility = View.VISIBLE
                } else {
                    now.text = ""
                    now.visibility = View.INVISIBLE
                }
            }

            is TvItem.Action -> {
                title.text = tvItem.subtitle
                badge.visibility = View.GONE
                quality.visibility = View.GONE
                initial.visibility = View.GONE
                now.visibility = View.GONE
                logo.visibility = View.GONE
                fav.visibility = View.GONE
                archive.visibility = View.GONE
                logo.tag = null
                logo.setImageDrawable(null)
                action.visibility = View.VISIBLE
                action.text = tvItem.title
            }
        }

        view.setOnClickListener { onClick(tvItem) }
        view.setOnLongClickListener {
            onLongClick(tvItem)
            true
        }
    }

    private fun bindLogo(
        logo: ImageView,
        initial: TextView,
        url: String?,
        root: View
    ) {
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
        val width = root.resources.getDimensionPixelSize(R.dimen.card_width)
        val height = root.resources.getDimensionPixelSize(R.dimen.card_thumb_height)
        logo.load(url) {
            size(width, height)
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

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val view = viewHolder.view
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.elevation = 0f
        val logo = view.findViewById<ImageView>(R.id.card_logo)
        logo.setImageDrawable(null)
        logo.tag = null
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
    }

    private fun initials(name: String): String =
        name.split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
}
