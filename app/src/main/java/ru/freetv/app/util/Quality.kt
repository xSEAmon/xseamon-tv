package ru.freetv.app.util

object Quality {

    private val resolutionTag = Regex("""\(\s*(\d{3,4})\s*[pi]\s*\)""", RegexOption.IGNORE_CASE)
    private val hdWord = Regex("""\bhd\b""", RegexOption.IGNORE_CASE)
    private val bracketTag = Regex("""\[[^\]]*]""")
    private val manySpaces = Regex("""\s{2,}""")

    fun of(name: String): Int {
        resolutionTag.find(name)?.let { return it.groupValues[1].toInt() }
        val s = name.lowercase()
        return when {
            "2160" in s || "4k" in s || "uhd" in s -> 2160
            "1080" in s || "fhd" in s -> 1080
            "720" in s || hdWord.containsMatchIn(s) -> 720
            "576" in s || "480" in s || "sd" in s -> 480
            else -> 0
        }
    }

    fun label(quality: Int): String = when {
        quality >= 2160 -> "4K"
        quality >= 1080 -> "FHD"
        quality >= 720 -> "HD"
        quality > 0 -> "SD"
        else -> ""
    }

    fun displayName(raw: String): String =
        raw.replace(resolutionTag, "")
            .replace(bracketTag, "")
            .replace(manySpaces, " ")
            .trim()
}
