package ru.freetv.app.data

import ru.freetv.app.util.Quality

/**
 * Собирает каталог из «сырых» записей плейлиста:
 * склеивает дубли и HD-варианты, распознаёт 20 федеральных каналов,
 * раскладывает остальные по категориям.
 */
object CatalogBuilder {

    data class Options(
        val hdOnly: Boolean,
        val hideUnstable: Boolean,
        val hideDead: Boolean,
        val isDead: (String) -> Boolean,
        val preferred: Map<String, List<String>> = emptyMap(),
        val favorites: Set<String> = emptySet(),
        val order: List<String> = emptyList(),
        val noAudioKeys: Set<String> = emptySet(),
        val hideNoAudio: Boolean = true
    )

    private val qualityTag = Regex("""\(\s*\d{3,4}\s*[pi]\s*\)""", RegexOption.IGNORE_CASE)
    private val bracketTag = Regex("""\[[^\]]*]""")
    private val plusShift = Regex("""\+\d{1,2}\b""")
    private val manySpaces = Regex("""\s{2,}""")
    private val nonAlphanumeric = Regex("""[^a-zа-я0-9]""")
    private val cyrillic = Regex("""[А-Яа-яЁё]""")

    private val qualitySuffixes = setOf("sd", "hd", "fhd", "uhd", "4k", "8k", "hq", "lq", "")

    private val groupNames = mapOf(
        "general" to "Общие",
        "news" to "Новости",
        "sports" to "Спорт",
        "movies" to "Кино",
        "series" to "Сериалы",
        "kids" to "Детям",
        "animation" to "Мультфильмы",
        "music" to "Музыка",
        "entertainment" to "Развлечения",
        "culture" to "Культура",
        "documentary" to "Документальные",
        "religious" to "Религия",
        "education" to "Познавательные",
        "science" to "Наука",
        "shop" to "Магазины",
        "travel" to "Путешествия",
        "auto" to "Авто",
        "cooking" to "Кулинария",
        "family" to "Семейные",
        "lifestyle" to "Стиль жизни",
        "weather" to "Погода",
        "business" to "Бизнес",
        "politics" to "Политика",
        "comedy" to "Юмор",
        "fashion" to "Мода",
        "anime" to "Аниме",
        "legislative" to "Парламент",
        "outdoor" to "Охота и рыбалка",
        "relax" to "Релакс",
        "classic" to "Классика",
        "undefined" to "Прочее"
    )

    private val groupOrder = listOf(
        "Новости", "Общие", "Кино", "Сериалы", "Спорт", "Детям", "Мультфильмы",
        "Познавательные", "Документальные", "Развлечения", "Музыка", "Культура",
        "Юмор", "Религия", "Путешествия", "Охота и рыбалка", "Авто", "Кулинария",
        "Семейные", "Стиль жизни", "Мода", "Наука", "Бизнес", "Политика",
        "Парламент", "Магазины", "Погода", "Классика", "Релакс", "Прочее"
    )

