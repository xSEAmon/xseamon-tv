package ru.freetv.app.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * MulticastLock нужен на части Amlogic/Realtek для приёма udp:// и rtp://.
 * Держим только на время воспроизведения multicast-канала.
 */
class MulticastController(context: Context) {

    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lock: WifiManager.MulticastLock? = null

    fun acquire() {
        if (lock?.isHeld == true) return
        val manager = wifi ?: return
        try {
            val created = manager.createMulticastLock("antontv-multicast").apply {
                setReferenceCounted(false)
            }
            created.acquire()
            lock = created
            Log.d("AntonTV", "multicast lock acquired")
        } catch (error: Throwable) {
            Log.w("AntonTV", "multicast lock failed", error)
        }
    }

    fun release() {
        val current = lock ?: return
        try {
            if (current.isHeld) current.release()
        } catch (_: Throwable) {
        } finally {
            lock = null
        }
    }
}
