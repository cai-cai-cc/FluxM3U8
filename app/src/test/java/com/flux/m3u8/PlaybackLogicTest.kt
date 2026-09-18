package com.flux.m3u8

import com.flux.m3u8.model.KeyInfo
import com.flux.m3u8.model.Segment
import com.flux.m3u8.playback.HttpRange
import com.flux.m3u8.playback.PlaylistBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 边下边播的纯逻辑单测（播放列表生成 + Range 解析）。 */
class PlaybackLogicTest {

    private fun seg(index: Int, uri: String = "https://a.com/$index.ts", dur: Double = 6.0) =
        Segment(index = index, uri = uri, duration = dur)

    @Test
    fun localPlaylistIsWellFormed() {
        val list = PlaylistBuilder.build(listOf(seg(0), seg(1)), 10)

        assertTrue(list.startsWith("#EXTM3U"))
        // 没有 PLAYLIST-TYPE:VOD 播放器会按直播处理，不允许拖动
        assertTrue(list.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(list.contains("#EXT-X-TARGETDURATION:10"))
        assertTrue(list.contains("#EXT-X-ENDLIST"))
        assertTrue(list.contains("s/0.ts"))
        assertTrue(list.contains("s/1.ts"))
        assertTrue(list.contains("#EXTINF:6.000,"))
        // 分片地址必须是本地相对路径，绝不能把远端地址直接暴露给播放器
        assertTrue(!list.contains("https://"))
    }

    @Test
    fun encryptedStreamPlaylistHasNoKeyTag() {
        val encrypted = seg(0).copy(
            key = KeyInfo(method = "AES-128", uri = "https://a.com/k.key", ivHex = null)
        )
        // 本地分片已是明文，再带 KEY 会让播放器二次解密，直接花屏
        assertTrue(!PlaylistBuilder.build(listOf(encrypted), 6).contains("EXT-X-KEY"))
    }

    @Test
    fun missingDurationFallsBackToTarget() {
        assertTrue(PlaylistBuilder.build(listOf(seg(0, dur = 0.0)), 8).contains("#EXTINF:8.000,"))
    }

    @Test
    fun resolveTargetDurationWorks() {
        assertEquals(10, PlaylistBuilder.resolveTargetDuration(listOf(seg(0)), 10))
        // 声明为 0（不少自建切片脚本会漏写）时按最长分片向上取整
        assertEquals(7, PlaylistBuilder.resolveTargetDuration(listOf(seg(0, dur = 6.2)), 0))
        // 全是 0 时给个安全默认值：写 0 会让部分播放器直接拒绝解析
        assertEquals(4, PlaylistBuilder.resolveTargetDuration(listOf(seg(0, dur = 0.0)), 0))
        assertEquals(4, PlaylistBuilder.resolveTargetDuration(emptyList(), 0))
    }

    @Test
    fun rangeWithoutHeaderIsFull() {
        assertTrue(HttpRange.parse(null, 100) is HttpRange.Result.Full)
        assertTrue(HttpRange.parse("", 100) is HttpRange.Result.Full)
        assertTrue(HttpRange.parse("bytes=abc", 100) is HttpRange.Result.Full)
    }

    @Test
    fun rangeNormal() {
        val r = HttpRange.parse("bytes=0-9", 100) as HttpRange.Result.Part
        assertEquals(0, r.start)
        assertEquals(9, r.endInclusive)
        assertEquals(10, r.length)
    }

    @Test
    fun rangeOpenEnded() {
        val r = HttpRange.parse("bytes=10-", 100) as HttpRange.Result.Part
        assertEquals(10, r.start)
        assertEquals(99, r.endInclusive)
    }

    @Test
    fun rangeSuffix() {
        val r = HttpRange.parse("bytes=-10", 100) as HttpRange.Result.Part
        assertEquals(90, r.start)
        assertEquals(99, r.endInclusive)
    }

    @Test
    fun rangeEndClampedToEntity() {
        val r = HttpRange.parse("bytes=0-199", 100) as HttpRange.Result.Part
        assertEquals(0, r.start)
        assertEquals(99, r.endInclusive)
    }

    @Test
    fun rangeStartBeyondEntityIsUnsatisfiable() {
        assertTrue(HttpRange.parse("bytes=100-199", 100) is HttpRange.Result.Unsatisfiable)
        assertTrue(HttpRange.parse("bytes=150-", 100) is HttpRange.Result.Unsatisfiable)
    }

    @Test
    fun rangeStartGreaterThanEndIsUnsatisfiable() {
        assertTrue(HttpRange.parse("bytes=50-20", 100) is HttpRange.Result.Unsatisfiable)
    }

    @Test
    fun rangeOnEmptyEntityDoesNotCrash() {
        assertTrue(HttpRange.parse("bytes=0-9", 0) is HttpRange.Result.Full)
        assertTrue(HttpRange.parse("bytes=0-9", -1) is HttpRange.Result.Full)
    }

    @Test
    fun contentRangeHeaderFormat() {
        assertEquals("bytes 0-9/100", HttpRange.contentRange(0, 9, 100))
    }
}
