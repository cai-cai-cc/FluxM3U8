package com.flux.m3u8.model

/**
 * 任务状态。
 *
 * 只有 [Downloading]、[Parsing]、[Merging]、[Queued] 会被视为"活跃"，
 * 活跃任务会触发前台服务保活（常驻通知 + 唤醒锁）。
 */
enum class TaskStatus(val cn: String) {
    Queued("排队中"),
    Parsing("解析中"),
    Downloading("下载中"),
    Merging("合并中"),
    Paused("已暂停"),
    Completed("已完成"),
    Error("失败"),
    Canceled("已取消");

    val isActive: Boolean
        get() = this == Queued || this == Parsing || this == Downloading || this == Merging

    /**
     * 是否正在**实际占用下载槽位**。
     *
     * 和 [isActive] 的区别：[Queued] 不算——它只是在等前面的任务让位。
     * 算并发上限时必须用这个，否则排队中的任务会把槽位占满，前面的下完了
     * 后面也永远轮不到（排队数 = 上限时表现为"完全不动"）。
     */
    val isRunning: Boolean
        get() = this == Parsing || this == Downloading || this == Merging

    companion object {
        fun from(name: String): TaskStatus =
            values().firstOrNull { it.name == name } ?: Queued
    }
}

/** EXT-X-KEY：分片加密信息。 */
data class KeyInfo(
    val method: String,
    val uri: String?,
    val ivHex: String?,
    val keyFormat: String = "identity"
) {
    val encrypted: Boolean
        get() = method.equals("AES-128", true) || method.equals("SAMPLE-AES", true)
}

/** EXT-X-MAP：fMP4 的初始化段。 */
data class MapInfo(
    val uri: String,
    val byteRange: Pair<Long, Long>? = null
)

/** 单个分片。 */
data class Segment(
    val index: Int,
    val uri: String,
    val duration: Double = 0.0,
    val byteRange: Pair<Long, Long>? = null,   // (length, offset)
    val key: KeyInfo? = null,
    val discontinuity: Boolean = false,
    val initMap: MapInfo? = null,
    val mediaSequence: Long = 0
) {
    val fileName: String
        get() = "%07d.ts".format(index)
}

/** Media Playlist：真正包含分片地址的播放列表。 */
data class MediaPlaylist(
    val segments: List<Segment>,
    val targetDuration: Int = 0,
    val mediaSequence: Long = 0,
    val endList: Boolean = false,
    val version: Int = 0
) {
    val duration: Double get() = segments.sumOf { it.duration }
    val isLive: Boolean get() = !endList
    val isFmp4: Boolean get() = segments.firstOrNull()?.initMap != null
    val encrypted: Boolean get() = segments.any { it.key?.encrypted == true }
}

/** Master Playlist 中的一条清晰度。 */
data class Variant(
    val url: String,
    val bandwidth: Long = 0,
    val resolution: String = "",
    val codecs: String = "",
    val frameRate: Double = 0.0
) {
    val height: Int
        get() = resolution.split("x").getOrNull(1)?.toIntOrNull() ?: 0

    val label: String
        get() = when {
            height > 0 -> "${height}P"
            bandwidth > 0 -> "${bandwidth / 1000}kbps"
            else -> "默认"
        }
}

/** Master Playlist：多个清晰度的索引。 */
data class MasterPlaylist(val variants: List<Variant>) {
    fun sorted(): List<Variant> = variants.sortedWith(
        compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth }
    )
}

/** 解析预览结果，用于新建任务前展示信息。 */
data class ProbeInfo(
    val isMaster: Boolean,
    val variants: List<Variant> = emptyList(),
    val durationSeconds: Int = 0,
    val segmentCount: Int = 0,
    val encrypted: Boolean = false,
    val isLive: Boolean = false,
    val isFmp4: Boolean = false,
    val suggestedName: String = ""
)

/** 下载任务（同时是数据库行与 UI 渲染的数据源）。 */
data class DownloadTask(
    val id: String,
    val url: String,
    val name: String,
    val saveDirUri: String,          // 保存目录（树的 URI 或 "internal"）
    val status: TaskStatus = TaskStatus.Queued,
    val variantLabel: String = "",
    val totalSegments: Int = 0,
    val doneSegments: Int = 0,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val estimated: Boolean = false,  // 总大小是否为估算
    val isLive: Boolean = false,
    val encrypted: Boolean = false,
    val speedBps: Long = 0,
    val error: String? = null,
    val outputUri: String? = null,
    val outputName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    // 运行时选项（不落库的字段用默认值即可）
    val connections: Int = 8,
    val toMp4: Boolean = false,
    val referer: String = "",
    val userAgent: String = "",
    val cookie: String = "",
    val variantIndex: Int? = null,
    // 合并/转换阶段进度（0~1，运行时字段，不落库）
    val mergeProgress: Float = 0f,
    // 合并/转换当前阶段文案（如「合并分片中…」「转换为 MP4…」，运行时字段，不落库）
    val phaseLabel: String = ""
) {
    val progress: Float
        get() = when {
            // 已完成就是 100%：totalBytes 是估算值时（总长度未知，按平均分片大小推算），
            // doneBytes/totalBytes 常常算出来是 0.99，列表就会永远停在 99% 让人以为没下完。
            status == TaskStatus.Completed -> 1f
            totalBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            totalSegments > 0 -> (doneSegments.toFloat() / totalSegments).coerceIn(0f, 1f)
            else -> 0f
        }

    val etaSeconds: Long
        get() {
            if (speedBps <= 0 || totalBytes <= 0) return 0
            return ((totalBytes - doneBytes) / speedBps).coerceAtLeast(0)
        }
}
