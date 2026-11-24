package ru.freetv.app.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

enum class DecoderMode {
    HW,
    HW_FALLBACK,
    SW;

    fun next(): DecoderMode = when (this) {
        HW -> HW_FALLBACK
        HW_FALLBACK -> SW
        SW -> SW
    }
}

/**
 * Рабочий профиль декодера запоминается для конкретного устройства и MIME.
 * После смены прошивки Build.FINGERPRINT меняется — профиль сбрасывается.
 */
class DecoderProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("decoder_profile_v3", Context.MODE_PRIVATE)

    fun modeFor(mime: String): DecoderMode {
        val value = prefs.getString(key(mime), DecoderMode.HW.name) ?: DecoderMode.HW.name
        return runCatching { DecoderMode.valueOf(value) }.getOrDefault(DecoderMode.HW)
    }

    fun remember(mime: String, mode: DecoderMode) {
        prefs.edit().putString(key(mime), mode.name).apply()
    }

    fun next(mime: String): DecoderMode {
        val next = modeFor(mime).next()
        remember(mime, next)
        return next
    }

    fun reset() = prefs.edit().clear().apply()

    private fun key(mime: String): String = DeviceProfile.deviceKey() + "|" + mime
}

@OptIn(UnstableApi::class)
class DeviceCodecSelector(private val mode: DecoderMode) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        // Профиль HW/HW+/SW относится только к видео. Аудио (AAC/MP2/AC3)
        // должно всегда идти через штатный селектор, иначе на части боксов
        // программный аудиодекодер отфильтровывается и звука нет вообще.
        if (mimeType.startsWith("audio/", ignoreCase = true)) {
            return MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
        }

        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        )
        if (all.isEmpty()) return all
        return when (mode) {
            DecoderMode.HW -> all.filter { it.hardwareAccelerated }
                .ifEmpty { all }
            DecoderMode.HW_FALLBACK -> all.sortedByDescending { it.hardwareAccelerated }
            DecoderMode.SW -> all.filter { !it.hardwareAccelerated }
                .ifEmpty { all }
        }
    }

    companion object {
        val VIDEO_MIMES = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MP4V)
    }
}
