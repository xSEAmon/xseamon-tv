package ru.freetv.app.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Один телеканал. [urls] содержит основной поток и резервные (в порядке приоритета),
 * [number] — номер для быстрого переключения цифрами.
 */
@Parcelize
data class Channel(
    val key: String,
    val name: String,
    val urls: List<String>,
    val logo: String?,
    val group: String,
    val quality: Int,
    val number: Int,
    val federal: Boolean,
    val favorite: Boolean = false,
    val epgNames: List<String>,
    val tvgId: String? = null,
    val hasArchive: Boolean = false
) : Parcelable {

    val isHd: Boolean get() = quality >= 720

    val primaryUrl: String get() = urls.firstOrNull().orEmpty()

    val isMulticast: Boolean
        get() = urls.any {
            it.startsWith("udp://", true) || it.startsWith("rtp://", true)
        }
}