    fun build(raw: List<RawChannel>, options: Options, updatedAt: Long): Catalog {
        val groups = LinkedHashMap<String, MutableList<RawChannel>>()
        for (channel in raw) {
            if (channel.name.isBlank()) continue
            groups.getOrPut(keyOf(channel)) { mutableListOf() }.add(channel)
        }

        val regular = ArrayList<Channel>()
        val federalCandidates = HashMap<Int, MutableList<Channel>>()

        for ((key, variants) in groups) {
            val sorted = variants.sortedByDescending { Quality.of(it.name) }
            val best = sorted.firstOrNull() ?: continue
            val quality = variants.maxOf { Quality.of(it.name) }

            val order = variants
                .map { Federal.match(it.name) }
                .filter { it > 0 }
                .minOrNull() ?: 0
            val def = Federal.defFor(order)

            val urls = LinkedHashSet<String>()
            options.preferred[key].orEmpty().forEach { urls.add(it) }
            sorted.forEach { urls.add(it.url) }
            if (urls.isEmpty()) continue

            val displayName = def?.name
                ?: sorted.firstOrNull { cyrillic.containsMatchIn(it.name) }
                    ?.let { Quality.displayName(it.name) }
                    ?.takeIf { it.isNotBlank() }
                ?: Quality.displayName(best.name).ifBlank { best.name }

            val logo = def?.logoAsset ?: sorted.firstNotNullOfOrNull { it.logo }
            val group = if (def != null) "Федеральные" else translateGroup(best.group)

            if (def == null) {
                if (options.hdOnly && quality < 720) continue
                if (options.hideUnstable && variants.all { it.unstable }) continue
                if (options.hideDead && options.isDead(key)) continue
                if (options.hideNoAudio && key in options.noAudioKeys) continue
            }

            val channel = Channel(
                key = key,
                name = displayName,
                urls = urls.toList(),
                logo = logo,
                group = group,
                quality = quality,
                number = 0,
                federal = def != null,
                favorite = key in options.favorites,
                epgNames = (def?.epgAliases ?: emptyList()) + displayName,
                tvgId = sorted.firstNotNullOfOrNull { it.tvgId?.takeIf { id -> id.isNotBlank() } },
                hasArchive = variants.any { it.hasArchive }
            )

            if (def != null) {
                federalCandidates.getOrPut(def.order) { mutableListOf() }.add(channel)
            } else {
                regular.add(channel)
            }
        }

        val federal = ArrayList<Channel>(20)
        for (def in Federal.defs) {
            val candidates = federalCandidates[def.order].orEmpty()
            val logo = candidates.firstOrNull { !it.logo.isNullOrBlank() }?.logo
                ?: def.logoAsset

            federal.add(
                Channel(
                    key = "fed:${def.order}",
                    name = def.name,
                    urls = def.urls,
                    logo = logo,
                    group = "Федеральные",
                    quality = def.quality,
                    number = def.order,
                    federal = true,
                    favorite = ("fed:${def.order}" in options.favorites),
                    epgNames = (def.epgAliases + def.name).distinct(),
                    tvgId = null,
                    hasArchive = false
                )
            )
        }

        val orderIndex = HashMap<String, Int>()
        options.order.forEachIndexed { index, key -> orderIndex[key] = index }

        fun sorted(list: List<Channel>): List<Channel> = list.sortedWith(
            compareByDescending<Channel> { it.favorite }
                .thenBy { orderIndex[it.key] ?: Int.MAX_VALUE }
                .thenByDescending { it.quality }
                .thenBy { it.name }
        )

        val favoriteChannels = sorted((federal + regular).filter { it.favorite })

        val rows = ArrayList<CatalogRow>()
        if (favoriteChannels.isNotEmpty()) {
            rows.add(CatalogRow("Избранное", favoriteChannels))
        }
        rows.add(CatalogRow("Федеральные каналы", federal))

        val byGroup = regular.groupBy { it.group }
        val usedGroups = LinkedHashSet<String>()
        for (group in groupOrder) {
            val channels = byGroup[group] ?: continue
            usedGroups.add(group)
            rows.add(CatalogRow(group, sorted(channels)))
        }
        for ((group, channels) in byGroup) {
            if (group in usedGroups) continue
            rows.add(CatalogRow(group, sorted(channels)))
        }

        val numberByKey = HashMap<String, Int>()
        federal.forEach { numberByKey[it.key] = it.number }
        var number = 21
        for (row in rows) {
            if (row.title == "Федеральные каналы" || row.title == "Избранное") continue
            for (channel in row.channels) {
                if (numberByKey[channel.key] == null) numberByKey[channel.key] = number++
            }
        }

        val numberedRows = rows.map { row ->
            CatalogRow(
                row.title,
                row.channels.map { channel ->
                    channel.copy(number = numberByKey[channel.key] ?: 0)
                }
            )
        }

        val all = numberedRows.flatMap { it.channels }.distinctBy { it.key }
        return Catalog(
            rows = numberedRows,
            total = all.size,
            hd = all.count { it.quality >= 720 },
            federal = federal.size,
            updatedAt = updatedAt
        )
    }

    fun keyOf(channel: RawChannel): String {
        val id = channel.tvgId?.trim().orEmpty()
        if (id.isNotEmpty()) {
            val parts = id.split('@', limit = 2)
            val base = parts[0].substringBefore('.').lowercase().replace(nonAlphanumeric, "")
            var suffix = parts.getOrNull(1)?.lowercase()?.trim().orEmpty()
            if (suffix in qualitySuffixes) suffix = ""
            if (base.isNotEmpty() && base != "ru" && base != "tv") {
                return "id:$base" + if (suffix.isEmpty()) "" else "|$suffix"
            }
        }
        return "n:" + normalize(channel.name)
    }

    fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(qualityTag, " ")
            .replace(bracketTag, " ")
            .replace(plusShift, " ")
            .replace(nonAlphanumeric, "")

    private val groupAliases = mapOf(
        "новости" to "Новости",
        "общие" to "Общие",
        "кино" to "Кино",
        "сериалы" to "Сериалы",
        "спорт" to "Спорт",
        "детям" to "Детям",
        "мультфильмы" to "Мультфильмы",
        "познавательные" to "Познавательные",
        "документальные" to "Документальные",
        "развлечения" to "Развлечения",
        "музыка" to "Музыка",
        "культура" to "Культура",
        "юмор" to "Юмор",
        "религия" to "Религия",
        "путешествия" to "Путешествия",
        "охота и рыбалка" to "Охота и рыбалка",
        "авто" to "Авто",
        "кулинария" to "Кулинария",
        "семейные" to "Семейные",
        "стиль жизни" to "Стиль жизни",
        "мода" to "Мода",
        "наука" to "Наука",
        "бизнес" to "Бизнес",
        "политика" to "Политика",
        "парламент" to "Парламент",
        "магазины" to "Магазины",
        "погода" to "Погода",
        "классика" to "Классика",
        "релакс" to "Релакс",
        "прочее" to "Прочее"
    )

    private fun translateGroup(raw: String): String {
        val clean = raw.substringBefore(';').trim()
        val key = clean.lowercase()
        return groupNames[key] ?: groupAliases[key] ?: clean.ifBlank { "Прочее" }
    }
}
