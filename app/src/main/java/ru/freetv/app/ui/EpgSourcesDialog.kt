package ru.freetv.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.freetv.app.R
import ru.freetv.app.data.Epg
import ru.freetv.app.data.db.EpgSourceEntity

/**
 * Управление списком XMLTV-источников: N источников, приоритет, gzip, удаление.
 */
object EpgSourcesDialog {

    fun show(context: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            val sources = runCatching { Epg.sources(context) }.getOrDefault(emptyList())
            val labels = sources.map { source ->
                val status = source.lastError?.let { " · ошибка: $it" }.orEmpty()
                "${source.priority}. ${source.name}${if (source.enabled) "" else " (выкл)"}$status"
            }.toTypedArray()

            val dialog = AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
                .setTitle("Источники EPG (XMLTV)")
                .setItems(labels) { _, which ->
                    sources.getOrNull(which)?.let { edit(context, it) { show(context) } }
                }
                .setPositiveButton("Добавить") { _, _ ->
                    edit(context, null) { show(context) }
                }
                .setNegativeButton("Закрыть", null)
                .create()

            if (sources.isEmpty()) {
                dialog.setMessage("Источников пока нет. Добавьте XMLTV URL — программа появится в сетке.")
            }
            dialog.setOnDismissListener { scope.cancel() }
            dialog.show()
        }
    }

    private fun edit(context: Context, source: EpgSourceEntity?, onDone: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_epg_source, null)
        val name = view.findViewById<EditText>(R.id.epg_source_name)
        val url = view.findViewById<EditText>(R.id.epg_source_url)
        val priority = view.findViewById<EditText>(R.id.epg_source_priority)
        val gzip = view.findViewById<CheckBox>(R.id.epg_source_gzip)

        name.setText(source?.name.orEmpty())
        url.setText(source?.url.orEmpty())
        priority.setText((source?.priority ?: 1).toString())
        gzip.isChecked = source?.gzip == true

        val builder = AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle(if (source == null) "Добавить XMLTV" else "Изменить XMLTV")
            .setView(view)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)

        if (source != null) {
            builder.setNeutralButton("Удалить") { _, _ ->
                CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    Epg.deleteSource(context, source)
                    onDone()
                }
            }
        }
        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nameValue = name.text.toString().ifBlank { "XMLTV" }
                val urlValue = url.text.toString().trim()
                if (!urlValue.startsWith("http://") && !urlValue.startsWith("https://")) {
                    url.error = "Нужен http(s) URL"
                    return@setOnClickListener
                }
                val priorityValue = priority.text.toString().toIntOrNull() ?: 1
                CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    if (source == null) {
                        Epg.addSource(context, nameValue, urlValue, gzip.isChecked, priorityValue)
                    } else {
                        Epg.updateSource(
                            context,
                            source.copy(
                                name = nameValue,
                                url = urlValue,
                                gzip = gzip.isChecked,
                                priority = priorityValue
                            )
                        )
                    }
                    dialog.dismiss()
                    onDone()
                }
            }
        }
        dialog.show()
    }
}
