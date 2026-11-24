package ru.freetv.app.util

import android.content.Context

class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("freetv", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = prefs.getString(KEY_PLAYLIST, DEFAULT_PLAYLIST)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PLAYLIST
        set(value) = prefs.edit().putString(KEY_PLAYLIST, value.trim()).apply()

    /** "m3u" или "xtream". */
    var sourceType: String
        get() = prefs.getString(KEY_SOURCE_TYPE, "m3u") ?: "m3u"
        set(value) = prefs.edit().putString(KEY_SOURCE_TYPE, value).apply()

    var xtreamHost: String
        get() = prefs.getString(KEY_XTREAM_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XTREAM_HOST, value.trim()).apply()

    var xtreamPort: String
        get() = prefs.getString(KEY_XTREAM_PORT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XTREAM_PORT, value.trim()).apply()

    var xtreamUser: String
        get() = prefs.getString(KEY_XTREAM_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XTREAM_USER, value.trim()).apply()

    var xtreamPass: String
        get() = prefs.getString(KEY_XTREAM_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XTREAM_PASS, value.trim()).apply()

    var hdOnly: Boolean
        get() = prefs.getBoolean(KEY_HD_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_HD_ONLY, value).apply()

    var hideUnstable: Boolean
        get() = prefs.getBoolean(KEY_HIDE_UNSTABLE, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_UNSTABLE, value).apply()

    var hideDead: Boolean
        get() = prefs.getBoolean(KEY_HIDE_DEAD, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_DEAD, value).apply()

    /** Скрывать каналы, у которых все источники в MP2 (на части ТВ нет звука). */
    var hideNoAudio: Boolean
        get() = prefs.getBoolean(KEY_HIDE_NO_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_NO_AUDIO, value).apply()

    /** "auto", "tv" или "phone". */
    var uiMode: String
        get() = prefs.getString(KEY_UI_MODE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_UI_MODE, value).apply()

    /** Пользователь уже выбрал устройство на первом экране. */
    var uiModeSelected: Boolean
        get() = prefs.getBoolean(KEY_UI_MODE_SELECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_UI_MODE_SELECTED, value).apply()

    var healthChecked: Boolean
        get() = prefs.getBoolean(KEY_HEALTH_CHECKED, false)
        set(value) = prefs.edit().putBoolean(KEY_HEALTH_CHECKED, value).apply()

    var lastVersion: Int
        get() = prefs.getInt(KEY_LAST_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_VERSION, value).apply()

    /** 0 — авто, иначе максимальная высота кадра (720, 1080...). */
    var videoQuality: Int
        get() = prefs.getInt(KEY_VIDEO_QUALITY, 0)
        set(value) = prefs.edit().putInt(KEY_VIDEO_QUALITY, value).apply()

    /** Репозиторий GitHub для автообновления в формате "owner/repo". Пусто — выключено. */
    var updateRepo: String
        get() = prefs.getString(KEY_UPDATE_REPO, DEFAULT_UPDATE_REPO)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UPDATE_REPO
        set(value) = prefs.edit().putString(KEY_UPDATE_REPO, value.trim()).apply()

    fun favorites(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun isFavorite(key: String): Boolean = key in favorites()

    fun toggleFavorite(key: String): Boolean {
        val set = favorites().toMutableSet()
        val added = set.add(key)
        if (!added) set.remove(key)
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
        return added
    }

    fun channelOrder(): List<String> =
        prefs.getString(KEY_ORDER, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun saveOrder(order: List<String>) {
        prefs.edit().putString(KEY_ORDER, order.distinct().joinToString("\n")).apply()
    }

    fun markDead(key: String) {
        prefs.edit().putLong(DEAD_PREFIX + key, System.currentTimeMillis()).apply()
    }

    fun isDead(key: String): Boolean {
        val at = prefs.getLong(DEAD_PREFIX + key, 0L)
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > DEAD_TTL_MS) {
            prefs.edit().remove(DEAD_PREFIX + key).apply()
            return false
        }
        return true
    }

    fun clearDead() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(DEAD_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Помечает конкретный поток нерабочим, чтобы не тратить на него время. */
    fun markBadUrl(url: String) {
        prefs.edit().putLong(BAD_URL_PREFIX + url, System.currentTimeMillis()).apply()
    }

    fun isBadUrl(url: String): Boolean {
        val at = prefs.getLong(BAD_URL_PREFIX + url, 0L)
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > BAD_URL_TTL_MS) {
            prefs.edit().remove(BAD_URL_PREFIX + url).apply()
            return false
        }
        return true
    }

    fun clearBadUrls() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(BAD_URL_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_PLAYLIST = "https://iptv-org.github.io/iptv/countries/ru.m3u"
        const val DEFAULT_UPDATE_REPO = "xSEAmon/xseamon-tv"
        private const val KEY_PLAYLIST = "playlist_url"
        private const val KEY_SOURCE_TYPE = "source_type"
        private const val KEY_XTREAM_HOST = "xtream_host"
        private const val KEY_XTREAM_PORT = "xtream_port"
        private const val KEY_XTREAM_USER = "xtream_user"
        private const val KEY_XTREAM_PASS = "xtream_pass"
        private const val KEY_HD_ONLY = "hd_only"
        private const val KEY_HIDE_UNSTABLE = "hide_unstable"
        private const val KEY_HIDE_DEAD = "hide_dead"
        private const val KEY_HIDE_NO_AUDIO = "hide_no_audio"
        private const val KEY_UI_MODE = "ui_mode"
        private const val KEY_UI_MODE_SELECTED = "ui_mode_selected"
        private const val KEY_HEALTH_CHECKED = "health_checked"
        private const val KEY_LAST_VERSION = "last_version"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_UPDATE_REPO = "update_repo"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_ORDER = "channel_order"
        private const val DEAD_PREFIX = "dead:"
        private const val BAD_URL_PREFIX = "badurl:"
        private const val DEAD_TTL_MS = 3L * 24 * 60 * 60 * 1000
        private const val BAD_URL_TTL_MS = 24L * 60 * 60 * 1000
    }
}
