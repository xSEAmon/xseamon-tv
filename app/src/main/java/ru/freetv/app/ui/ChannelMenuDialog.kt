package ru.freetv.app.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import ru.freetv.app.R
import ru.freetv.app.data.Channel

/** Меню канала: избранное, программа передач и перемещение по списку. */
object ChannelMenuDialog {

    fun show(
        context: Context,
        channel: Channel,
        isFavorite: Boolean,
        canUp: Boolean,
        canDown: Boolean,
        onFavorite: () -> Unit,
        onSchedule: () -> Unit,
        onUp: () -> Unit,
        onDown: () -> Unit,
        onTop: () -> Unit,
        onGrid: (() -> Unit)? = null
    ) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add(if (isFavorite) "Убрать из избранного" else "Добавить в избранное")
        actions.add(onFavorite)

        labels.add("Программа передач")
        actions.add(onSchedule)

        if (onGrid != null) {
            labels.add("Сетка программ")
            actions.add(onGrid)
        }

        if (canUp) {
            labels.add("Переместить выше")
            actions.add(onUp)
        }
        if (canDown) {
            labels.add("Переместить ниже")
            actions.add(onDown)
        }

        labels.add("В начало списка")
        actions.add(onTop)

        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle(channel.name)
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
