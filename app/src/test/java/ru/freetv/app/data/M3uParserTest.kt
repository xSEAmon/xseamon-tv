package ru.freetv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses single channel with attributes`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="Russia1.ru" tvg-logo="https://example.com/logo.png" group-title="General",Россия 1 (1080p)
            https://example.com/stream.m3u8
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(1, result.size)
        val channel = result[0]
        assertEquals("Россия 1 (1080p)", channel.name)
        assertEquals("https://example.com/stream.m3u8", channel.url)
        assertEquals("Russia1.ru", channel.tvgId)
        assertEquals("https://example.com/logo.png", channel.logo)
        assertEquals("General", channel.group)
        assertFalse(channel.unstable)
    }

    @Test
    fun `skips EXTVLCOPT line and reads the url`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" group-title="G",Channel
            #EXTVLCOPT:http-user-agent=TestAgent
            http://example.com/live/index.m3u8
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(1, result.size)
        assertEquals("http://example.com/live/index.m3u8", result[0].url)
    }

    @Test
    fun `normalizes udp and rtp urls`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Multicast
            udp://@239.0.0.1:1234
            #EXTINF:-1,Multicast2
            rtp://@239.0.0.2:5678
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(2, result.size)
        assertEquals("udp://239.0.0.1:1234", result[0].url)
        assertEquals("rtp://239.0.0.2:5678", result[1].url)
    }

    @Test
    fun `skips unsupported schemes`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Bad
            ftp://example.com/stream
            #EXTINF:-1,Good
            https://example.com/ok.m3u8
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(1, result.size)
        assertEquals("https://example.com/ok.m3u8", result[0].url)
    }

    @Test
    fun `marks unstable channels`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="G",Channel [Not 24/7]
            https://example.com/a.m3u8
            #EXTINF:-1 group-title="G",Channel [Geo-blocked]
            https://example.com/b.m3u8
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(2, result.size)
        assertTrue(result[0].unstable)
        assertTrue(result[1].unstable)
    }

    @Test
    fun `handles empty input`() {
        assertTrue(M3uParser.parse("").isEmpty())
        assertTrue(M3uParser.parse("#EXTM3U\n").isEmpty())
    }
}
