package ru.freetv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.freetv.app.util.Prefs
import java.io.File
import java.util.concurrent.TimeUnit

class ChannelRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val preferred: Map<String, List<String>> by lazy { loadPreferred() }

    private val noAudioKeys: Set<String> by lazy { loadNoAudio() }

    private fun loadNoAudio(): Set<String> = runCatching {
        val text = appContext.assets.open("noaudio.json")
            .bufferedReader().use { it.readText() }
        val array = JSONObject(text).optJSONArray("keys") ?: return emptySet()
        val result = HashSet<String>()
        for (i in 0 until array.length()) {
            val key = array.optString(i)
            if (key.isNotBlank()) result.add(key)
        }
        result
    }.getOrDefault(emptySet())

    private fun loadPreferred(): Map<String, List<String>> = runCatching {
        val text = appContext.assets.open("preferred.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(text).optJSONObject("channels") ?: return emptyMap()
        val result = HashMap<String, List<String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = root.optJSONArray(key) ?: continue
            val urls = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val url = array.optString(i)
                if (url.startsWith("http")) urls.add(url)
            }
            if (urls.isNotEmpty()) result[key] = urls
        }
        result
    }.getOrDefault(emptyMap())

    suspend fun load(forceRefresh: Boolean): Catalog = withContext(Dispatchers.IO) {
        buildCatalog(loadPlaylistText(forceRefresh))
    }

    /** Быстрая загрузка без сети: кэш прошлого обновления или встроенный плейлист. */
    suspend fun loadLocal(): Catalog = withContext(Dispatchers.IO) {
        val cache = cacheFile()
        val text = if (cache.exists()) {
            cache.readText()
        } else {
            appContext.assets.open("ru.m3u").bufferedReader().use { it.readText() }
        }
        buildCatalog(text)
    }

    private fun cacheFile(): File =
        File(appContext.filesDir, if (prefs.sourceType == "xtream") "xtream.m3u" else "playlist.m3u")

    private fun buildCatalog(text: String): Catalog = CatalogBuilder.build(
        raw = M3uParser.parse(text).filter { raw ->
            val host = runCatching { java.net.URI(raw.url).host.orEmpty() }.getOrDefault("")
            host !in BLOCKED_HOSTS
        },
        options = CatalogBuilder.Options(
            hdOnly = prefs.hdOnly,
            hideUnstable = prefs.hideUnstable,
            hideDead = prefs.hideDead,
            isDead = prefs::isDead,
            preferred = preferred,
            favorites = prefs.favorites(),
            order = prefs.channelOrder(),
            noAudioKeys = noAudioKeys,
            hideNoAudio = prefs.hideNoAudio,
            hiddenGroups = if (prefs.showAllCategories) emptySet() else CatalogBuilder.JUNK_GROUPS
        ),
        updatedAt = System.currentTimeMillis()
    )

    private fun loadPlaylistText(forceRefresh: Boolean): String {
        val cache = cacheFile()
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS

        if (!forceRefresh && fresh) {
            return cache.readText()
        }

        if (prefs.sourceType == "xtream") {
            return try {
                val client = XtreamClient(prefs)
                if (!client.config().isValid) error("Xtream не настроен")
                val raw = client.fetch()
                val body = client.toM3u(raw)
                cache.writeText(body)
                body
            } catch (error: Exception) {
                if (cache.exists()) cache.readText()
                else appContext.assets.open("ru.m3u").bufferedReader().use { it.readText() }
            }
        }

        return try {
            val request = Request.Builder()
                .url(prefs.playlistUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                if (!body.contains("#EXTM3U")) error("Это не M3U-плейлист")
                cache.writeText(body)
                body
            }
        } catch (error: Exception) {
            if (cache.exists()) {
                cache.readText()
            } else {
                appContext.assets.open("ru.m3u").bufferedReader().use { it.readText() }
            }
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Хосты, отдающие 403/недоступные — каналы с ними скрываются. */
        private val BLOCKED_HOSTS = setOf(
            "stream.mcquack.net",
            "fs.uplink.kz"
        )
    }
}
