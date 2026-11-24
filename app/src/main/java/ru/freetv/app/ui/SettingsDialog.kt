package ru.freetv.app.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import ru.freetv.app.R
import ru.freetv.app.update.UpdateChecker
import ru.freetv.app.util.Prefs

class SettingsDialog(
    private val context: Context,
    private val prefs: Prefs,
    private val onSaved: () -> Unit
) {

    fun show() {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)

        val url = view.findViewById<EditText>(R.id.settings_url)
        val hdOnly = view.findViewById<CheckBox>(R.id.settings_hd)
        val hideDead = view.findViewById<CheckBox>(R.id.settings_dead)
        val hideUnstable = view.findViewById<CheckBox>(R.id.settings_unstable)
        val hideNoAudio = view.findViewById<CheckBox>(R.id.settings_noaudio)
        val allCategories = view.findViewById<CheckBox>(R.id.settings_all_categories)
        val reset = view.findViewById<TextView>(R.id.settings_reset)
        val changeDevice = view.findViewById<TextView>(R.id.settings_change_device)
        val versionView = view.findViewById<TextView>(R.id.settings_version)
        val uiMode = view.findViewById<RadioGroup>(R.id.settings_ui_mode)
        val sourceType = view.findViewById<RadioGroup>(R.id.settings_source_type)
        val sourceM3u = view.findViewById<android.widget.RadioButton>(R.id.settings_source_m3u)
        val sourceXtream = view.findViewById<android.widget.RadioButton>(R.id.settings_source_xtream)
        val xtreamHost = view.findViewById<EditText>(R.id.settings_xtream_host)
        val xtreamUser = view.findViewById<EditText>(R.id.settings_xtream_user)
        val xtreamPass = view.findViewById<EditText>(R.id.settings_xtream_pass)
        val epgSources = view.findViewById<TextView>(R.id.settings_epg_sources)
        val updateRepo = view.findViewById<EditText>(R.id.settings_update_repo)
        val checkUpdates = view.findViewById<TextView>(R.id.settings_check_updates)

        fun updateSourceVisibility() {
            val xtream = sourceXtream.isChecked
            url.visibility = if (xtream) android.view.View.GONE else android.view.View.VISIBLE
            xtreamHost.visibility = if (xtream) android.view.View.VISIBLE else android.view.View.GONE
            xtreamUser.visibility = if (xtream) android.view.View.VISIBLE else android.view.View.GONE
            xtreamPass.visibility = if (xtream) android.view.View.VISIBLE else android.view.View.GONE
        }

        versionView.text = "Антон ТВ · версия " + version.ifBlank { "—" }

        url.setText(prefs.playlistUrl)
        hdOnly.isChecked = prefs.hdOnly
        hideDead.isChecked = prefs.hideDead
        hideUnstable.isChecked = prefs.hideUnstable
        hideNoAudio.isChecked = prefs.hideNoAudio
        allCategories.isChecked = prefs.showAllCategories
        xtreamHost.setText(prefs.xtreamHost)
        xtreamUser.setText(prefs.xtreamUser)
        xtreamPass.setText(prefs.xtreamPass)
        updateRepo.setText(prefs.updateRepo)
        if (prefs.sourceType == "xtream") sourceXtream.isChecked = true else sourceM3u.isChecked = true
        updateSourceVisibility()
        when (prefs.uiMode) {
            "tv" -> uiMode.check(R.id.settings_ui_tv)
            "phone" -> uiMode.check(R.id.settings_ui_phone)
            else -> uiMode.check(R.id.settings_ui_auto)
        }
        sourceType.setOnCheckedChangeListener { _, _ -> updateSourceVisibility() }
        epgSources.setOnClickListener { EpgSourcesDialog.show(context) }

        val dialog = AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle("Настройки" + if (version.isNotBlank()) " · v$version" else "")
            .setView(view)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.playlistUrl = url.text.toString().ifBlank { Prefs.DEFAULT_PLAYLIST }
                prefs.sourceType = if (sourceXtream.isChecked) "xtream" else "m3u"
                prefs.xtreamHost = xtreamHost.text.toString()
                prefs.xtreamUser = xtreamUser.text.toString()
                prefs.xtreamPass = xtreamPass.text.toString()
                prefs.updateRepo = updateRepo.text.toString()
                prefs.hdOnly = hdOnly.isChecked
                prefs.hideDead = hideDead.isChecked
                prefs.hideUnstable = hideUnstable.isChecked
                prefs.hideNoAudio = hideNoAudio.isChecked
                prefs.showAllCategories = allCategories.isChecked
                prefs.uiMode = when (uiMode.checkedRadioButtonId) {
                    R.id.settings_ui_tv -> "tv"
                    R.id.settings_ui_phone -> "phone"
                    else -> "auto"
                }
                dialog.dismiss()
                onSaved()
            }

            reset.setOnClickListener {
                prefs.reset()
                url.setText(prefs.playlistUrl)
                hdOnly.isChecked = prefs.hdOnly
                hideDead.isChecked = prefs.hideDead
                hideUnstable.isChecked = prefs.hideUnstable
                hideNoAudio.isChecked = prefs.hideNoAudio
                allCategories.isChecked = prefs.showAllCategories
                xtreamHost.setText("")
                xtreamUser.setText("")
                xtreamPass.setText("")
                updateRepo.setText("")
                sourceM3u.isChecked = true
                updateSourceVisibility()
            }

            changeDevice.setOnClickListener {
                dialog.dismiss()
                context.startActivity(DeviceSelectActivity.intent(context, force = true))
            }

            checkUpdates.setOnClickListener {
                dialog.dismiss()
                (context as? AppCompatActivity)?.let { UpdateChecker.checkForUpdates(it) }
            }
        }

        dialog.show()
    }
}
