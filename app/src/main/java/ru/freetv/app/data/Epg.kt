package ru.freetv.app.data

import android.content.Context
import ru.freetv.app.data.db.EpgSourceEntity
import java.util.Calendar
import java.util.Locale

/**
 * Фасад EPG для UI: в памяти только текущее окно программ.
 * Полные данные лежат в Room, импорт — в EpgRepository.
 */
object Epg {

    data class Programme(
        val start: Long,
        val stop: Long,
        val title: String
    )

    @Volatile
    private var data: Map<String, List<Programme>> = emptyMap()

    @Volatile
    private var loadedAt = 0L

    val isLoaded: Boolean get() = loadedAt > 0L

    fun current(channel: Channel, now: Long = System.currentTimeMillis()): Programme? =
        lookup(channel)?.firstOrNull { now >= it.start && now < it.stop }

    fun next(channel: Channel, now: Long = System.currentTimeMillis()): Programme? =
        lookup(channel)?.firstOrNull { it.start > now && it.start - now <= 6L * 60 * 60 * 1000 }

    fun upcoming(channel: Channel, hours: Int = 24): List<Programme> {
        val now = System.currentTimeMillis()
        val until = now + hours * 60L * 60L * 1000L
        return lookup(channel)
            ?.filter { it.stop > now && it.start < until }
            ?.sortedBy { it.start }
            ?: emptyList()
    }

    fun window(channel: Channel, from: Long, to: Long): List<Programme> =
        lookup(channel)
            ?.filter { it.stop > from && it.start < to }
            ?.sortedBy { it.start }
            ?: emptyList()

    private fun lookup(channel: Channel): List<Programme>? {
        data[channel.key]?.let { return it }
        for (name in channel.epgNames) {
            data[normalize(name)]?.let { return it }
        }
        data[normalize(channel.name)]?.let { return it }
        return null
    }

    suspend fun loadWindow(
        context: Context,
        channels: List<Channel>,
        hours: Int = 6
    ) {
        if (channels.isEmpty()) return
        val now = System.currentTimeMillis()
        val from = now - hours * 60L * 60L * 1000L
        val to = now + hours * 60L * 60L * 1000L
        val loaded = EpgRepository(context).loadWindow(channels, from, to)
        data = loaded
        loadedAt = System.currentTimeMillis()
    }

    suspend fun loadCache(context: Context) {
        val channels = CatalogStore.channels()
        if (channels.isNotEmpty()) loadWindow(context, channels)
    }

    suspend fun refresh(context: Context, channels: List<Channel>, force: Boolean = false): Boolean {
        val updated = EpgRepository(context).refresh(channels, force)
        loadWindow(context, channels)
        return updated
    }

    suspend fun sources(context: Context): List<EpgSourceEntity> =
        EpgRepository(context).sources()

    suspend fun addSource(context: Context, name: String, url: String, gzip: Boolean, priority: Int) {
        EpgRepository(context).addSource(name, url, gzip, priority)
    }

    suspend fun updateSource(context: Context, source: EpgSourceEntity) {
        EpgRepository(context).updateSource(source)
    }

    suspend fun deleteSource(context: Context, source: EpgSourceEntity) {
        EpgRepository(context).deleteSource(source)
    }

    suspend fun recent(context: Context, limit: Int, offset: Int) =
        EpgRepository(context).recent(limit, offset)

    suspend fun recordRecent(context: Context, channelKey: String) {
        EpgRepository(context).recordRecent(channelKey)
    }

    fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("""[^a-zа-я0-9]"""), "")

    fun formatTime(value: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = value
        return String.format(
            Locale.US,
            "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }
}
