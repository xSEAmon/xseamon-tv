package ru.freetv.app.data

import android.util.JsonReader
import ru.freetv.app.util.Prefs
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Xtream Codes — только live. Без VOD, series, archive и timeshift.
 */
class XtreamClient(private val prefs: Prefs) {

    data class Config(
        val baseUrl: String,
        val user: String,
        val password: String
    ) {
        val isValid: Boolean get() = baseUrl.isNotBlank() && user.isNotBlank() && password.isNotBlank()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun config(): Config {
        var base = prefs.xtreamHost.trim().trimEnd('/')
        if (base.isBlank()) return Config("", prefs.xtreamUser, prefs.xtreamPass)
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            base = "http://$base"
        }
        val port = prefs.xtreamPort.trim()
        val authority = base.substringAfter("://")
        if (port.isNotBlank() && !authority.contains(':')) {
            base = "$base:$port"
        }
        return Config(base, prefs.xtreamUser.trim(), prefs.xtreamPass.trim())
    }

    fun fetch(): List<RawChannel> {
        val config = config()
        if (!config.isValid) error("Заполните сервер, логин и пароль Xtream")

        val categories = loadCategories(config)
        val result = ArrayList<RawChannel>()

        val url = apiUrl(config, "get_live_streams")
        http.newCall(Request.Builder().url(url).header("User-Agent", ChannelRepository.USER_AGENT).build())
            .execute().use { response ->
                if (!response.isSuccessful) error("Xtream HTTP ${response.code}")
                val body = response.body ?: error("Xtream: пустой ответ")
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    reader.isLenient = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val item = readObject(reader)
                        val id = item["stream_id"].orEmpty()
                        if (id.isBlank()) continue
                        val name = item["name"].orEmpty().ifBlank { "Канал $id" }
                        val group = categories[item["category_id"].orEmpty()].orEmpty().ifBlank { "Xtream" }
                        val logo = item["stream_icon"]?.takeIf { it.startsWith("http") }
                        val epgId = item["epg_channel_id"]?.takeIf { it.isNotBlank() }
                        val num = item["num"]?.toIntOrNull() ?: 0

                        val hls = "${config.baseUrl}/live/${enc(config.user)}/${enc(config.password)}/$id.m3u8"
                        val ts = "${config.baseUrl}/live/${enc(config.user)}/${enc(config.password)}/$id.ts"

                        result.add(
                            RawChannel(
                                name = name,
                                url = hls,
                                logo = logo,
                                tvgId = epgId,
                                group = group,
                                unstable = false
                            )
                        )
                        result.add(
                            RawChannel(
                                name = name,
                                url = ts,
                                logo = logo,
                                tvgId = epgId,
                                group = group,
                                unstable = false
                            )
                        )
                        if (num > 0 && epgId == null) {
                            // номер сохраняется в имени-метаданных не нужен; порядок задаст каталог
                        }
                    }
                    reader.endArray()
                }
            }

        if (result.isEmpty()) error("Xtream не вернул live-каналы")
        return result
    }

    fun toM3u(channels: List<RawChannel>): String = buildString {
        appendLine("#EXTM3U")
        for (channel in channels) {
            append("#EXTINF:-1")
            channel.tvgId?.let { append(" tvg-id=\"").append(it.replace("\"", "")).append("\"") }
            channel.logo?.let { append(" tvg-logo=\"").append(it.replace("\"", "")).append("\"") }
            append(" group-title=\"").append(channel.group.replace("\"", "")).append("\",")
            appendLine(channel.name)
            appendLine(channel.url)
        }
    }

    private fun loadCategories(config: Config): Map<String, String> {
        val result = HashMap<String, String>()
        val url = apiUrl(config, "get_live_categories")
        http.newCall(Request.Builder().url(url).header("User-Agent", ChannelRepository.USER_AGENT).build())
            .execute().use { response ->
                if (!response.isSuccessful) return result
                val body = response.body ?: return result
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    reader.isLenient = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val item = readObject(reader)
                        val id = item["category_id"].orEmpty()
                        val name = item["category_name"].orEmpty()
                        if (id.isNotBlank() && name.isNotBlank()) result[id] = name
                    }
                    reader.endArray()
                }
            }
        return result
    }

    private fun apiUrl(config: Config, action: String): String =
        "${config.baseUrl}/player_api.php?username=${enc(config.user)}" +
            "&password=${enc(config.password)}&action=$action"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun readObject(reader: JsonReader): Map<String, String> {
        val result = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            result[name] = when (reader.peek()) {
                android.util.JsonToken.NULL -> {
                    reader.nextNull()
                    ""
                }
                android.util.JsonToken.BOOLEAN -> reader.nextBoolean().toString()
                android.util.JsonToken.NUMBER -> reader.nextString()
                else -> reader.nextString()
            }
        }
        reader.endObject()
        return result
    }
}
