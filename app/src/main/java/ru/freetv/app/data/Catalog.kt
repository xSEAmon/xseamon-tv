package ru.freetv.app.data

data class CatalogRow(
    val title: String,
    val channels: List<Channel>
)

data class Catalog(
    val rows: List<CatalogRow>,
    val total: Int,
    val hd: Int,
    val federal: Int,
    val updatedAt: Long
) {
    val allChannels: List<Channel>
        get() = rows.flatMap { it.channels }.distinctBy { it.key }

    /** Имена, по которым ищем телепрограмму в XMLTV. */
    fun epgNames(): Set<String> =
        allChannels
            .flatMap { it.epgNames }
            .map { Epg.normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()
}
