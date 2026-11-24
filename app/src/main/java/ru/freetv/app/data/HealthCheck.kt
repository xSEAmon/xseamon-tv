package ru.freetv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Проверка доступности каналов на конкретном устройстве.
 *
 * Мёртвым считаем только явный отказ: 404/410, несуществующий домен,
 * отказ в соединении. Таймауты и 403 (геоблокировка) оставляем — они
 * могут работать в другой сети.
 */
object HealthCheck {

    private const val CONCURRENCY = 16

    private enum class Status { ALIVE, DEAD, UNKNOWN }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun run(
        channels: List<Channel>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Set<String> = coroutineScope {
        val total = channels.size
        val done = AtomicInteger(0)
        val dead: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val semaphore = Semaphore(CONCURRENCY)

        channels.map { channel ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!isAlive(channel)) dead.add(channel.key)
                }
                val current = done.incrementAndGet()
                withContext(Dispatchers.Main) { onProgress(current, total) }
            }
        }.awaitAll()

        dead
    }

    private fun isAlive(channel: Channel): Boolean {
        for (url in channel.urls.take(3)) {
            when (probe(url)) {
                Status.ALIVE -> return true
                Status.DEAD -> continue
                Status.UNKNOWN -> return true
            }
        }
        return false
    }

    private fun probe(url: String): Status {
        if (url.startsWith("udp://", true) || url.startsWith("rtp://", true)) {
            return Status.UNKNOWN
        }
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ChannelRepository.USER_AGENT)
                .header("Range", "bytes=0-2047")
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 206 -> Status.ALIVE
                    response.code == 404 || response.code == 410 -> Status.DEAD
                    else -> Status.UNKNOWN
                }
            }
        } catch (error: UnknownHostException) {
            Status.DEAD
        } catch (error: ConnectException) {
            Status.DEAD
        } catch (error: NoRouteToHostException) {
            Status.DEAD
        } catch (error: Exception) {
            // Таймаут/SSL/обрыв — не повод скрывать канал на 3 дня.
            Status.UNKNOWN
        }
    }
}
