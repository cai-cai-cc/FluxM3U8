package com.flux.m3u8.playback

/**
 * HTTP Range 解析（RFC 7233 子集）。
 *
 * 抽成纯函数是为了能直接单测——之前它埋在 LocalPlayServer 里，
 * 想验证各种边界情况只能起真服务。
 */
object HttpRange {

    sealed class Result {
        /** 没有 Range 头，或头无法解析：返回整个实体。 */
        object Full : Result()

        /** 部分内容。 */
        data class Part(val start: Long, val endInclusive: Long) : Result() {
            val length: Long get() = (endInclusive - start + 1).coerceAtLeast(0)
        }

        /** 起点超出实体长度：应返回 416。 */
        data object Unsatisfiable : Result()
    }

    /**
     * @param header 原始 Range 头（形如 `bytes=0-1023`），可为 null
     * @param total 实体总长度
     */
    fun parse(header: String?, total: Long): Result {
        if (total <= 0) return Result.Full
        if (header.isNullOrBlank()) return Result.Full

        val value = header.substringAfter('=', "").trim()
        if (value.isEmpty()) return Result.Full

        val dash = value.indexOf('-')
        if (dash < 0) return Result.Full

        val first = value.substring(0, dash).trim()
        val second = value.substring(dash + 1).trim()

        // bytes=-500：最后 500 字节
        if (first.isEmpty()) {
            val suffix = second.toLongOrNull() ?: return Result.Full
            if (suffix <= 0) return Result.Full
            val start = (total - suffix).coerceAtLeast(0)
            return Result.Part(start, total - 1)
        }

        val start = first.toLongOrNull() ?: return Result.Full
        if (start >= total) return Result.Unsatisfiable

        // bytes=0- ：从 0 到末尾
        val end = if (second.isEmpty()) total - 1
        else (second.toLongOrNull() ?: return Result.Full).coerceAtMost(total - 1)

        if (end < start) return Result.Unsatisfiable
        return Result.Part(start, end)
    }

    /** 生成 Content-Range 响应头值。 */
    fun contentRange(start: Long, endInclusive: Long, total: Long): String =
        "bytes $start-$endInclusive/$total"
}
