package ru.freetv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.freetv.app.R
import ru.freetv.app.data.CatalogStore
import ru.freetv.app.data.Channel
import ru.freetv.app.data.DeviceProfile
import ru.freetv.app.data.Epg
import ru.freetv.app.util.Prefs

class SearchActivity : AppCompatActivity() {

    private lateinit var adapter: MobileChannelAdapter
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var prefs: Prefs
    private lateinit var profile: DeviceProfile

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var all: List<Channel> = emptyList()
    private var filtered: List<Channel> = emptyList()
    private var shown = 0
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        prefs = Prefs(this)
        profile = DeviceProfile.of(this)
        all = CatalogStore.channels()
        if (all.isEmpty()) {
            finish()
            return
        }

        input = findViewById(R.id.search_input)
        status = findViewById(R.id.search_status)
        recycler = findViewById(R.id.search_grid)

        val span = when {
            resources.configuration.screenWidthDp >= 720 -> 4
            resources.configuration.screenWidthDp >= 540 -> 3
            else -> 2
        }
        recycler.layoutManager = GridLayoutManager(this, span)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(12)

        adapter = MobileChannelAdapter(
            onClick = { openChannel(it) },
            onLongClick = { openMenu(it) }
        )
        recycler.adapter = adapter

        findViewById<View>(R.id.search_back).setOnClickListener { finish() }
        input.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            searchJob?.cancel()
            searchJob = scope.launch {
                delay(250L)
                applyFilter()
            }
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val manager = rv.layoutManager as? GridLayoutManager ?: return
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    loadMore()
                }
            }
        })

        applyFilter()
        input.requestFocus()
    }

    private fun applyFilter() {
        val normalized = Epg.normalize(query)
        filtered = if (normalized.isBlank()) {
            all
        } else {
            all.filter { channel ->
                Epg.normalize(channel.name).contains(normalized) ||
                    channel.number.toString() == query.trim() ||
                    Epg.normalize(channel.tvgId.orEmpty()).contains(normalized)
            }
        }
        shown = 0
        adapter.submit(emptyList())
        loadMore()
    }

    private fun loadMore() {
        if (shown >= filtered.size) {
            updateStatus()
            return
        }
        val end = (shown + profile.searchPage).coerceAtMost(filtered.size)
        val items = filtered.subList(shown, end).map { MobileItem.Chan(it) }
        shown = end
        val current = ArrayList<MobileItem>()
        // MobileChannelAdapter хранит только окно результатов, поэтому добавляем накопительно.
        val start = 0
        for (i in start until shown) current.add(MobileItem.Chan(filtered[i]))
        adapter.submit(current)
        updateStatus()
    }

    private fun updateStatus() {
        if (filtered.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = "Ничего не найдено"
        } else {
            status.visibility = View.GONE
        }
    }

    private fun openChannel(channel: Channel) {
        scope.launch { Epg.recordRecent(this@SearchActivity, channel.key) }
        val channels = CatalogStore.channels()
        var index = channels.indexOfFirst { it.key == channel.key }
        if (index < 0) index = 0
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
                .putExtra(PlayerActivity.EXTRA_KEY, channel.key)
        )
    }

    private fun openMenu(channel: Channel) {
        ChannelMenuDialog.show(
            context = this,
            channel = channel,
            isFavorite = prefs.isFavorite(channel.key),
            canUp = false,
            canDown = false,
            onFavorite = {
                prefs.toggleFavorite(channel.key)
                applyFilter()
            },
            onSchedule = { startActivity(EpgGridActivity.intent(this, channel.key)) },
            onUp = { },
            onDown = { },
            onTop = { },
            onGrid = { startActivity(EpgGridActivity.intent(this, channel.key)) }
        )
    }

    override fun onDestroy() {
        searchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
