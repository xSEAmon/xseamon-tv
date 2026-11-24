package ru.freetv.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.freetv.app.R
import ru.freetv.app.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * Автообновление через GitHub Releases.
 *
 * Ищет последний релиз по адресу:
 *   https://api.github.com/repos/{owner}/{repo}/releases/latest
 *
 * Соглашение по тегу релиза: `tag_name` должен содержать versionCode приложения
 * (например `v38` или `38`). Номер сравнивается с установленным versionCode,
 * а в качестве APK берётся первый ассет с расширением `.apk`.
 *
 * Скачивание идёт через системный [DownloadManager] — его `content://`-ссылка
 * надёжно открывается системным установщиком (без «Открыть с помощью»).
 */
object UpdateChecker {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Update(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkUrl: String
    )

    /** Запускает полный цикл: проверка → предложение → скачивание → установка. */
    fun checkForUpdates(activity: AppCompatActivity) {
        val progress = AlertDialog.Builder(activity, R.style.Theme_FreeTV_Dialog)
            .setTitle("Обновление")
            .setMessage("Проверяем наличие новой версии…")
            .setCancelable(false)
            .create()
        progress.show()

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchLatest(activity) } }
            progress.dismiss()
            result.onSuccess { update ->
                if (update != null && update.versionCode > installedVersionCode(activity)) {
                    showUpdateAvailable(activity, update)
                } else if (update != null) {
                    info(activity, "Обновление", "У вас последняя версия.")
                } else {
                    info(
                        activity,
                        "Обновление",
                        "Репозиторий обновлений не настроен или недоступен.\n" +
                            "Укажите его в настройках в формате owner/repo."
                    )
                }
            }.onFailure { error ->
                val detail = when (error) {
                    is java.net.SocketTimeoutException,
                    is java.net.ConnectException -> "Сервер не ответил. Проверьте интернет-соединение."

                    is java.io.IOException -> "Нет соединения с интернетом."
                    else -> error.message?.takeIf { it.isNotBlank() }
                        ?: error.javaClass.simpleName
                }
                info(activity, "Ошибка", "Не удалось проверить обновления.\n$detail")
            }
        }
    }

    private fun showUpdateAvailable(activity: AppCompatActivity, update: Update) {
        val message = buildString {
            if (update.notes.isNotBlank()) {
                append(update.notes.trim())
                append("\n\n")
            }
            append("Скачать и установить версию ")
            append(update.versionName)
            append("?")
        }
        AlertDialog.Builder(activity, R.style.Theme_FreeTV_Dialog)
            .setTitle("Доступна версия " + update.versionName)
            .setMessage(message)
            .setPositiveButton("Обновить") { _, _ -> downloadAndInstall(activity, update) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun downloadAndInstall(activity: AppCompatActivity, update: Update) {
        // Android 8+ требует разрешение на установку из этого источника.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            info(
                activity,
                "Разрешение",
                "Разрешите установку из этого источника, затем нажмите «Обновить» ещё раз."
            )
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
            )
            return
        }

        val fileName = "AntonTV-" + update.versionName + ".apk"
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Антон ТВ " + update.versionName)
            .setDescription("Скачивание обновления…")
            .setMimeType("application/vnd.android-package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        info(
            activity,
            "Обновление",
            "Загрузка началась. Когда она завершится, откроется окно установки."
        )

        activity.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { waitForDownload(activity, downloadId) }
            if (uri != null) {
                installFromUri(activity, uri)
            } else {
                info(activity, "Ошибка", "Не удалось скачать обновление.")
            }
        }
    }

    private suspend fun waitForDownload(context: Context, id: Long): Uri? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            val query = DownloadManager.Query().setFilterById(id)
            manager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    when (cursor.getInt(column)) {
                        DownloadManager.STATUS_SUCCESSFUL ->
                            return manager.getUriForDownloadedFile(id)

                        DownloadManager.STATUS_FAILED -> return null
                    }
                }
            }
            delay(500L)
        }
        return null
    }

    private fun installFromUri(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android-package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun info(activity: AppCompatActivity, title: String, message: String) {
        AlertDialog.Builder(activity, R.style.Theme_FreeTV_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Ок", null)
            .show()
    }

    private fun fetchLatest(context: Context): Update? {
        val repo = Prefs(context).updateRepo.trim().trimEnd('/')
        if (!repo.contains("/")) return null
        if (repo.count { it == '/' } != 1) return null

        val url = "https://api.github.com/repos/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AntonTV")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = JSONObject(response.body?.string().orEmpty())

            val tag = root.optString("tag_name").orEmpty()
            val versionCode = parseVersionCode(tag) ?: return null
            val versionName = root.optString("name").ifBlank { tag }
            val notes = root.optString("body").orEmpty().take(2000)

            val assets = root.optJSONArray("assets") ?: return null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i)
                val name = asset.optString("name")
                val download = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && download.isNotBlank()) {
                    apkUrl = download
                    break
                }
            }
            if (apkUrl.isBlank()) return null

            return Update(versionCode, versionName, notes, apkUrl)
        }
    }

    /** Принимает `v38`, `38`, пробелы не критичны. */
    internal fun parseVersionCode(tag: String): Int? =
        Regex("""^[vV]?\s*(\d+)\s*$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }.getOrDefault(0)
}
