package ru.freetv.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.freetv.app.R
import ru.freetv.app.data.CatalogStore
import ru.freetv.app.data.Channel
import ru.freetv.app.data.ChannelRepository
import ru.freetv.app.data.DecoderMode
import ru.freetv.app.data.DecoderProfileStore
import ru.freetv.app.data.DeviceCodecSelector
import ru.freetv.app.data.DeviceProfile
import ru.freetv.app.data.Epg
import ru.freetv.app.data.MulticastController
import ru.freetv.app.data.PlaybackErrorInfo
import ru.freetv.app.data.PlaybackErrorMapper
import ru.freetv.app.data.SchemeDataSourceFactory
import ru.freetv.app.util.Prefs
import ru.freetv.app.util.Quality
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_KEY = "extra_key"
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var numberView: TextView
    private lateinit var nameView: TextView
    private lateinit var metaView: TextView
    private lateinit var nowView: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var liveBadge: TextView
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorHint: TextView
    private lateinit var errorTechnical: TextView
    private lateinit var touchLayer: View
    private lateinit var playPauseButton: TextView

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var prefs: Prefs
    private lateinit var profile: DeviceProfile
    private lateinit var decoderStore: DecoderProfileStore
    private lateinit var multicast: MulticastController

    private val resolverHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var urlIndex = 0
    private var attempt = 0
    private var decoderAttempts = 0
    private var decoderMode = DecoderMode.HW
    private var playlistActive = false
    private var lastErrorAt = 0L
    private var digitBuffer = ""
    private var ready = false
    private var lastBuffered = -1L
    private var lastErrorText = ""
    private var resumeOnStart = false
    private var userPaused = false
    private var reconnectAttempt = 0
    private var resolveJob: Job? = null
    private val triedHosts = ArrayList<String>()

    private val handler = Handler(Looper.getMainLooper())

    private val hideOverlayTask = Runnable {
        // Меню не должно «залипать» на экране, даже если фокус остался
        // на кнопке после OK. Через 5 секунд убираем и фокус, и панель.
        currentFocus?.clearFocus()
        hideOverlay()
    }
    private val digitsResetTask = Runnable { digitBuffer = "" }
    private val applyDigitsTask = Runnable { applyDigits() }
    private val abrTask = Runnable { if (ready && prefs.videoQuality == 0) applyAbr(start = false) }
    private val reconnectTask = Runnable {
        if (errorView.visibility == View.VISIBLE) {
            reconnectAttempt = 0
            urlIndex = 0
            attempt = 0
            playCurrent()
        }
    }

    private val watchdogTask = object : Runnable {
        override fun run() {
            if (!::player.isInitialized) return
            if (player.playbackState == Player.STATE_BUFFERING && player.isLoading) {
                val buffered = player.totalBufferedDuration
                if (buffered > lastBuffered + 200L) {
                    lastBuffered = buffered
                    handler.postDelayed(this, 6_000L)
                } else {
                    onPlaybackFailed(
                        PlaybackErrorInfo(
                            "Поток не загружается",
                            "Данные не приходят. Пробуем резервный источник.",
                            transient = true
                        )
                    )
                }
            } else {
                lastBuffered = player.totalBufferedDuration
                handler.postDelayed(this, 6_000L)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            bindNow()
            updateLive()
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        prefs = Prefs(this)
        profile = DeviceProfile.of(this)
        decoderStore = DecoderProfileStore(this)
        decoderMode = decoderStore.modeFor(MimeTypes.VIDEO_H264)
        multicast = MulticastController(this)

        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.overlay)
        numberView = findViewById(R.id.channel_number)
        nameView = findViewById(R.id.channel_name)
        metaView = findViewById(R.id.channel_meta)
        nowView = findViewById(R.id.channel_now)
        nowProgress = findViewById(R.id.channel_progress)
        liveBadge = findViewById(R.id.live_badge)
        errorView = findViewById(R.id.error_view)
        errorTitle = findViewById(R.id.error_title)
        errorHint = findViewById(R.id.error_hint)
        errorTechnical = findViewById(R.id.error_technical)

        touchLayer = findViewById(R.id.touch_layer)
        playPauseButton = findViewById(R.id.btn_play)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_schedule).setOnClickListener {
            startActivity(EpgGridActivity.intent(this, channels.getOrNull(index)?.key))
            showOverlay()
        }
        findViewById<View>(R.id.btn_quality).setOnClickListener {
            showQualityDialog()
            showOverlay()
        }
        findViewById<View>(R.id.btn_retry).setOnClickListener {
            errorView.visibility = View.GONE
            urlIndex = 0
            attempt = 0
            playCurrent()
        }
        playPauseButton.setOnClickListener { togglePlayback() }

        val controlIds = intArrayOf(
            R.id.btn_play,
            R.id.btn_schedule,
            R.id.btn_quality,
            R.id.btn_back,
            R.id.btn_retry
        )
        controlIds.forEach { id ->
            findViewById<View>(id).setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Перезапускаем 5-секундный таймер, но не отменяем его навсегда.
                    handler.removeCallbacks(hideOverlayTask)
                    handler.postDelayed(hideOverlayTask, 5_000L)
                }
            }
        }
        setupTouch()

        channels = CatalogStore.channels()
        if (channels.isEmpty()) {
            finish()
            return
        }

        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, channels.lastIndex)
        intent.getStringExtra(EXTRA_KEY)?.let { key ->
            val found = channels.indexOfFirst { it.key == key }
            if (found >= 0) index = found
        }

        player = createPlayer()
        playerView.player = player
        preparePlaylist(index)
        bindOverlay(channels[index])
        startWatchdog()
        hideSystemUi()
    }

    private fun createPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ChannelRepository.USER_AGENT)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(12_000)
            .setAllowCrossProtocolRedirects(true)

        val headersFactory = ResolvingDataSource.Factory(http) { spec ->
            val host = spec.uri.host.orEmpty()
            val headers = when {
                host.endsWith("cinerama.uz") -> mapOf(
                    "Referer" to "https://cinerama.uz/",
                    "Origin" to "https://cinerama.uz"
                )
                host.endsWith("thestream.cyou") -> mapOf(
                    "Referer" to "https://thestream.cyou/",
                    "Origin" to "https://thestream.cyou"
                )
                host.endsWith("rutube.ru") -> mapOf(
                    "Referer" to "https://rutube.ru/",
                    "Origin" to "https://rutube.ru"
                )
                else -> emptyMap()
            }
            if (headers.isEmpty()) spec else spec.withRequestHeaders(headers)
        }

        val dataSourceFactory = SchemeDataSourceFactory(headersFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
        }
        applyAbr(start = true)

        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(DeviceCodecSelector(decoderMode))

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.bufferMinMs,
                profile.bufferMaxMs,
                500,
                1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (player === exo) {
                            lastErrorText = PlaybackErrorMapper.technical(error)
                            handler.post { onPlaybackError(error) }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            ready = true
                            reconnectAttempt = 0
                            lastErrorText = ""
                            errorView.visibility = View.GONE
                            bindNow()
                            updateLive()
                            updateActualQuality()
                            handler.removeCallbacks(abrTask)
                            handler.postDelayed(abrTask, 10_000L)
                        }
                        updatePlayPause()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateActualQuality()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateLive()
                        updatePlayPause()
                    }
                })
            }
    }

    private fun preparePlaylist(startIndex: Int) {
        if (channels.isEmpty()) return
        val items = channels.map { channel ->
            val url = channel.urls.firstOrNull().orEmpty()
            buildMediaItem(url) ?: MediaItem.fromUri(if (url.isBlank()) "about:blank" else url)
        }
        player.setMediaItems(items, startIndex, 0)
        player.prepare()
        player.playWhenReady = true
        playlistActive = true
        ready = false
        lastBuffered = -1L
        urlIndex = 0
        updateMulticast(channels.getOrNull(startIndex)?.urls?.firstOrNull().orEmpty())
        startWatchdog()
    }

    private fun buildMediaItem(url: String): MediaItem? {
        if (url.isBlank()) return null
        val mime = when {
            url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.startsWith("udp://", true) || url.startsWith("rtp://", true) -> MimeTypes.VIDEO_MP2T
            url.contains(".ts", true) -> MimeTypes.VIDEO_MP2T
            else -> null
        }
        return MediaItem.Builder()
            .setUri(url)
            .apply { if (mime != null) setMimeType(mime) }
            .build()
    }

    private fun tune(position: Int, force: Boolean = false) {
        if (channels.isEmpty()) return
        resolveJob?.cancel()
        index = ((position % channels.size) + channels.size) % channels.size
        val channel = channels[index]
        urlIndex = channel.urls.indexOfFirst { !prefs.isBadUrl(it) }.coerceAtLeast(0)
        attempt = 0
        decoderAttempts = 0
        triedHosts.clear()
        ready = false
        lastBuffered = -1L
        lastErrorText = ""
        reconnectAttempt = 0
        handler.removeCallbacks(reconnectTask)
        errorView.visibility = View.GONE
        bindOverlay(channel)
        showOverlay()

        val url = channel.urls.getOrNull(urlIndex).orEmpty()
        if (url.isBlank()) {
            showError("Канал недоступен", "У этого канала нет источника")
            return
        }
        if (url.contains("rutube.ru/api/play/options/")) {
            playlistActive = false
            resolveRutube(url, channel.key)
            return
        }
        updateMulticast(url)
        if (!playlistActive) {
            preparePlaylist(index)
            return
        }

        val item = buildMediaItem(url)
        if (item != null) {
            val currentUri = runCatching {
                player.getMediaItemAt(index).localConfiguration?.uri?.toString()
            }.getOrNull()
            if (force || currentUri != url) player.replaceMediaItem(index, item)
            player.seekToDefaultPosition(index)
            player.playWhenReady = true
            startWatchdog()
        }
    }

    private fun playCurrent() {
        val channel = channels.getOrNull(index) ?: return
        val url = channel.urls.getOrNull(urlIndex).orEmpty()
        if (url.isBlank()) {
            showError("Канал недоступен", "Все источники этого канала не отвечают")
            return
        }
        if (url.contains("rutube.ru/api/play/options/")) {
            resolveRutube(url, channel.key)
            return
        }
        updateMulticast(url)
        errorView.visibility = View.GONE
        bindOverlay(channel)
        ready = false
        lastBuffered = -1L
        startWatchdog()

        if (playlistActive) {
            val item = buildMediaItem(url)
            if (item != null) {
                player.replaceMediaItem(index, item)
                player.seekToDefaultPosition(index)
                player.prepare()
                player.playWhenReady = true
            }
        } else {
            playUrl(url)
        }
    }

    private fun resolveRutube(apiUrl: String, requestedKey: String) {
        errorView.visibility = View.GONE
        bindOverlay(channels[index])
        resolveJob = lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", ChannelRepository.USER_AGENT)
                        .build()
                    resolverHttp.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string().orEmpty()
                        Regex("""https?://[^"\\\s]+\.m3u8[^"\\\s]*""")
                            .find(body)?.value
                    }
                }.getOrNull()
            }
            if (channels.getOrNull(index)?.key != requestedKey) return@launch
            if (resolved != null) {
                playUrl(resolved)
            } else {
                onPlaybackFailed(
                    PlaybackErrorInfo(
                        "Не удалось открыть поток",
                        "Сервис не выдал ссылку. Пробуем резервный источник.",
                        transient = true
                    )
                )
            }
        }
    }

    private fun playUrl(url: String) {
        playlistActive = false
        updateMulticast(url)
        errorView.visibility = View.GONE
        bindOverlay(channels[index])
        runCatching { Uri.parse(url).host }.getOrNull()?.let { host ->
            if (host.isNotBlank() && host !in triedHosts) triedHosts.add(host)
        }
        player.setMediaItem(buildMediaItem(url) ?: MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        ready = false
        lastBuffered = -1L
        startWatchdog()
        showOverlay()
    }

    private fun updateMulticast(url: String) {
        if (url.startsWith("udp://", true) || url.startsWith("rtp://", true)) {
            multicast.acquire()
        } else {
            multicast.release()
        }
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdogTask)
        lastBuffered = -1L
        handler.postDelayed(watchdogTask, 8_000L)
    }

    private fun onPlaybackError(error: PlaybackException) {
        val info = PlaybackErrorMapper.map(error)
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        ) {
            if (tryNextDecoder()) return
        }
        onPlaybackFailed(info)
    }

    private fun tryNextDecoder(): Boolean {
        if (decoderAttempts >= 2) return false
        val next = decoderStore.next(MimeTypes.VIDEO_H264)
        if (next == decoderMode) return false
        decoderAttempts++
        decoderMode = next
        rebuildPlayer()
        return true
    }

    private fun rebuildPlayer() {
        val old = player
        playerView.player = null
        old.release()
        player = createPlayer()
        playerView.player = player
        preparePlaylist(index)
        bindOverlay(channels[index])
    }

    private fun onPlaybackFailed(info: PlaybackErrorInfo) {
        handler.removeCallbacks(watchdogTask)
        val now = System.currentTimeMillis()
        if (now - lastErrorAt < 500L) return
        lastErrorAt = now

        val channel = channels.getOrNull(index) ?: return
        val currentUrl = channel.urls.getOrNull(urlIndex).orEmpty()

        if (info.transient && attempt < 2) {
            attempt++
            errorView.visibility = View.GONE
            handler.postDelayed({
                if (channels.getOrNull(index)?.key == channel.key && urlIndex < channel.urls.size) {
                    playCurrent()
                }
            }, 1_000L * attempt)
            return
        }

        if (urlIndex + 1 < channel.urls.size) {
            prefs.markBadUrl(currentUrl)
            urlIndex++
            attempt = 0
            playCurrent()
            return
        }

        if (hasNetwork()) prefs.markDead(channel.key)
        showError(info.title, info.hint)
        scheduleReconnect(info)
    }

    private fun scheduleReconnect(info: PlaybackErrorInfo) {
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(4)
        val delay = min(30_000L, 5_000L * reconnectAttempt)
        errorHint.text = info.hint + "\nПовтор через " + (delay / 1000) + " с"
        handler.removeCallbacks(reconnectTask)
        handler.postDelayed(reconnectTask, delay)
    }

    private fun hasNetwork(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        @Suppress("DEPRECATION")
        return manager.activeNetworkInfo?.isConnected == true
    }

    private fun zap(delta: Int) {
        if (channels.isEmpty()) return
        tune(index + delta)
    }

    private fun bindOverlay(channel: Channel) {
        numberView.text = channel.number.toString()
        nameView.text = channel.name
        metaView.text = buildMeta(channel, Quality.label(channel.quality))
        bindNow()
        updateLive()
        showOverlay()
    }

    private fun buildMeta(channel: Channel, quality: String): String {
        val parts = ArrayList<String>()
        if (quality.isNotBlank() && urlIndex == 0) parts.add(quality)
        if (channel.federal) parts.add("федеральный")
        if (channel.urls.size > 1) parts.add(channel.urls.size.toString() + " источника")
        if (urlIndex > 0) parts.add("резервный поток")
        if (channel.isMulticast) parts.add("multicast")
        if (channel.hasArchive) parts.add("архив")
        return parts.joinToString(" · ")
    }

    private fun bindNow() {
        val channel = channels.getOrNull(index) ?: return
        val current = Epg.current(channel)
        val next = Epg.next(channel)

        val text = StringBuilder()
        if (current != null) {
            text.append("Сейчас: ").append(current.title)
        }
        if (next != null) {
            if (text.isNotEmpty()) text.append("\n")
            text.append("Далее: ").append(Epg.formatTime(next.start)).append(" ").append(next.title)
        }

        nowView.text = text.toString()
        nowView.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE

        if (current != null && current.stop > current.start) {
            val now = System.currentTimeMillis()
            val progress = ((now - current.start) * 1000 / (current.stop - current.start))
                .coerceIn(0L, 1000L)
            nowProgress.progress = progress.toInt()
            nowProgress.visibility = View.VISIBLE
        } else {
            nowProgress.visibility = View.GONE
        }
    }

    private fun updateLive() {
        liveBadge.visibility = if (ready && player.isPlaying) View.VISIBLE else View.GONE
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        overlay.animate().cancel()
        overlay.alpha = 1f
        handler.removeCallbacks(hideOverlayTask)
        handler.postDelayed(hideOverlayTask, 5_000L)
    }

    private fun hideOverlay() {
        currentFocus?.clearFocus()
        overlay.animate()
            .alpha(0f)
            .setDuration(250L)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE && overlay.alpha > 0.5f) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    private fun controlsFocused(): Boolean {
        val focused = currentFocus ?: return false
        return focused.id == R.id.btn_play ||
            focused.id == R.id.btn_schedule ||
            focused.id == R.id.btn_quality ||
            focused.id == R.id.btn_back ||
            focused.id == R.id.btn_retry
    }

    private fun togglePlayback() {
        if (player.isPlaying) {
            userPaused = true
            player.pause()
        } else {
            userPaused = false
            player.play()
        }
        updatePlayPause()
        showOverlay()
    }

    private fun updatePlayPause() {
        if (::playPauseButton.isInitialized && ::player.isInitialized) {
            val playing = player.isPlaying
            playPauseButton.text = if (playing) "Пауза" else "Смотреть"
            playPauseButton.setCompoundDrawablesWithIntrinsicBounds(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                0, 0, 0
            )
        }
    }

    private fun setupTouch() {
        val touchSlop = (28 * resources.displayMetrics.density).toInt()
        val swipeThreshold = (64 * resources.displayMetrics.density).toInt()
        var downY = 0f
        var downX = 0f
        var moved = false

        touchLayer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    downX = event.x
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.y - downY) > touchSlop || abs(event.x - downX) > touchSlop) {
                        moved = true
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dy = event.y - downY
                    val dx = event.x - downX
                    when {
                        abs(dy) > swipeThreshold && abs(dy) > abs(dx) * 1.4f -> {
                            if (dy < 0) zap(1) else zap(-1)
                        }

                        !moved -> {
                            if (errorView.visibility == View.VISIBLE) {
                                urlIndex = 0
                                attempt = 0
                                playCurrent()
                            } else {
                                toggleOverlay()
                            }
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun showError(title: String, hint: String) {
        errorTitle.text = title
        errorHint.text = hint
        errorTechnical.text = lastErrorText
        errorTechnical.visibility = if (lastErrorText.isBlank()) View.GONE else View.VISIBLE
        errorView.visibility = View.VISIBLE
        liveBadge.visibility = View.GONE
        showOverlay()
    }

    private fun applyAbr(start: Boolean) {
        if (!::trackSelector.isInitialized) return
        val manual = prefs.videoQuality
        val params = trackSelector.buildUponParameters()
        if (manual > 0) {
            params.setMaxVideoSize(profile.maxVideoWidth, manual.coerceAtMost(profile.maxVideoHeight))
            params.setMaxVideoBitrate(profile.maxBitrate)
        } else {
            params.setMaxVideoSize(profile.maxVideoWidth, profile.maxVideoHeight)
            params.setMaxVideoBitrate(if (start) profile.startBitrate else profile.maxBitrate)
        }
        params.setForceHighestSupportedBitrate(false)
        trackSelector.setParameters(params)
    }

    private fun updateActualQuality() {
        if (!::player.isInitialized) return
        val channel = channels.getOrNull(index) ?: return
        var height = 0
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val trackHeight = group.getTrackFormat(i).height
                    if (trackHeight > 0) height = trackHeight
                }
            }
        }
        if (height <= 0) height = player.videoFormat?.height ?: 0
        if (height > 0) {
            val label = Quality.label(height)
            val text = if (label.isBlank()) height.toString() + "p" else label + " " + height + "p"
            metaView.text = buildMeta(channel, text)
        }
    }

    private fun showQualityDialog() {
        val heights = sortedSetOf<Int>()
        if (::player.isInitialized) {
            for (group in player.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until group.length) {
                    val height = group.getTrackFormat(i).height
                    if (height > 0) heights.add(height)
                }
            }
        }
        val sorted = heights.sortedDescending()

        if (sorted.isEmpty()) {
            AlertDialog.Builder(this, R.style.Theme_FreeTV_Dialog)
                .setTitle("Качество видео")
                .setMessage("Поток ещё загружается или доступно только одно качество.")
                .setPositiveButton("Ок", null)
                .show()
            return
        }

        val labels = ArrayList<String>()
        labels.add("Авто")
        sorted.forEach { labels.add(it.toString() + "p") }

        var checked = 0
        val current = prefs.videoQuality
        if (current > 0) {
            val position = sorted.indexOfFirst { it <= current }
            if (position >= 0) checked = position + 1
        }

        AlertDialog.Builder(this, R.style.Theme_FreeTV_Dialog)
            .setTitle("Качество видео")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                prefs.videoQuality = if (which == 0) 0 else sorted[which - 1]
                applyAbr(start = false)
                dialog.dismiss()
                showOverlay()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun onDigit(digit: String) {
        digitBuffer = (digitBuffer + digit).takeLast(3)
        handler.removeCallbacks(digitsResetTask)
        handler.removeCallbacks(applyDigitsTask)
        handler.postDelayed(applyDigitsTask, 900L)
        handler.postDelayed(digitsResetTask, 4_000L)

        showOverlay()
        numberView.text = digitBuffer
        metaView.text = "Введите номер канала"
    }

    private fun applyDigits() {
        val number = digitBuffer.toIntOrNull() ?: return
        digitBuffer = ""
        val target = channels.indexOfFirst { it.number == number }
        if (target >= 0) {
            tune(target)
        } else {
            showOverlay()
            metaView.text = "Нет канала " + number
        }
    }

    private fun openChannelMenu() {
        val channel = channels.getOrNull(index) ?: return
        ChannelMenuDialog.show(
            context = this,
            channel = channel,
            isFavorite = prefs.isFavorite(channel.key),
            canUp = false,
            canDown = false,
            onFavorite = {
                prefs.toggleFavorite(channel.key)
                bindOverlay(channel)
            },
            onSchedule = { startActivity(EpgGridActivity.intent(this, channel.key)) },
            onUp = { },
            onDown = { },
            onTop = { },
            onGrid = { startActivity(EpgGridActivity.intent(this, channel.key)) }
        )
        showOverlay()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (controlsFocused()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_BACK -> {
                    currentFocus?.clearFocus()
                    hideOverlay()
                    return true
                }
                else -> return super.onKeyDown(keyCode, event)
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                zap(1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                zap(-1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                if (errorView.visibility == View.VISIBLE) {
                    urlIndex = 0
                    attempt = 0
                    playCurrent()
                } else {
                    showOverlay()
                    playPauseButton.post { playPauseButton.requestFocus() }
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MENU -> {
                showOverlay()
                playPauseButton.post { playPauseButton.requestFocus() }
                return true
            }

            KeyEvent.KEYCODE_INFO -> {
                startActivity(EpgGridActivity.intent(this, channels.getOrNull(index)?.key))
                showOverlay()
                return true
            }

            KeyEvent.KEYCODE_PROG_BLUE,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                showQualityDialog()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayback()
                showOverlay()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (overlay.visibility == View.VISIBLE && overlay.alpha > 0.5f) {
                    hideOverlay()
                } else {
                    finish()
                }
                return true
            }
        }

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            onDigit((keyCode - KeyEvent.KEYCODE_0).toString())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            openChannelMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        channels = CatalogStore.channels()
        if (channels.isEmpty()) return
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, channels.lastIndex)
        intent.getStringExtra(EXTRA_KEY)?.let { key ->
            val found = channels.indexOfFirst { it.key == key }
            if (found >= 0) index = found
        }
        tune(index, force = true)
    }

    override fun onStart() {
        super.onStart()
        handler.postDelayed(ticker, 15_000L)
        if (resumeOnStart && !userPaused && ::player.isInitialized) {
            updateMulticast(channels.getOrNull(index)?.urls?.getOrNull(urlIndex).orEmpty())
            player.play()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        if (::player.isInitialized) {
            resumeOnStart = player.isPlaying
            player.pause()
        }
        multicast.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
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
        resolveJob?.cancel()
        multicast.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
