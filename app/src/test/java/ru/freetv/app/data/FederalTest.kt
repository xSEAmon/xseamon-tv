package ru.freetv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FederalTest {

    @Test
    fun `matches all 20 federal channels by name`() {
        val cases = mapOf(
            "Первый канал" to 1,
            "Channel One (Russia) (1080p)" to 1,
            "Россия 1 (1080p)" to 2,
            "Россия-1 HD (720p)" to 2,
            "Матч ТВ (576p)" to 3,
            "Match! TV (576p)" to 3,
            "НТВ (576p)" to 4,
            "Пятый канал (576p)" to 5,
            "Россия-К (1080p)" to 6,
            "Россия 24 (1080p)" to 7,
            "Карусель (720p)" to 8,
            "ОТР (576p)" to 9,
            "ТВ Центр (576p)" to 10,
            "РЕН ТВ (576p)" to 11,
            "СПАС (576p)" to 12,
            "СТС (576p)" to 13,
            "Домашний (576p)" to 14,
            "ТВ-3 (576p)" to 15,
            "Пятница! (576p)" to 16,
            "Звезда (1080p)" to 17,
            "Мир (1080p)" to 18,
            "ТНТ (576p)" to 19,
            "Муз-ТВ (576p)" to 20
        )
        for ((name, expected) in cases) {
            assertEquals("channel: $name", expected, Federal.match(name))
        }
    }

    @Test
    fun `returns zero for unknown channel`() {
        assertEquals(0, Federal.match("Совершенно неизвестный канал (576p)"))
        assertEquals(0, Federal.match(""))
    }

    @Test
    fun `defines exactly 20 federal channels in order`() {
        assertEquals(20, Federal.defs.size)
        Federal.defs.forEachIndexed { index, def ->
            assertEquals(index + 1, def.order)
        }
    }

    @Test
    fun `every federal channel has at least one source`() {
        Federal.defs.forEach { def ->
            assertTrue("${def.name} has no sources", def.urls.isNotEmpty())
        }
    }

    @Test
    fun `federal sources prefer aac-capable hosts first`() {
        // После реорганизации источников первым для проблемных каналов идёт
        // надёжный HLS-поток (smotrim/zabava/cinerama), а не «сырой» IP-зеркало.
        val byName = Federal.defs.associateBy { it.name }

        assertTrue(
            "Россия-1 должна начинаться со smotrim",
            byName["Россия-1"]!!.urls.first().contains("smotrim.ru")
        )
        assertTrue(
            "Матч ТВ должен начинаться с zabava",
            byName["Матч ТВ"]!!.urls.first().contains("zabava")
        )
        assertTrue(
            "СТС должен начинаться с zabava",
            byName["СТС"]!!.urls.first().contains("zabava")
        )
        assertTrue(
            "ТНТ должен начинаться с zabava",
            byName["ТНТ"]!!.urls.first().contains("zabava")
        )
        assertTrue(
            "Пятница должна начинаться с zabava",
            byName["Пятница!"]!!.urls.first().contains("zabava")
        )
    }

    @Test
    fun `no federal source points to dead mcquack or uplink hosts`() {
        val deadHosts = listOf("mcquack.net", "uplink.kz")
        Federal.defs.forEach { def ->
            def.urls.forEach { url ->
                deadHosts.forEach { host ->
                    assertTrue("${def.name} uses dead host $host", !url.contains(host))
                }
            }
        }
    }
}
