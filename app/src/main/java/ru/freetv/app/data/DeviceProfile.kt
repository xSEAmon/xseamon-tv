package ru.freetv.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.min

/**
 * Единая точка деградации под слабые ТВ-боксы.
 *
 * 2 GB Amlogic S905/S905X — baseline, 1 GB — облегчённый режим.
 */
data class DeviceProfile(
    val lowRam: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    val maxVideoWidth: Int,
    val maxVideoHeight: Int,
    val startBitrate: Int,
    val maxBitrate: Int,
    val bufferMinMs: Int,
    val bufferMaxMs: Int,
    val epgWindowHours: Int,
    val rowWindow: Int,
    val searchPage: Int,
    val prefetch: Boolean
) {
    companion object {
        fun of(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val lowRam = (am?.isLowRamDevice == true) || (am?.memoryClass ?: 256) <= 128

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0) {
                metrics.widthPixels = context.resources.displayMetrics.widthPixels
                metrics.heightPixels = context.resources.displayMetrics.heightPixels
            }
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val maxHeight = if (lowRam) min(screenHeight, 720) else min(screenHeight, 1080)
            val maxWidth = if (lowRam) min(screenWidth, 1280) else min(screenWidth, 1920)

            return DeviceProfile(
                lowRam = lowRam,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                maxVideoWidth = maxWidth.coerceAtLeast(640),
                maxVideoHeight = maxHeight.coerceAtLeast(360),
                startBitrate = if (lowRam) 1_500_000 else 2_500_000,
                maxBitrate = if (lowRam) 3_500_000 else 8_000_000,
                bufferMinMs = 2_000,
                bufferMaxMs = if (lowRam) 8_000 else 12_000,
                epgWindowHours = if (lowRam) 3 else 6,
                rowWindow = if (lowRam) 20 else 30,
                searchPage = if (lowRam) 20 else 30,
                prefetch = !lowRam
            )
        }

        fun deviceKey(): String = listOf(
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.FINGERPRINT
        ).joinToString("|")
    }
}
