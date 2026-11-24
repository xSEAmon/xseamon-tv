package ru.freetv.app.data

/**
 * 20 обязательных общедоступных телеканалов РФ.
 *
 * Для каждого канала задан список проверенных потоков (от лучшего к резервным)
 * и правила распознавания канала в произвольном M3U-плейлисте.
 */
data class FederalDef(
    val order: Int,
    val name: String,
    val urls: List<String>,
    val quality: Int,
    val logoAsset: String,
    val epgAliases: List<String>,
    val patterns: List<Regex>
)

object Federal {

    private fun p(vararg sources: String): List<Regex> =
        sources.map { Regex(it, RegexOption.IGNORE_CASE) }

    private val missingLogoAssets = setOf(2, 14)

    private fun logo(order: Int): String =
        if (order in missingLogoAssets) ""
        else "file:///android_asset/logos/f" + order.toString().padStart(2, '0') + ".png"

    val defs: List<FederalDef> = listOf(
        FederalDef(
            1, "Первый канал",
            listOf(
                "https://bl.rutube.ru/livestream/c58f502c7bb34a8fcdd976b221fca292/index.m3u8?s=LR6a5zcWehcRKTm3o61phg&e=2095001779&scheme=https",
                "https://streaming.thestream.cyou/live/210.m3u8",
                "https://stream8.cinerama.uz/1214/tracks-v1a1/mono.m3u8",
                "http://31.148.5.241/kanal_1/index.m3u8"
            ),
            720, logo(1),
            listOf("Первый канал"),
            p("^channel one\\s*\\(", "^первый\\s*канал", "^1\\s*канал")
        ),
        FederalDef(
            2, "Россия-1",
            listOf(
                "https://live.smotrim.ru/vgtrk/0/russia1-hd/index.m3u8",
                "https://stream.smotrim.ru/hls2/russia_hd/playlist_6.m3u8",
                "http://176.118.197.101/RossiyaHD/index.m3u8"
            ),
            1080, logo(2),
            listOf("Россия 1", "Россия-1"),
            p("^russia[- ]?1( hd)?\\s*\\(", "^rossiya[- ]?1( hd)?\\s*\\(", "^россия[- ]?1( hd)?\\s*\\(")
        ),
        FederalDef(
            3, "Матч ТВ",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_MATCHTV/variant.m3u8",
                "http://tv.tuva.ru/machtv/index.m3u8"
            ),
            576, logo(3),
            listOf("Матч ТВ", "Матч!"),
            p("^match!?\\s*tv\\s*\\(", "^матч!?\\s*тв\\s*\\(")
        ),
        FederalDef(
            4, "НТВ",
            listOf(
                "https://cdn-dvr.ntv.ru/ntv0_hd/index.m3u8",
                "https://cdn.ntv.ru/ntv0/playlist.m3u8",
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_NTV/variant.m3u8"
            ),
            1080, logo(4),
            listOf("НТВ"),
            p("^ntv\\s*\\(russia\\)", "^нтв\\s*\\(", "^ntv hd\\s*\\(")
        ),
        FederalDef(
            5, "Пятый канал",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_5TV/variant.m3u8"
            ),
            576, logo(5),
            listOf("Пятый канал"),
            p("^channel 5\\s*\\(russia\\)", "^пятый\\s*канал", "^5\\s*канал")
        ),
        FederalDef(
            6, "Россия-К",
            listOf(
                "https://live.smotrim.ru/vgtrk/0/kultura-hd/index.m3u8",
                "https://stream.smotrim.ru/hls2/russia_k/playlist_5.m3u8"
            ),
            1080, logo(6),
            listOf("Культура", "Россия К", "Россия-К"),
            p("^russia[- ]?k( hd)?\\s*\\(", "^россия[- ]?к( hd)?\\s*\\(", "^культура\\s*\\(")
        ),
        FederalDef(
            7, "Россия-24",
            listOf(
                "https://live.smotrim.ru/vgtrk/0/russia24-hd/index.m3u8",
                "http://31.148.5.241/kanal_7/index.m3u8"
            ),
            1080, logo(7),
            listOf("Россия 24", "Россия-24"),
            p("^russia[- ]?24( hd)?\\s*\\(", "^россия[- ]?24( hd)?\\s*\\(")
        ),
        FederalDef(
            8, "Карусель",
            listOf(
                "https://stream.smotrim.ru/hls2/karusel/playlist_3.m3u8",
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_KARUSEL/variant.m3u8",
                "http://185.37.150.46/Karusel/index.m3u8"
            ),
            720, logo(8),
            listOf("Карусель"),
            p("^carousel\\s*\\(", "^карусель\\s*\\(")
        ),
        FederalDef(
            9, "ОТР",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_OTR/variant.m3u8"
            ),
            576, logo(9),
            listOf("ОТР", "Общественное телевидение России"),
            p("^otr\\s*\\(", "^отр\\s*\\(")
        ),
        FederalDef(
            10, "ТВ Центр",
            listOf(
                "https://stream8.cinerama.uz/1025/tracks-v1a1/mono.m3u8",
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_TVC/variant.m3u8"
            ),
            576, logo(10),
            listOf("ТВ Центр", "ТВЦ"),
            p("^tv centr( hd)?\\s*\\(", "^тв\\s*центр( hd)?\\s*\\(", "^твц\\s*\\(")
        ),
        FederalDef(
            11, "РЕН ТВ",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_RENTV/variant.m3u8"
            ),
            576, logo(11),
            listOf("РЕН ТВ"),
            p("^ren tv\\s*\\(", "^рен\\s*тв\\s*\\(")
        ),
        FederalDef(
            12, "СПАС",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_SPAS/variant.m3u8",
                "https://tvspas.mediacdn.ru/cdn/spas/playlist.m3u8"
            ),
            576, logo(12),
            listOf("Спас ТВ", "Спас"),
            p("^spas\\s*\\(", "^спас\\s*\\(")
        ),
        FederalDef(
            13, "СТС",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_STS/variant.m3u8",
                "http://176.118.197.101/CTC/index.m3u8"
            ),
            576, logo(13),
            listOf("СТС"),
            p("^sts( hd)?\\s*\\(", "^стс\\s*\\(")
        ),
        FederalDef(
            14, "Домашний",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_DOMASHNIY/variant.m3u8"
            ),
            576, logo(14),
            listOf("Домашний"),
            p("^domashniy\\s*\\(", "^домашний\\s*\\(")
        ),
        FederalDef(
            15, "ТВ-3",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_TV3/variant.m3u8",
                "http://176.118.197.101/TB3/index.m3u8"
            ),
            576, logo(15),
            listOf("ТВ-3", "ТВ3"),
            p("^tv-?3( hd)?\\s*\\(", "^тв-?3( hd)?\\s*\\(")
        ),
        FederalDef(
            16, "Пятница!",
            listOf(
                "http://31.148.5.241/kanal_16/index.m3u8",
                "https://vod.tuva.ru/friday/index.m3u8",
                "http://tv.tuva.ru/friday/index.m3u8",
                "https://stream8.cinerama.uz/1205/tracks-v1a1/mono.m3u8"
            ),
            576, logo(16),
            listOf("Пятница!", "Пятница"),
            p("^friday!?\\s*\\(", "^пятница!?\\s*\\(")
        ),
        FederalDef(
            17, "Звезда",
            listOf(
                "https://tvzvezda.bonus-tv.ru/cdn/tvzvezda/playlist.m3u8",
                "https://tvchannelstream1.tvzvezda.ru/cdn/tvzvezda/playlist.m3u8"
            ),
            1080, logo(17),
            listOf("Звезда"),
            p("^zvezda( hd)?\\s*\\(", "^звезда( hd)?\\s*\\(")
        ),
        FederalDef(
            18, "Мир",
            listOf(
                "http://hls.mirtv.cdnvideo.ru/mirtv-parampublish/mirtv_2500/playlist.m3u8",
                "http://tvcdn01.esilnet.kz/tv/mir/index.m3u8"
            ),
            1080, logo(18),
            listOf("МИР", "Мир"),
            p("^mir( hd)?\\s*\\(", "^мир( hd)?\\s*\\(")
        ),
        FederalDef(
            19, "ТНТ",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_TNT/variant.m3u8",
                "http://176.118.197.101/THT/index.m3u8"
            ),
            576, logo(19),
            listOf("ТНТ"),
            p("^tnt\\s*\\(russia\\)", "^тнт\\s*\\(")
        ),
        FederalDef(
            20, "Муз-ТВ",
            listOf(
                "https://zabava-htlive.cdn.ngenix.net/hls/CH_MUZTV/variant.m3u8",
                "http://31.148.5.241/kanal_20/index.m3u8"
            ),
            576, logo(20),
            listOf("Муз-ТВ", "МУЗ ТВ"),
            p("^muz-tv\\s*\\(", "^муз[- ]?тв\\s*\\(")
        )
    )

    fun defFor(order: Int): FederalDef? = defs.firstOrNull { it.order == order }

    /** Возвращает номер федерального канала (1..20) или 0. */
    fun match(name: String): Int {
        for (def in defs) {
            for (pattern in def.patterns) {
                if (pattern.containsMatchIn(name)) return def.order
            }
        }
        return 0
    }
}
