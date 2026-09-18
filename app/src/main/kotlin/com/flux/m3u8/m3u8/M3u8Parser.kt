package com.flux.m3u8.m3u8

import com.flux.m3u8.model.*
import java.net.URL

/**
 * M3U8 / HLS 播放列表解析器。
 *
 * 覆盖目前主流站点的绝大多数形态：
 *  · Master Playlist（EXT-X-STREAM-INF 多清晰度）
 *  · Media Playlist（EXTINF / EXT-X-BYTERANGE / EXT-X-DISCONTINUITY）
 *  · AES-128 加密（EXT-X-KEY，含自定义 IV）
 *  · fMP4（EXT-X-MAP 初始化段）
 *  · 直播流（无 EXT-X-ENDLIST）
 */
object M3u8Parser {

    private const val HEAD_LIMIT = 4000

    /**
     * 解析结果：二选一。
     * 用密封类保证调用方必须处理两种情况，避免漏判 Master 列表。
     */
    sealed class Result {
        data class Master(val playlist: MasterPlaylist) : Result()
        data class Media(val playlist: MediaPlaylist) : Result()
    }

    fun parse(text: String, baseUrl: String): Result {
        val variants = mutableListOf<Variant>()
        val segments = mutableListOf<Segment>()

        var currentKey: KeyInfo? = null
        var currentMap: MapInfo? = null
        var pendingDuration = 0.0
        var pendingByteRange: Pair<Long, Long>? = null
        var pendingDiscontinuity = false
        var nextOffset = 0L

        var mediaSequence = 0L
        var targetDuration = 0
        var version = 0
        var endList = false
        var variantAttrs: Map<String, String>? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    variantAttrs = parseAttrs(line.substringAfter(':'))
                }

                line.startsWith("#EXT-X-MEDIA") -> Unit  // 外挂音轨/字幕：当前版本只取视频轨

                line.startsWith("#EXT-X-KEY") -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    currentKey = if (method.equals("NONE", true)) null else KeyInfo(
                        method = method,
                        uri = a["URI"]?.let { resolve(baseUrl, it) },
                        ivHex = a["IV"],
                        keyFormat = a["KEYFORMAT"] ?: "identity"
                    )
                }

                line.startsWith("#EXT-X-MAP") -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    val uri = a["URI"]?.let { resolve(baseUrl, it) } ?: ""
                    currentMap = MapInfo(uri, a["BYTERANGE"]?.let { parseByteRange(it, 0) })
                }

                line.startsWith("#EXT-X-BYTERANGE") -> {
                    pendingByteRange = parseByteRange(line.substringAfter(':'), nextOffset)
                }

                line.startsWith("#EXTINF") -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }

                line.startsWith("#EXT-X-DISCONTINUITY") -> pendingDiscontinuity = true

                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0

                line.startsWith("#EXT-X-TARGETDURATION") ->
                    targetDuration = line.substringAfter(':').trim().toIntOrNull() ?: 0

                line.startsWith("#EXT-X-VERSION") ->
                    version = line.substringAfter(':').trim().toIntOrNull() ?: 0

                line.startsWith("#EXT-X-ENDLIST") -> endList = true

                line.startsWith("#") -> Unit

                else -> {
                    // 非注释行：要么是清晰度地址，要么是分片地址
                    val attrs = variantAttrs
                    if (attrs != null) {
                        variants.add(
                            Variant(
                                url = resolve(baseUrl, line),
                                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
                                resolution = attrs["RESOLUTION"] ?: "",
                                codecs = attrs["CODECS"] ?: "",
                                frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0
                            )
                        )
                        variantAttrs = null
                    } else {
                        val idx = segments.size
                        segments.add(
                            Segment(
                                index = idx,
                                uri = resolve(baseUrl, line),
                                duration = pendingDuration,
                                byteRange = pendingByteRange,
                                key = currentKey,
                                discontinuity = pendingDiscontinuity,
                                initMap = currentMap,
                                mediaSequence = mediaSequence + idx
                            )
                        )
                        if (pendingByteRange != null) {
                            nextOffset = pendingByteRange!!.second + pendingByteRange!!.first
                        }
                        pendingByteRange = null
                        pendingDiscontinuity = false
                        pendingDuration = 0.0
                    }
                }
            }
        }

        return if (variants.isNotEmpty()) {
            Result.Master(MasterPlaylist(variants))
        } else {
            Result.Media(
                MediaPlaylist(
                    segments = segments,
                    targetDuration = targetDuration,
                    mediaSequence = mediaSequence,
                    endList = endList,
                    version = version
                )
            )
        }
    }

    /**
     * 解析标签属性，兼容引号内包含逗号的情况（如 CODECS="avc1.64001f,mp4a.40.2"）。
     */
    private fun parseAttrs(value: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < value.length) {
            val eq = value.indexOf('=', i)
            if (eq < 0) break
            val key = value.substring(i, eq).trim().uppercase()
            i = eq + 1
            if (i < value.length && value[i] == '"') {
                i++
                val end = value.indexOf('"', i).let { if (it < 0) value.length else it }
                out[key] = value.substring(i, end)
                i = end + 1
                if (i < value.length && value[i] == ',') i++
            } else {
                val end = value.indexOf(',', i).let { if (it < 0) value.length else it }
                out[key] = value.substring(i, end).trim()
                i = end + 1
            }
        }
        return out
    }

    /** EXT-X-BYTERANGE:<length>[@<offset>]，省略 offset 时延续上一段末尾。 */
    private fun parseByteRange(value: String, defaultOffset: Long): Pair<Long, Long> {
        val parts = value.split('@')
        val length = parts[0].trim().toLongOrNull() ?: 0L
        val offset = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: defaultOffset
        return length to offset
    }

    /** 相对地址补全为绝对地址。 */
    fun resolve(base: String, ref: String): String = try {
        URL(URL(base), ref).toString()
    } catch (e: Exception) {
        when {
            ref.startsWith("http", true) -> ref
            else -> base.substringBeforeLast('/') + "/" + ref
        }
    }

    /** 校验响应内容是否确实是 m3u8。 */
    fun isValid(text: String): Boolean {
        val head = if (text.length > HEAD_LIMIT) text.substring(0, HEAD_LIMIT) else text
        return head.contains("#EXTM3U")
    }

    /** 从 URL 猜一个可读的文件名。 */
    fun guessName(url: String): String {
        val base = url.substringBefore('?').substringAfterLast('/')
        val cleaned = base
            .replace(Regex("\\.(m3u8|m3u|ts|mp4)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
        return cleaned.ifBlank { "video" }.take(80)
    }
}
