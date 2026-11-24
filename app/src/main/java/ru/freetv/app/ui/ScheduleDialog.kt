package ru.freetv.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import ru.freetv.app.R
import ru.freetv.app.data.Channel
import ru.freetv.app.data.Epg

/** Диалог «Программа передач»: что идёт сейчас и что будет дальше. */
object ScheduleDialog {

    fun show(context: Context, channel: Channel) {
        val programmes = Epg.upcoming(channel, hours = 24)

        val dialog = AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle(channel.name)
            .setNegativeButton("Закрыть", null)
            .create()

        if (programmes.isEmpty()) {
            dialog.setMessage("Программа передач для этого канала пока недоступна")
            dialog.show()
            return
        }

        val list = ListView(context)
        list.divider = null
        list.dividerHeight = 0
        list.setBackgroundColor(Color.TRANSPARENT)
        list.isFocusable = true
        list.adapter = ScheduleAdapter(context, programmes)
        list.setOnItemClickListener { _, _, _, _ -> }

        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        container.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 360)
            )
        )

        dialog.setView(container)
        dialog.show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class ScheduleAdapter(
        private val context: Context,
        private val items: List<Epg.Programme>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                isFocusable = true
                setBackgroundResource(R.drawable.schedule_row)
            }

            val programme = items[position]
            val now = System.currentTimeMillis()
            val isCurrent = now >= programme.start && now < programme.stop
            val prefix = if (isCurrent) "● " else ""

            row.text = prefix + Epg.formatTime(programme.start) + "–" +
                Epg.formatTime(programme.stop) + "  " + programme.title
            row.setTextColor(
                if (isCurrent) ContextCompat.getColor(context, R.color.accent)
                else ContextCompat.getColor(context, R.color.text)
            )
            return row
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
