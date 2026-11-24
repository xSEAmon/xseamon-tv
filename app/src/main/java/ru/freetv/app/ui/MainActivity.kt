package ru.freetv.app.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.freetv.app.R
import ru.freetv.app.data.Catalog
import ru.freetv.app.data.CatalogRow
import ru.freetv.app.data.CatalogStore
import ru.freetv.app.data.Channel
import ru.freetv.app.data.ChannelRepository
import ru.freetv.app.data.DeviceProfile
import ru.freetv.app.data.Epg
import ru.freetv.app.data.HealthCheck
import ru.freetv.app.util.Prefs

class MainActivity : AppCompatActivity() {

    private enum class UiMode { TV, PHONE }

    private lateinit var prefs: Prefs
    private lateinit var repository: ChannelRepository
    private lateinit var profile: DeviceProfile
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var skeleton: View
    private lateinit var numberOverlay: TextView

    private var mode = UiMode.TV
    private var catalog: Catalog? = null
    private var lastClickAt = 0L
    private var immersive = true
    private var healthRunning = false
    private var tvFallback = false
    private var skeletonAnimator: ObjectAnimator? = null
    private var digitBuffer = ""
    private var lastSelectedPosition = -1

    private var browseFragment: BrowseSupportFragment? = null
    private var rowsAdapter: ArrayObjectAdapter? = null
    private var mobileAdapter: MobileChannelAdapter? = null

