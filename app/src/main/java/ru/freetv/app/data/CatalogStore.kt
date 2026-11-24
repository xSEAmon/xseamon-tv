package ru.freetv.app.data

/**
 * Каталог живёт в памяти процесса. Activity не передают 450 Channel через Intent:
 * это риск TransactionTooLargeException и лишняя память.
 */
object CatalogStore {

    @Volatile
    private var current: Catalog? = null

    @Volatile
    private var flat: List<Channel> = emptyList()

    fun set(catalog: Catalog) {
        current = catalog
        flat = catalog.allChannels
    }

    fun catalog(): Catalog? = current

    fun channels(): List<Channel> = flat

    fun channel(key: String): Channel? = flat.firstOrNull { it.key == key }

    fun indexOf(key: String): Int = flat.indexOfFirst { it.key == key }

    fun channelAt(index: Int): Channel? = flat.getOrNull(index)

    fun clear() {
        current = null
        flat = emptyList()
    }
}
