package ru.freetv.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {

    @Test
    fun `detects resolution from parens`() {
        assertEquals(1080, Quality.of("Первый канал (1080p)"))
        assertEquals(720, Quality.of("Канал (720p)"))
        assertEquals(576, Quality.of("Канал (576p)"))
        assertEquals(480, Quality.of("Канал (480i)"))
    }

    @Test
    fun `detects resolution from words`() {
        assertEquals(1080, Quality.of("Канал FHD"))
        assertEquals(720, Quality.of("Канал HD"))
        assertEquals(2160, Quality.of("Канал 4K"))
        assertEquals(480, Quality.of("Канал SD"))
    }

    @Test
    fun `returns zero when quality unknown`() {
        assertEquals(0, Quality.of("Канал"))
    }

    @Test
    fun `label maps quality to short name`() {
        assertEquals("4K", Quality.label(2160))
        assertEquals("FHD", Quality.label(1080))
        assertEquals("HD", Quality.label(720))
        assertEquals("SD", Quality.label(576))
        assertEquals("", Quality.label(0))
    }

    @Test
    fun `displayName strips tags`() {
        assertEquals("Россия 1", Quality.displayName("Россия 1 (1080p) [Not 24/7]"))
        assertEquals("Пятница!", Quality.displayName("Пятница! (576p)"))
    }
}
