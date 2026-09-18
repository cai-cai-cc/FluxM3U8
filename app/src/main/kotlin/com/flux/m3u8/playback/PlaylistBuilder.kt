package com.flux.m3u8.playback

import com.flux.m3u8.model.Segment
import java.util.Locale

/**
 * 生成边下边播用的**本地**播放列表。
 *
 * 与远端播放列表的关键差异：
 *  · 分片地址换成 `s/<序号>.ts`，由本地代理提供
 *  · **不写 EXT-X-KEY**——本地分片（以及按需回源的分片）都已经是解密后的明文
 *  · 强制 `#EXT-X-ENDLIST` 与 `PLAYLIST-TYPE:VOD`，
 *    否则播放器会当成直播流，不允许拖动到未缓冲位置
 */
object PlaylistBuilder {

    private const val DEFAULT_TARGET_DURATION = 4

    fun build(segments: List<Segment>, targetDuration: Int): String {
        val target = resolveTargetDuration(segments, targetDuration)
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        segments.forEachIndexed { i, seg ->
            val dur = if (seg.duration > 0) seg.duration else target.toDouble()
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", dur)).append(",\n")
            sb.append("s/").append(i).append(".ts\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /**
     * 目标时长：优先用播放列表声明的值；缺失时用最长分片向上取整兜底。
     * 必须是正整数，写 0 会让部分播放器直接拒绝解析。
     */
    fun resolveTargetDuration(segments: List<Segment>, declared: Int): Int {
        if (declared > 0) return declared
        val maxDur = segments.maxOfOrNull { it.duration } ?: 0.0
        val ceil = kotlin.math.ceil(maxDur).toInt()
        return if (ceil > 0) ceil else DEFAULT_TARGET_DURATION
    }
}
