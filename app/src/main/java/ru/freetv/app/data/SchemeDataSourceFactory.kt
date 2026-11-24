package ru.freetv.app.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.UdpDataSource
import java.io.IOException

/**
 * http/https -> HTTP-источник (с заголовками CDN),
 * udp/rtp -> UdpDataSource. Это позволяет принимать multicast MPEG-TS.
 */
@OptIn(UnstableApi::class)
class SchemeDataSourceFactory(
    private val httpFactory: DataSource.Factory
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        SchemeDataSource(httpFactory.createDataSource())

    private class SchemeDataSource(
        private val http: DataSource
    ) : DataSource {

        private var active: DataSource? = null
        private var listener: TransferListener? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listener = transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            val source = if (scheme == "udp" || scheme == "rtp") {
                UdpDataSource()
            } else {
                http
            }
            active = source
            listener?.let { source.addTransferListener(it) }
            val spec = if (scheme == "rtp") {
                dataSpec.withUri(dataSpec.uri.buildUpon().scheme("udp").build())
            } else {
                dataSpec
            }
            return source.open(spec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: -1

        override fun getUri(): Uri? = active?.uri

        override fun close() {
            try {
                active?.close()
            } catch (_: IOException) {
            } finally {
                active = null
            }
        }

        override fun getResponseHeaders(): Map<String, List<String>> =
            active?.responseHeaders ?: emptyMap()
    }
}
