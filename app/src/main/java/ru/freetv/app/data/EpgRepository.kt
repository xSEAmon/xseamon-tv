package ru.freetv.app.data

import android.content.Context
import android.util.Xml
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import ru.freetv.app.data.db.AppDatabase
import ru.freetv.app.data.db.EpgChannelEntity
import ru.freetv.app.data.db.EpgProgrammeEntity
import ru.freetv.app.data.db.EpgSourceEntity
import ru.freetv.app.data.db.RecentEntity
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * EPG-хранилище: несколько XMLTV-источников, Room, мерж по приоритету.
 * В память попадает только окно (обычно ±6 часов), а не весь XMLTV.
 */
class EpgRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db by lazy { AppDatabase.get(appContext) }
    private val dao by lazy { db.epgDao() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val formatters = listOf(
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmmss",
        "yyyyMMddHHmm Z",
        "yyyyMMddHHmm"
    ).map { SimpleDateFormat(it, Locale.US) }

    suspend fun sources(): List<EpgSourceEntity> = withContext(Dispatchers.IO) { dao.sources() }

    suspend fun addSource(name: String, url: String, gzip: Boolean, priority: Int): Long =
        withContext(Dispatchers.IO) {
            dao.insertSource(
                EpgSourceEntity(
                    name = name.ifBlank { "XMLTV" },
                    url = url.trim(),
                    gzip = gzip,
                    priority = priority
                )
            )
        }

    suspend fun updateSource(source: EpgSourceEntity) = withContext(Dispatchers.IO) {
        dao.updateSource(source)
    }

    suspend fun deleteSource(source: EpgSourceEntity) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.clearProgrammes(source.id)
            dao.clearChannels(source.id)
            dao.deleteSource(source)
        }
    }

    suspend fun refresh(channels: List<Channel>, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val sources = dao.enabledSources()
            if (sources.isEmpty() || channels.isEmpty()) return@withContext false
            val index = ChannelIndex(channels)
            var any = false

            for (source in sources) {
                if (!force && source.lastError == null && source.lastFetchedAt > 0 &&
                    System.currentTimeMillis() - source.lastFetchedAt < CACHE_TTL_MS
                ) {
                    continue
                }
                val temp = File.createTempFile("epg-", ".xml", appContext.cacheDir)
                try {
                    download(source, temp)
                    val parsed = parse(temp, source, index)
                    db.withTransaction {
                        dao.clearProgrammes(source.id)
                        dao.clearChannels(source.id)
                        if (parsed.programmes.isNotEmpty()) dao.insertProgrammes(parsed.programmes)
                        if (parsed.channels.isNotEmpty()) dao.insertChannels(parsed.channels)
                        dao.updateSource(
                            source.copy(
                                lastFetchedAt = System.currentTimeMillis(),
                                lastError = null
                            )
                        )
                    }
                    any = true
                } catch (error: Exception) {
                    dao.updateSource(
                        source.copy(lastError = (error.message ?: "ошибка загрузки").take(180))
                    )
                } finally {
                    temp.delete()
                }
            }
            any
        }

    suspend fun loadWindow(
        channels: List<Channel>,
        from: Long,
        to: Long
    ): Map<String, List<Epg.Programme>> = withContext(Dispatchers.IO) {
        val keys = channels.map { it.key }.distinct()
        if (keys.isEmpty()) return@withContext emptyMap()
        val rows = keys.chunked(400).flatMap { chunk -> dao.programmes(chunk, from, to) }
        if (rows.isEmpty()) return@withContext emptyMap()
        val priorities = dao.sources().associate { it.id to it.priority }
        val result = HashMap<String, MutableList<Epg.Programme>>()

        for ((channelKey, list) in rows.groupBy { it.channelKey }) {
            val sorted = list.sortedWith(
                compareBy<EpgProgrammeEntity>({ it.start }, { priorities[it.sourceId] ?: 0 })
            )
            val merged = ArrayList<Epg.Programme>()
            var lastStop = Long.MIN_VALUE
            for (item in sorted) {
                if (item.stop <= lastStop) continue
                val start = if (item.start < lastStop) lastStop else item.start
                if (item.stop <= start) continue
                merged.add(Epg.Programme(start, item.stop, item.title))
                lastStop = item.stop
            }
            if (merged.isNotEmpty()) result[channelKey] = merged
        }
        result
    }

    suspend fun recent(limit: Int, offset: Int): List<RecentEntity> =
        withContext(Dispatchers.IO) { dao.recent(limit, offset) }

    suspend fun recordRecent(channelKey: String) = withContext(Dispatchers.IO) {
        dao.upsertRecent(RecentEntity(channelKey, System.currentTimeMillis()))
    }

    suspend fun clearRecent() = withContext(Dispatchers.IO) { dao.clearRecent() }

    private fun download(source: EpgSourceEntity, target: File) {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", ChannelRepository.USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("пустой ответ")
            body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
    }

    private data class ParseResult(
        val programmes: List<EpgProgrammeEntity>,
        val channels: List<EpgChannelEntity>
    )

    private fun parse(file: File, source: EpgSourceEntity, index: ChannelIndex): ParseResult {
        val programmes = ArrayList<EpgProgrammeEntity>()
        val channelRows = ArrayList<EpgChannelEntity>()
        val idToKey = HashMap<String, String>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        InputStreamReader(openStream(file, source.gzip), Charsets.UTF_8).use { reader ->
            parser.setInput(reader)

            val now = System.currentTimeMillis()
            val from = now - IMPORT_BEFORE_MS
            val to = now + IMPORT_AFTER_MS

            var event = parser.eventType
            var currentId: String? = null
            var names = ArrayList<String>()
            var progStart = 0L
            var progStop = 0L
            var progChannel: String? = null
            var inWindow = false
            var title: String? = null
            var desc: String? = null
            var category: String? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            currentId = parser.getAttributeValue(null, "id")
                            names = ArrayList()
                        }

                        "display-name" -> {
                            val text = parser.nextText().trim()
                            if (text.isNotBlank()) names.add(text)
                        }

                        "programme" -> {
                            progStart = parseTime(parser.getAttributeValue(null, "start"))
                            progStop = parseTime(parser.getAttributeValue(null, "stop"))
                            progChannel = parser.getAttributeValue(null, "channel")
                            inWindow = progStart > 0L && progStop > from && progStart < to
                            if (inWindow && progStop <= progStart) progStop = progStart + 60 * 60 * 1000L
                            title = null
                            desc = null
                            category = null
                        }

                        "title" -> if (inWindow) title = parser.nextText().trim()
                        "desc" -> if (inWindow) desc = parser.nextText().trim().takeIf { it.isNotBlank() }
                        "category" -> if (inWindow) category = parser.nextText().trim().takeIf { it.isNotBlank() }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = currentId
                            if (!id.isNullOrBlank()) {
                                val key = index.keyFor(id, names)
                                if (key != null) {
                                    idToKey[id] = key
                                    channelRows.add(
                                        EpgChannelEntity(
                                            sourceId = source.id,
                                            xmltvId = id,
                                            displayName = names.firstOrNull().orEmpty().ifBlank { id },
                                            normalizedName = Epg.normalize(names.firstOrNull() ?: id)
                                        )
                                    )
                                }
                            }
                            currentId = null
                        }

                        "programme" -> {
                            val channelId = progChannel
                            if (inWindow && channelId != null) {
                                val key = idToKey[channelId] ?: index.keyFor(channelId, emptyList())
                                if (key != null) {
                                    programmes.add(
                                        EpgProgrammeEntity(
                                            sourceId = source.id,
                                            channelKey = key,
                                            start = progStart,
                                            stop = progStop,
                                            title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
                                            desc = desc,
                                            category = category
                                        )
                                    )
                                }
                            }
                            inWindow = false
                            progChannel = null
                        }
                    }
                }
                event = parser.next()
            }
        }

        programmes.sortBy { it.start }
        return ParseResult(programmes, channelRows)
    }

    private fun openStream(file: File, forceGzip: Boolean): InputStream {
        val raw = BufferedInputStream(FileInputStream(file), 64 * 1024)
        raw.mark(2)
        val first = raw.read()
        val second = raw.read()
        raw.reset()
        return if (forceGzip || (first == 0x1f && second == 0x8b)) {
            GZIPInputStream(raw, 64 * 1024)
        } else {
            raw
        }
    }

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val value = raw.trim()
        for (formatter in formatters) {
            try {
                formatter.timeZone = TimeZone.getDefault()
                return formatter.parse(value)?.time ?: 0L
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private class ChannelIndex(channels: List<Channel>) {
        private val byId = HashMap<String, String>()
        private val byName = HashMap<String, String>()

        init {
            for (channel in channels) {
                channel.tvgId?.let { id ->
                    byId[Epg.normalize(id)] = channel.key
                    byId[Epg.normalize(id.substringBefore('@'))] = channel.key
                }
                (channel.epgNames + channel.name).forEach { name ->
                    byName[Epg.normalize(name)] = channel.key
                }
            }
        }

        fun keyFor(id: String, names: List<String>): String? {
            byId[Epg.normalize(id)]?.let { return it }
            val base = id.substringBefore('@')
            if (base != id) byId[Epg.normalize(base)]?.let { return it }
            for (name in names) {
                byName[Epg.normalize(name)]?.let { return it }
            }
            return null
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        private const val IMPORT_BEFORE_MS = 6L * 60 * 60 * 1000
        private const val IMPORT_AFTER_MS = 48L * 60 * 60 * 1000
    }
}