    private val rowChannels = HashMap<String, List<Channel>>()
    private val rowAdapters = HashMap<String, ArrayObjectAdapter>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refreshVisibleRows()
            handler.postDelayed(this, 60_000L)
        }
    }

    private val digitsReset = Runnable {
        digitBuffer = ""
        numberOverlay.visibility = View.GONE
    }

    private val applyDigits = Runnable { applyDigitBuffer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        repository = ChannelRepository(this)
        profile = DeviceProfile.of(this)
        mode = resolveMode()

        @Suppress("DEPRECATION")
        val versionCode = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionCode
        }.getOrDefault(0)
        if (prefs.lastVersion != versionCode) {
            prefs.lastVersion = versionCode
            prefs.clearDead()
            prefs.clearBadUrls()
            prefs.healthChecked = false
        }

        if (mode == UiMode.TV) {
            val tvOk = runCatching {
                setContentView(R.layout.activity_main)
                setupTv()
            }.onFailure {
                Log.e("AntonTV", "TV UI setup failed", it)
            }.isSuccess
            if (!tvOk) {
                // Не даём приложению упасть на телефоне/проблемной прошивке:
                // откатываемся на обычную сетку, но режим остаётся TV.
                tvFallback = true
                setContentView(R.layout.activity_mobile)
                setupPhone()
            }
        } else {
            setContentView(R.layout.activity_mobile)
            setupPhone()
        }

        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        subtitle = findViewById(R.id.top_subtitle)
        skeleton = findViewById(R.id.skeleton)
        numberOverlay = findViewById(R.id.number_overlay)

        findViewById<View>(R.id.btn_search)?.setOnClickListener { openSearch() }
        // В fallback-режиме на телефоне это обычная сетка, поэтому кнопки
        // должны работать независимо от выбранного «TV/Phone».
        findViewById<View>(R.id.btn_refresh)?.setOnClickListener { load(force = true) }
        findViewById<View>(R.id.btn_check)?.setOnClickListener { checkChannels() }
        findViewById<View>(R.id.btn_settings)?.setOnClickListener { openSettings() }

        val hasTouch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        immersive = mode == UiMode.TV && !hasTouch

        val hint = findViewById<TextView>(R.id.top_hint)
        if (hint != null) {
            hint.text = if (hasTouch && !hasLeanback) "Тап — канал · поиск сверху"
            else "OK — канал · ↑↓ — ряд · цифры — номер"
            if (resources.configuration.screenWidthDp < 480) hint.visibility = View.GONE
        }

        startSkeleton()
        loadInitial()
    }

    private fun resolveMode(): UiMode = when (prefs.uiMode) {
        "tv" -> if (hasLeanback()) UiMode.TV else UiMode.PHONE
        "phone" -> UiMode.PHONE
        else -> {
            val touch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            val leanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            if (touch && !leanback) UiMode.PHONE else UiMode.TV
        }
    }

    private fun hasLeanback(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun setupTv() {
        val fragment = BrowseSupportFragment()
        fragment.headersState = BrowseSupportFragment.HEADERS_ENABLED
        fragment.isHeadersTransitionOnBackEnabled = true
        fragment.brandColor = ContextCompat.getColor(this, R.color.bg)
        supportFragmentManager.beginTransaction()
            .replace(R.id.rows_container, fragment)
            .commitNow()
        browseFragment = fragment
    }

    private fun ensureMobileAdapter(): MobileChannelAdapter {
        var adapter = mobileAdapter
        if (adapter == null) {
            adapter = MobileChannelAdapter(
                onClick = { onChannelClick(it) },
                onLongClick = { openChannelMenu(it) }
            )
            mobileAdapter = adapter
        }
        return adapter
    }

    private fun setupPhone() {
        val recycler = findViewById<RecyclerView>(R.id.grid)
        val span = spanCount()
        val layoutManager = GridLayoutManager(this, span)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (mobileAdapter?.isHeader(position) == true) span else 1
        }
        recycler.layoutManager = layoutManager
        recycler.itemAnimator = null
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(12)
        recycler.adapter = ensureMobileAdapter()
    }

    /**
     * Страховка для прошивок/телефонов, где Leanback-фрагмент падает.
     * Каталог остаётся рабочим в виде D-pad-сетки, приложение не закрывается.
     */
    private fun setupTvFallback() {
        browseFragment?.let { fragment ->
            runCatching {
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitNowAllowingStateLoss()
            }
        }
        browseFragment = null
        val container = findViewById<FrameLayout>(R.id.rows_container)
        container.removeAllViews()
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, spanCount())
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(12)
            adapter = ensureMobileAdapter()
        }
        container.addView(
            recycler,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun spanCount(): Int {
        val width = resources.configuration.screenWidthDp
        return when {
            width >= 720 -> 4
            width >= 540 -> 3
            else -> 2
        }
    }

    private fun startSkeleton() {
        skeleton.visibility = View.VISIBLE
        progress.visibility = View.GONE
        status.visibility = View.GONE
        skeletonAnimator?.cancel()
        skeletonAnimator = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 0.45f, 1f).apply {
            duration = 900L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopSkeleton() {
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        skeleton.visibility = View.GONE
    }

    private fun loadInitial() {
        status.visibility = View.GONE
        scope.launch {
            val quick = runCatching { repository.loadLocal() }
            quick.onSuccess {
                CatalogStore.set(it)
                val recent = loadRecent()
                show(it, recent)
            }

            val full = runCatching { repository.load(forceRefresh = false) }
            full.onSuccess { loaded ->
                val current = catalog
                if (current == null || current.total != loaded.total || current.hd != loaded.hd) {
                    CatalogStore.set(loaded)
                    val recent = loadRecent()
                    show(loaded, recent)
                }
                refreshEpg(loaded, force = false)
            }.onFailure {
                stopSkeleton()
                if (catalog == null) {
                    status.visibility = View.VISIBLE
                    status.text = "Нет доступа к плейлисту.\nПроверьте интернет или источник в настройках."
                }
            }
        }
    }

    private fun load(force: Boolean) {
        startSkeleton()
        scope.launch {
            val result = runCatching { repository.load(force) }
            result.onSuccess { loaded ->
                CatalogStore.set(loaded)
                val recent = loadRecent()
                show(loaded, recent)
                refreshEpg(loaded, force = force)
            }.onFailure { error ->
                stopSkeleton()
                status.visibility = View.VISIBLE
                status.text = "Не удалось загрузить каналы\n" + (error.message ?: "")
            }
        }
    }

    private suspend fun loadRecent(): List<Channel> {
        return runCatching {
            Epg.recent(this@MainActivity, limit = 20, offset = 0)
                .mapNotNull { CatalogStore.channel(it.channelKey) }
                .distinctBy { it.key }
        }.getOrDefault(emptyList())
    }

    private fun show(catalog: Catalog, recent: List<Channel>) {
        this.catalog = catalog
        CatalogStore.set(catalog)
        stopSkeleton()
        status.visibility = View.GONE

        subtitle.text = buildString {
            append(catalog.total).append(" каналов")
            append(" · ").append(catalog.hd).append(" HD")
            append(" · ").append(catalog.federal).append(" федеральных")
            if (recent.isNotEmpty()) append(" · недавние")
        }

        if (mode == UiMode.TV && !tvFallback) {
            val tv = runCatching { showTv(catalog, recent) }.onFailure {
                Log.e("AntonTV", "TV UI render failed", it)
            }
            if (tv.isFailure) {
                tvFallback = true
                setupTvFallback()
                showPhoneSafe(catalog, recent)
                status.visibility = View.VISIBLE
                status.text = "Телевизорный интерфейс недоступен на этом устройстве.\n" +
                    "Показана сетка каналов."
            }
        } else {
            showPhoneSafe(catalog, recent)
        }

        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 60_000L)

        maybeAutoCheck(catalog)
    }

    private fun showTv(catalog: Catalog, recent: List<Channel>) {
        val presenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
            setShadowEnabled(false)
        }
        val adapter = ArrayObjectAdapter(presenter)
        rowChannels.clear()
        rowAdapters.clear()

        val rows = ArrayList<CatalogRow>(catalog.rows)
        if (recent.isNotEmpty()) {
            val insertAt = if (rows.firstOrNull()?.title == "Избранное") 1 else 0
            rows.add(insertAt, CatalogRow("Недавние", recent))
        }

        var federalIndex = 0
        for (row in rows) {
            if (row.title == "Федеральные каналы") federalIndex = adapter.size()
            val rowAdapter = ArrayObjectAdapter(
                TvItemPresenter(
                    onClick = { onItemClick(it) },
                    onLongClick = { item ->
                        if (item is TvItem.Chan) openChannelMenu(item.channel)
                    }
                )
            )
            rowChannels[row.title] = row.channels
            rowAdapters[row.title] = rowAdapter
            addWindow(rowAdapter, row.channels, 0, profile.rowWindow)
            adapter.add(ListRow(HeaderItem(row.title + " · " + row.channels.size), rowAdapter))
        }

        val actions = ArrayObjectAdapter(
            TvItemPresenter(
                onClick = { onItemClick(it) },
                onLongClick = { }
            )
        )
        actions.add(TvItem.Action("search", "Поиск", "Найти канал"))
        actions.add(TvItem.Action("refresh", "Обновить", "Обновить плейлист"))
        actions.add(TvItem.Action("check", "Проверить", "Проверить доступность каналов"))
        actions.add(TvItem.Action("settings", "Настройки", "Источники, HD, EPG"))
        actions.add(TvItem.Action("about", "О программе", "Антон ТВ 3.0"))
        adapter.add(ListRow(HeaderItem("Управление"), actions))

        rowsAdapter = adapter
        val fragment = browseFragment ?: return
        fragment.adapter = adapter
        fragment.setOnItemViewClickedListener { _, item, _, _ ->
            (item as? TvItem)?.let { onItemClick(it) }
        }
        fragment.setOnItemViewSelectedListener { _, item, _, _ ->
            loadMoreIfNeeded(item)
        }
        fragment.view?.post {
            val target = if (lastSelectedPosition >= 0) lastSelectedPosition else federalIndex
            runCatching { fragment.setSelectedPosition(target) }
        }
    }

    private fun addWindow(
        adapter: ArrayObjectAdapter,
        channels: List<Channel>,
        from: Int,
        count: Int
    ) {
        val end = (from + count).coerceAtMost(channels.size)
        for (i in from until end) adapter.add(TvItem.Chan(channels[i]))
    }

    private fun loadMoreIfNeeded(item: Any) {
        runCatching {
            val channel = (item as? TvItem.Chan)?.channel ?: return
            val entry = rowChannels.entries.firstOrNull { entry ->
                entry.value.any { it.key == channel.key }
            } ?: return
            val adapter = rowAdapters[entry.key] ?: return
            val row = entry.value
            if (adapter.size() >= row.size) return
            val position = adapter.indexOf(item)
            if (position < 0 || position < adapter.size() - 5) return
            addWindow(adapter, row, adapter.size(), profile.rowWindow)
        }.onFailure { Log.e("AntonTV", "TV window load failed", it) }
    }

    private fun showPhoneSafe(catalog: Catalog, recent: List<Channel>) {
        runCatching { showPhone(catalog, recent) }.onFailure {
            Log.e("AntonTV", "phone/grid render failed", it)
            status.visibility = View.VISIBLE
            status.text = "Не удалось показать список каналов"
        }
    }

    private fun showPhone(catalog: Catalog, recent: List<Channel>) {
        val items = ArrayList<MobileItem>()
        if (recent.isNotEmpty()) {
            items.add(MobileItem.Header("Недавние", recent.size))
            recent.forEach { items.add(MobileItem.Chan(it)) }
        }
        for (row in catalog.rows) {
            if (row.channels.isEmpty()) continue
            items.add(MobileItem.Header(row.title, row.channels.size))
            row.channels.forEach { items.add(MobileItem.Chan(it)) }
        }
        mobileAdapter?.submit(items)
    }

    private fun refreshVisibleRows() {
        rowsAdapter?.let { adapter ->
            if (adapter.size() > 0) adapter.notifyArrayItemRangeChanged(0, adapter.size())
        }
        mobileAdapter?.refreshEpg()
    }

    private fun maybeAutoCheck(catalog: Catalog) {
        if (prefs.healthChecked) return
        if (profile.lowRam) return
        if (catalog.total <= 20) return
        prefs.healthChecked = true
        handler.postDelayed({ checkChannels() }, 60_000L)
    }

    private fun checkChannels() {
        if (healthRunning) return
        val current = catalog ?: return
        val targets = current.allChannels.filter { !it.federal }
        if (targets.isEmpty()) return

        healthRunning = true
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "Проверяем каналы…"

        scope.launch {
            val dead = runCatching {
                HealthCheck.run(targets) { done, total ->
                    status.text = "Проверка каналов: $done / $total"
                }
            }.getOrDefault(emptySet())

            dead.forEach { prefs.markDead(it) }
            prefs.healthChecked = true
            healthRunning = false

            progress.visibility = View.GONE
            status.visibility = View.GONE

            AlertDialog.Builder(this@MainActivity, R.style.Theme_FreeTV_Dialog)
                .setTitle("Проверка завершена")
                .setMessage("Проверено: " + targets.size + "\nНедоступно: " + dead.size)
                .setPositiveButton("Ок", null)
                .show()

            reloadLocal()
        }
    }

    private fun reloadLocal() {
        scope.launch {
            val loaded = runCatching { repository.loadLocal() }.getOrNull() ?: return@launch
            CatalogStore.set(loaded)
            val recent = loadRecent()
            show(loaded, recent)
        }
    }

    private fun refreshEpg(catalog: Catalog, force: Boolean) {
        scope.launch {
            delay(2_000L)
            val sources = runCatching { Epg.sources(this@MainActivity) }.getOrDefault(emptyList())
            if (sources.isEmpty()) return@launch
            runCatching { Epg.refresh(this@MainActivity, catalog.allChannels, force = force) }
            refreshVisibleRows()
        }
    }

    private fun openSettings() {
        val oldMode = prefs.uiMode
        SettingsDialog(this, prefs) {
            if (prefs.uiMode != oldMode) recreate() else load(force = true)
        }.show()
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    private fun onItemClick(item: TvItem) {
        val now = System.currentTimeMillis()
        if (now - lastClickAt < 350L) return
        lastClickAt = now

        when (item) {
            is TvItem.Chan -> onChannelClick(item.channel)
            is TvItem.Action -> when (item.id) {
                "search" -> openSearch()
                "refresh" -> load(force = true)
                "check" -> checkChannels()
                "settings" -> openSettings()
                "about" -> showAbout()
            }
        }
    }

    private fun onChannelClick(channel: Channel) {
        scope.launch { Epg.recordRecent(this@MainActivity, channel.key) }
        openPlayer(channel)
    }

    private fun openChannelMenu(channel: Channel) {
        val current = catalog ?: return
        val row = current.rows.firstOrNull { r -> r.channels.any { it.key == channel.key } }
        val index = row?.channels?.indexOfFirst { it.key == channel.key } ?: -1
        val canUp = index > 0
        val canDown = row != null && index >= 0 && index < row.channels.size - 1

        ChannelMenuDialog.show(
            context = this,
            channel = channel,
            isFavorite = prefs.isFavorite(channel.key),
            canUp = canUp,
            canDown = canDown,
            onFavorite = {
                prefs.toggleFavorite(channel.key)
                reloadLocal()
            },
            onSchedule = { startActivity(EpgGridActivity.intent(this, channel.key)) },
            onUp = { moveChannel(channel, up = true) },
            onDown = { moveChannel(channel, up = false) },
            onTop = { moveToStart(channel) },
            onGrid = { startActivity(EpgGridActivity.intent(this, channel.key)) }
        )
    }

    private fun moveChannel(channel: Channel, up: Boolean) {
        val current = catalog ?: return
        val row = current.rows.firstOrNull { r -> r.channels.any { it.key == channel.key } } ?: return
        val index = row.channels.indexOfFirst { it.key == channel.key }
        val neighbor = row.channels.getOrNull(if (up) index - 1 else index + 1) ?: return

        val order = current.allChannels.map { it.key }.toMutableList()
        val a = order.indexOf(channel.key)
        val b = order.indexOf(neighbor.key)
        if (a < 0 || b < 0) return

        val tmp = order[a]
        order[a] = order[b]
        order[b] = tmp
        prefs.saveOrder(order)
        reloadLocal()
    }

    private fun moveToStart(channel: Channel) {
        val current = catalog ?: return
        val order = current.allChannels.map { it.key }.toMutableList()
        if (order.remove(channel.key)) order.add(0, channel.key)
        prefs.saveOrder(order)
        reloadLocal()
    }

    private fun openPlayer(channel: Channel) {
        val channels = CatalogStore.channels().ifEmpty { catalog?.allChannels ?: listOf(channel) }
        var index = channels.indexOfFirst { it.key == channel.key }
        if (index < 0) index = 0
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
                .putExtra(PlayerActivity.EXTRA_KEY, channel.key)
        )
    }

    private fun showAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        AlertDialog.Builder(this, R.style.Theme_FreeTV_Dialog)
            .setTitle("Антон ТВ" + if (version.isNotBlank()) " · v$version" else "")
            .setMessage(
                "Бесплатные российские телеканалы, включая 20 обязательных " +
                    "общедоступных федеральных каналов.\n\n" +
                    "• 3.0: профиль декодера, Xtream live, XMLTV-источники, EPG-сетка.\n" +
                    "• M3U, HTTP MPEG-TS и multicast udp://rtp://.\n" +
                    "• Избранное, недавние, поиск, номера каналов.\n\n" +
                    "Управление в плеере:\n" +
                    "↑/↓ — канал, OK — панель, долгое OK — меню,\n" +
                    "← — панель, INFO — программа,\n" +
                    "цифры — номер канала, Назад — выход."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun onDigit(digit: String) {
        digitBuffer = (digitBuffer + digit).takeLast(3)
        handler.removeCallbacks(digitsReset)
        handler.removeCallbacks(applyDigits)
        handler.postDelayed(applyDigits, 900L)
        numberOverlay.text = digitBuffer
        numberOverlay.visibility = View.VISIBLE
    }

    private fun applyDigitBuffer() {
        val number = digitBuffer.toIntOrNull() ?: return
        digitBuffer = ""
        val channel = CatalogStore.channels().firstOrNull { it.number == number }
        if (channel != null) {
            numberOverlay.visibility = View.GONE
            onChannelClick(channel)
        } else {
            numberOverlay.text = "Нет канала $number"
            handler.postDelayed(digitsReset, 1_500L)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                openSettings()
                return true
            }

            KeyEvent.KEYCODE_SEARCH -> {
                openSearch()
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            onDigit((keyCode - KeyEvent.KEYCODE_0).toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersive) hideSystemUi()
        if (hasFocus) lastSelectedPosition = browseFragment?.selectedPosition ?: lastSelectedPosition
    }

    override fun onResume() {
        super.onResume()
        // Устройство могли сменить на экране выбора — пересоздаём интерфейс.
        if (resolveMode() != mode) recreate()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        skeletonAnimator?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
