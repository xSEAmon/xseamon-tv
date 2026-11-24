package ru.freetv.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import ru.freetv.app.R
import ru.freetv.app.util.Prefs

/**
 * Экран первого запуска: выбор устройства (Телевизор / Телефон).
 *
 * Показывается один раз — после выбора запускается [MainActivity], а выбор
 * запоминается. Изменить его можно в Настройках («Сменить устройство»),
 * тогда экран открывается принудительно через [EXTRA_FORCE].
 */
class DeviceSelectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE = "extra_force"

        fun intent(context: android.content.Context, force: Boolean = false): Intent =
            Intent(context, DeviceSelectActivity::class.java).putExtra(EXTRA_FORCE, force)
    }

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        if (!force && prefs.uiModeSelected) {
            openMain()
            return
        }

        setContentView(R.layout.activity_device_select)

        findViewById<View>(R.id.btn_tv).setOnClickListener { choose("tv") }
        findViewById<View>(R.id.btn_phone).setOnClickListener { choose("phone") }
    }

    private fun choose(mode: String) {
        prefs.uiMode = mode
        prefs.uiModeSelected = true
        openMain()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            hideSystemUi()
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
