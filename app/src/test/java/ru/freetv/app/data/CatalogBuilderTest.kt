package ru.freetv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBuilderTest {

    private fun raw(
        name: String,
        url: String,
        tvgId: String? = null,
        group: String = "General"
    ) = RawChannel(
        name = name,
        url = url,
        logo = null,
        tvgId = tvgId,
        group = group,
        unstable = false
    )

    private fun options() = CatalogBuilder.Options(
        hdOnly = false,
        hideUnstable = true,
        hideDead = true,
        isDead = { false },
        preferred = emptyMap(),
        favorites = emptySet(),
        order = emptyList(),
        noAudioKeys = emptySet(),
        hideNoAudio = false
    )

    @Test
    fun `builds 20 federal channels from hardcoded sources`() {
        val catalog = CatalogBuilder.build(
            raw = listOf(
                raw("Россия 1 (1080p)", "http://example.com/r1.m3u8", tvgId = "Russia1.ru")
            ),
            options = options(),
            updatedAt = 0L
        )

        assertEquals(20, catalog.federal)
        val federal = catalog.allChannels.filter { it.federal }
        assertEquals(20, federal.size)

        // Россия-1 берёт источники из Federal.defs, а не из плейлиста
        val russia1 = federal.first { it.name == "Россия-1" }
        assertTrue(russia1.urls.first().contains("smotrim.ru"))
        assertEquals(2, russia1.number)
    }

    @Test
    fun `numbers non-federal channels from 21`() {
        val catalog = CatalogBuilder.build(
            raw = listOf(
                raw("Some Channel (576p)", "http://example.com/s.m3u8", tvgId = "SomeChannel.ru")
            ),
            options = options(),
            updatedAt = 0L
        )

        val regular = catalog.allChannels.first { !it.federal }
        assertEquals(21, regular.number)
    }

    @Test
    fun `deduplicates variants by tvg-id and picks best quality`() {
        val catalog = CatalogBuilder.build(
            raw = listOf(
                raw("Some Channel (576p)", "http://a.m3u8", tvgId = "SomeChannel.ru"),
                raw("Some Channel (1080p)", "http://b.m3u8", tvgId = "SomeChannel.ru")
            ),
            options = options(),
            updatedAt = 0L
        )

        val regular = catalog.allChannels.filter { !it.federal }
        assertEquals(1, regular.size)
        assertEquals(1080, regular[0].quality)
    }

    @Test
    fun `keyOf normalizes tvg-id`() {
        assertEquals(
            "id:russia1",
            CatalogBuilder.keyOf(raw("x", "u", tvgId = "Russia1.ru@HD"))
        )
        assertEquals(
            "id:somechannel|plus2",
            CatalogBuilder.keyOf(raw("x", "u", tvgId = "SomeChannel.ru@Plus2"))
        )
    }

    @Test
    fun `keyOf falls back to normalized name`() {
        assertEquals(
            "n:первыйканал",
            CatalogBuilder.keyOf(raw("Первый канал (1080p)", "u", tvgId = null))
        )
    }

    @Test
    fun `normalize strips quality and bracket tags`() {
        assertEquals(
            "пятница",
            CatalogBuilder.normalize("Пятница! (576p) [Not 24/7]")
        )
        assertEquals(
            "россия1hd",
            CatalogBuilder.normalize("Россия 1 HD")
        )
    }

    @Test
    fun `hides channels marked dead`() {
        val dead = CatalogBuilder.Options(
            hdOnly = false,
            hideUnstable = true,
            hideDead = true,
            isDead = { key -> key == "id:dead" },
            preferred = emptyMap(),
            favorites = emptySet(),
            order = emptyList(),
            noAudioKeys = emptySet(),
            hideNoAudio = false
        )

        val catalog = CatalogBuilder.build(
            raw = listOf(
                raw("Dead (576p)", "http://dead.m3u8", tvgId = "Dead.ru"),
                raw("Alive (576p)", "http://alive.m3u8", tvgId = "Alive.ru")
            ),
            options = dead,
            updatedAt = 0L
        )

        val names = catalog.allChannels.map { it.name }
        assertFalse(names.any { it.contains("Dead") })
        assertTrue(names.any { it.contains("Alive") })
    }

    @Test
    fun `hides junk categories by default`() {
        val opts = options().copy(hiddenGroups = CatalogBuilder.JUNK_GROUPS)
        val catalog = CatalogBuilder.build(
            raw = listOf(
                raw("Магазин на диване (576p)", "http://shop.m3u8", tvgId = "Shop.ru", group = "Shop"),
                raw("Хороший музыкальный (576p)", "http://music.m3u8", tvgId = "Music.ru", group = "Music")
            ),
            options = opts,
            updatedAt = 0L
        )

        val names = catalog.allChannels.map { it.name }
        assertFalse("магазины должны быть скрыты", names.any { it.contains("Магазин") })
        assertTrue(names.any { it.contains("музыкальный") })
    }

    @Test
    fun `shows junk categories when hiddenGroups is empty`() {
        val catalog = CatalogBuilder.build(
            raw = listOf(
                raw("Магазин на диване (576p)", "http://shop.m3u8", tvgId = "Shop.ru", group = "Shop")
            ),
            options = options(),
            updatedAt = 0L
        )

        assertTrue(catalog.allChannels.any { it.name.contains("Магазин") })
    }
}
