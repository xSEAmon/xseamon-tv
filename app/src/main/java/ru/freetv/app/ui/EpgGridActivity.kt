package ru.freetv.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import ru.freetv.app.R
import ru.freetv.app.data.CatalogStore
import ru.freetv.app.data.Channel
import ru.freetv.app.data.DeviceProfile
import ru.freetv.app.data.Epg

class EpgGridActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHANNEL_KEY = "epg_channel_key"

        fun intent(context: Context, channelKey: String?): Intent =
            Intent(context, EpgGridActivity::class.java)
                .putExtra(EXTRA_CHANNEL_KEY, channelKey)
    }

    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var profile: DeviceProfile
    private var channelKey: String? = null
    private var from = 0L
    private var to = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg_grid)

        profile = DeviceProfile.of(this)
        channelKey = intent.getStringExtra(EXTRA_CHANNEL_KEY)
        recycler = findViewById(R.id.epg_rows)
        status = findViewById(R.id.epg_status)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(8)

        findViewById<View>(R.id.epg_back).setOnClickListener { finish() }

        val channels = CatalogStore.channels()
        if (channels.isEmpty()) {
            status.text = "Каталог ещё не загружен"
            return
        }

        val now = System.currentTimeMillis()
        val hours = profile.epgWindowHours
        from = now - hours * 60L * 60L * 1000L
        to = now + hours * 60L * 60L * 1000L

        status.text = "Загружаем программу…"
        lifecycleScope.launch {
            runCatching { Epg.loadWindow(this@EpgGridActivity, channels, hours) }
            render(channels)
        }
    }

    private fun render(channels: List<Channel>) {
        status.visibility = View.GONE
        val adapter = EpgRowAdapter(channels, from, to) { channel ->
            openChannel(channel)
        }
        recycler.adapter = adapter
        val target = channels.indexOfFirst { it.key == channelKey }
        if (target >= 0) {
            (recycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(target, 0)
        }
    }

    private fun openChannel(channel: Channel) {
        val channels = CatalogStore.channels()
        var index = channels.indexOfFirst { it.key == channel.key }
        if (index < 0) index = 0
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
                .putExtra(PlayerActivity.EXTRA_KEY, channel.key)
        )
    }

    private inner class EpgRowAdapter(
        private val channels: List<Channel>,
        private val from: Long,
        private val to: Long,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<EpgRowAdapter.Holder>() {

        private val density = resources.displayMetrics.density
        private val channelWidth = (if (profile.lowRam) 160 else 210) * density

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                isFocusable = false
            }
            val name = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(channelWidth.toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(dp(14), 0, dp(8), 0)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (profile.lowRam) 13f else 15f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            }
            val scroll = HorizontalScrollView(parent.context).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            val strip = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                isFocusable = false
            }
            scroll.addView(strip)
            row.addView(name)
            row.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return Holder(row, name, strip, scroll)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = channels[position]
            holder.name.text = channel.number.toString() + "  " + channel.name
            holder.strip.removeAllViews()
            val programmes = Epg.window(channel, from, to)
            val now = System.currentTimeMillis()
            if (programmes.isEmpty()) {
                holder.strip.addView(programmeView("Нет данных", dp(120), false, null))
            } else {
                for (programme in programmes) {
                    val minutes = ((programme.stop - programme.start) / 60_000L).coerceAtLeast(5)
                    val width = (minutes * (if (profile.lowRam) 1.8 else 2.4) * density)
                        .toInt()
                        .coerceIn(dp(96), dp(420))
                    val current = now >= programme.start && now < programme.stop
                    holder.strip.addView(
                        programmeView(
                            (if (current) "● " else "") +
                                Epg.formatTime(programme.start) + "  " + programme.title,
                            width,
                            current,
                            channel
                        )
                    )
                }
            }
            holder.row.setOnClickListener { onClick(channel) }
        }

        override fun getItemCount(): Int = channels.size

        private fun programmeView(
            text: String,
            width: Int,
            current: Boolean,
            channel: Channel?
        ): TextView = TextView(this@EpgGridActivity).apply {
            layoutParams = LinearLayout.LayoutParams(width, dp(52)).apply {
                marginEnd = dp(4)
            }
            setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (profile.lowRam) 11f else 13f)
            setTextColor(ContextCompat.getColor(context, if (current) R.color.accent else R.color.text))
            setBackgroundResource(R.drawable.schedule_row)
            isFocusable = true
            isClickable = true
            this.text = text
            if (channel != null) setOnClickListener { onClick(channel) }
        }

        inner class Holder(
            val row: LinearLayout,
            val name: TextView,
            val strip: LinearLayout,
            val scroll: HorizontalScrollView
        ) : RecyclerView.ViewHolder(row)

        private fun dp(value: Int): Int = (value * density).toInt()
    }
}
