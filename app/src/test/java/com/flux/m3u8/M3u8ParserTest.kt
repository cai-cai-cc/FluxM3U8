package com.flux.m3u8

import com.flux.m3u8.m3u8.M3u8Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8ParserTest {

    private val base = "https://example.com/vod/index.m3u8"

    private fun media(text: String) =
        (M3u8Parser.parse(text, base) as M3u8Parser.Result.Media).playlist

    @Test
    fun parseVodPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:9.009,
            seg0.ts
            #EXTINF:9.009,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = M3u8Parser.parse(text, base)
        assertTrue(result is M3u8Parser.Result.Media)
        val m = media(text)

        assertEquals(2, m.segments.size)
        assertEquals(10, m.targetDuration)
        assertEquals(18.018, m.duration, 0.001)
        assertTrue(m.endList)
        assertFalse(m.isLive)
        assertFalse(m.encrypted)
        assertFalse(m.isFmp4)

        // 分片地址必须补全成绝对地址，否则下载时肯定 404
        assertEquals("https://example.com/vod/seg0.ts", m.segments[0].uri)
        assertEquals("https://example.com/vod/seg1.ts", m.segments[1].uri)
        // 文件名按序号补零，保证合并顺序 = 播放顺序
        assertEquals("0000000.ts", m.segments[0].fileName)
        assertEquals("0000001.ts", m.segments[1].fileName)
    }

    @Test
    fun missingEndListMeansLive() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            seg0.ts
        """.trimIndent()
        val m = media(text)
        assertTrue(m.isLive)
        assertFalse(m.endList)
    }

    @Test
    fun parseByteRange() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:4.0,
            all.ts
            #EXT-X-BYTERANGE:2000@1000
            #EXTINF:4.0,
            all.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = media(text)

        // (length, offset)
        assertEquals(1000L to 0L, m.segments[0].byteRange)
        assertEquals(2000L to 1000L, m.segments[1].byteRange)
    }

    @Test
    fun parseAes128KeyAndIv() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.com/k.key",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = media(text)

        assertTrue(m.encrypted)
        val key = m.segments[0].key!!
        assertEquals("AES-128", key.method)
        assertEquals("https://example.com/k.key", key.uri)
        assertEquals("0x000102030405060708090a0b0c0d0e0f", key.ivHex)
    }

    @Test
    fun methodNoneIsNotEncrypted() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFalse(media(text).encrypted)
    }

    @Test
    fun parseExtMapAsFmp4() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = media(text)
        assertTrue(m.isFmp4)
        assertEquals("https://example.com/vod/init.mp4", m.segments[0].initMap!!.uri)
    }

    @Test
    fun parseMasterAndSortByQuality() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()

        val result = M3u8Parser.parse(text, base)
        assertTrue(result is M3u8Parser.Result.Master)
        val sorted = (result as M3u8Parser.Result.Master).playlist.sorted()

        assertEquals(listOf("1080P", "720P", "360P"), sorted.map { it.label })
        assertEquals("https://example.com/vod/1080p/index.m3u8", sorted[0].url)
    }

    @Test
    fun commaInsideQuotesIsPreserved() {
        // CODECS="avc1.64001f,mp4a.40.2"：按逗号粗暴 split 会把 codecs 截成 "avc1.64001f"
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2",FRAME-RATE=25.000
            v.m3u8
        """.trimIndent()
        val v = (M3u8Parser.parse(text, base) as M3u8Parser.Result.Master).playlist.variants.single()

        assertEquals("avc1.64001f,mp4a.40.2", v.codecs)
        assertEquals(25.0, v.frameRate, 0.001)
        assertEquals(1000000L, v.bandwidth)
    }

    @Test
    fun resolveRelativeUrls() {
        assertEquals("https://example.com/vod/d.ts", M3u8Parser.resolve(base, "d.ts"))
        assertEquals("https://example.com/x.ts", M3u8Parser.resolve(base, "/x.ts"))
        assertEquals("https://other.com/y.ts", M3u8Parser.resolve(base, "https://other.com/y.ts"))
    }

    @Test
    fun validateM3u8Content() {
        assertTrue(M3u8Parser.isValid("#EXTM3U\n#EXTINF:4,\na.ts"))
        assertFalse(M3u8Parser.isValid("<html><body>404</body></html>"))
    }

    @Test
    fun guessNameFromUrl() {
        assertEquals("video", M3u8Parser.guessName("https://a.com/p/video.m3u8?token=1"))
        assertEquals("movie", M3u8Parser.guessName("https://a.com/p/movie.mp4"))
        // 非法字符必须替换，否则写文件直接失败
        assertEquals("a_b", M3u8Parser.guessName("https://a.com/p/a:b.m3u8"))
        assertEquals("video", M3u8Parser.guessName("https://a.com/"))
    }
}
