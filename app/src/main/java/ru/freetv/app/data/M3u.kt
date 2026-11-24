package ru.freetv.app.data

data class RawChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val tvgId: String?,
    val group: String,
    val unstable: Boolean,
    val hasArchive: Boolean = false
)

/**
 * Парсер M3U/M3U8-плейлистов IPTV. Понимает tvg-id / tvg-logo / group-title,
 * #EXTGRP, #EXTVLCOPT и потоки http/https/udp/rtp.
 */
object M3uParser {

    private val attribute = Regex("""([\w-]+)=["']([^"']*)["']""")
    private val allowedSchemes = setOf("http", "https", "udp", "rtp")

    fun parse(text: String): List<RawChannel> {
        val lines = text.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .toList()

        val result = ArrayList<RawChannel>()
        var currentGroup = ""
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTGRP", ignoreCase = true)) {
                currentGroup = line.substringAfter(':', "").trim()
                i++
                continue
            }
            if (!line.startsWith("#EXTINF", ignoreCase = true)) {
                i++
                continue
            }

            val comma = line.lastIndexOf(',')
            val meta = if (comma >= 0) line.substring(0, comma) else line
            val name = if (comma >= 0) line.substring(comma + 1).trim() else ""

            val attrs = HashMap<String, String>()
            attribute.findAll(meta).forEach { m ->
                attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
            }

            var url = ""
            var j = i + 1
            while (j < lines.size) {
                val next = lines[j]
                when {
                    next.startsWith("#EXTVLCOPT", ignoreCase = true) -> Unit
                    next.startsWith("#") || next.isEmpty() -> Unit
                    else -> {
                        url = next
                        break
                    }
                }
                j++
            }

            val scheme = url.substringBefore(':', "").lowercase()
            if (scheme in allowedSchemes) {
                val normalizedUrl = normalizeUrl(url)
                val unstable = name.contains("[Not 24/7]", true) ||
                    name.contains("[Geo-blocked]", true)
                val group = attrs["group-title"].orEmpty().ifBlank { currentGroup }
                val hasArchive = attrs.containsKey("catchup") ||
                    attrs.containsKey("tvg-rec") ||
                    attrs.containsKey("timeshift")
                result.add(
                    RawChannel(
                        name = name,
                        url = normalizedUrl,
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
                        tvgId = attrs["tvg-id"],
                        group = group,
                        unstable = unstable,
                        hasArchive = hasArchive
                    )
                )
            }
            i = j + 1
        }
        return result
    }

    /** udp://@239.0.0.1:1234 -> udp://239.0.0.1:1234 */
    private fun normalizeUrl(url: String): String =
        url.replace("udp://@", "udp://", ignoreCase = true)
            .replace("rtp://@", "rtp://", ignoreCase = true)
}
