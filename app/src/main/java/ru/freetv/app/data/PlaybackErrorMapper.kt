package ru.freetv.app.data

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class PlaybackErrorInfo(
    val title: String,
    val hint: String,
    val transient: Boolean
)

object PlaybackErrorMapper {

    fun map(error: PlaybackException): PlaybackErrorInfo {
        val code = error.errorCode
        val cause = error.cause

        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return when (cause.responseCode) {
                401, 403 -> PlaybackErrorInfo(
                    "Источник запретил доступ",
                    "HTTP ${cause.responseCode}. Возможно, канал геоблокирован или требует другой источник.",
                    transient = false
                )
                404, 410 -> PlaybackErrorInfo(
                    "Поток не найден",
                    "HTTP ${cause.responseCode}. Источник устарел, пробуем резервный.",
                    transient = false
                )
                else -> PlaybackErrorInfo(
                    "Ошибка источника",
                    "HTTP ${cause.responseCode}. Пробуем другой поток.",
                    transient = false
                )
            }
        }

        if (cause is UnknownHostException) {
            return PlaybackErrorInfo(
                "Нет связи с источником",
                "Не удалось найти сервер канала. Проверьте интернет или DNS.",
                transient = true
            )
        }
        if (cause is SocketTimeoutException || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return PlaybackErrorInfo(
                "Источник не отвечает",
                "Превышено время ожидания. Повторим подключение.",
                transient = true
            )
        }
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
            return PlaybackErrorInfo(
                "Нет соединения",
                "Проверьте сеть на телевизоре. Повторим автоматически.",
                transient = true
            )
        }
        if (code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        ) {
            return PlaybackErrorInfo(
                "Формат не поддерживается",
                "Телевизор не смог запустить декодер. Пробуем другой профиль декодирования.",
                transient = false
            )
        }
        if (code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED || cause is IOException) {
            return PlaybackErrorInfo(
                "Поток оборвался",
                "Попробуем восстановить воспроизведение.",
                transient = true
            )
        }
        return PlaybackErrorInfo(
            "Не удалось открыть канал",
            "Попробуйте другой канал или повторите позже.",
            transient = true
        )
    }

    fun technical(error: PlaybackException): String = buildString {
        append(error.errorCodeName)
        error.cause?.message?.take(90)?.let { append(": ").append(it) }
    }
}
