package ru.freetv.app

import android.app.Application
import android.util.Log
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import ru.freetv.app.data.DeviceProfile
import ru.freetv.app.data.RefreshScheduler

class AntonTvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        val profile = DeviceProfile.of(this)
        val loader = ImageLoader.Builder(this)
            .crossfade(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(if (profile.lowRam) 0.01 else 0.02)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (profile.lowRam) 0.08 else 0.15)
                    .build()
            }
            .build()
        Coil.setImageLoader(loader)
        RefreshScheduler.schedule(this)
    }

    /**
     * Если на конкретном боксе/прошивке что-то падает, стектрейс остаётся
     * в files/last_crash.txt — его можно забрать через adb pull.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Log.e("AntonTV", "FATAL", throwable)
                val text = android.util.Log.getStackTraceString(throwable)
                java.io.File(filesDir, "last_crash.txt").writeText(text)
                getExternalFilesDir(null)?.let { dir ->
                    java.io.File(dir, "last_crash.txt").writeText(text)
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
