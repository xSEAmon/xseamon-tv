package ru.freetv.app.ui

import ru.freetv.app.data.Channel

sealed class TvItem {
    data class Chan(val channel: Channel) : TvItem()
    data class Action(val id: String, val title: String, val subtitle: String) : TvItem()
}
