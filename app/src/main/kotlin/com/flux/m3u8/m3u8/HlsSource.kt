package com.flux.m3u8.m3u8

import com.flux.m3u8.crypto.HlsCrypto
import com.flux.m3u8.data.Settings
import com.flux.m3u8.download.HttpFactory
import com.flux.m3u8.model.MediaPlaylist
import com.flux.m3u8.model.Segment
import okhttp3.Request
import java.io.IOException

/**
 * HLS 资源的统一取流入口。
 *
 * 之前 [com.flux.m3u8.download.TaskRunner] 和
 * [com.flux.m3u8.playback.PlaybackManager] 各写了一份
 * `fetchText / fetchBytes / fetchKeys / 解析 Master` 的逻辑，
 * 两边一旦改动就会走偏（比如一边加了超时处理另一边没有）。
 * 这里收敛成一处，两边都只调它。
 *
 * 所有方法都是**阻塞式**的，调用方负责切到 IO 线程。
 */
class HlsSource(private val settings: Settings) {

    /**
     * 拉取播放列表文本并校验。
     * @return 文本内容与最终 URL
     */
    fun fetchText(url: String, headers: Map<String, String>): Pair<String, String> {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        HttpFactory.get(settings).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("响应为空")
            if (!M3u8Parser.isValid(body)) throw IOException("地址返回的不是有效的 m3u8 内容")
            return body to url
        }
    }

    /** 拉取二进制内容，可带 Range。 */
    fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        range: Pair<Long, Long>? = null
    ): ByteArray {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            range?.let { (len, off) -> header("Range", "bytes=$off-${off + len - 1}") }
        }.build()
        HttpFactory.get(settings).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IOException("响应为空")
        }
    }

    /**
     * 解析出真正的 Media Playlist。
     *
     * @param variantIndex Master 列表里要选哪一档（排序后的下标），null 表示取最高画质
     * @return 三元组：媒体列表 URL、媒体列表、清晰度标签
     */
    fun resolveMediaPlaylist(
        url: String,
        headers: Map<String, String>,
        variantIndex: Int? = null
    ): Triple<String, MediaPlaylist, String> {
        val (text, finalUrl) = fetchText(url, headers)
        return when (val result = M3u8Parser.parse(text, finalUrl)) {
            is M3u8Parser.Result.Master -> {
                val variants = result.playlist.sorted()
                if (variants.isEmpty()) throw IllegalStateException("没有可用的清晰度")
                val chosen = variantIndex?.let { variants.getOrNull(it) } ?: variants.first()
                val (subText, subUrl) = fetchText(chosen.url, headers)
                val sub = M3u8Parser.parse(subText, subUrl)
                if (sub !is M3u8Parser.Result.Media) {
                    throw IllegalStateException("清晰度地址不是有效的播放列表")
                }
                Triple(subUrl, sub.playlist, chosen.label)
            }
            is M3u8Parser.Result.Media -> Triple(finalUrl, result.playlist, "")
        }
    }

    /** 拉取分片用到的全部 AES 密钥（按 URI 去重）。 */
    fun fetchKeys(
        segments: List<Segment>,
        headers: Map<String, String>
    ): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        for (seg in segments) {
            val key = seg.key ?: continue
            val uri = key.uri ?: continue
            if (!key.encrypted || out.containsKey(uri)) continue
            val raw = fetchBytes(uri, headers)
            out[uri] = HlsCrypto.normalizeKey(raw)
        }
        return out
    }

    /** 拉取 fMP4 的初始化段（EXT-X-MAP）。 */
    fun fetchInitMap(
        segments: List<Segment>,
        headers: Map<String, String>
    ): ByteArray? {
        val map = segments.firstOrNull()?.initMap ?: return null
        return fetchBytes(map.uri, headers, map.byteRange)
    }
}
