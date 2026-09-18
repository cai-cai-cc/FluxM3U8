package com.flux.m3u8.playback

import android.content.Context
import com.flux.m3u8.data.Settings
import com.flux.m3u8.data.Storage
import com.flux.m3u8.download.HttpFactory
import com.flux.m3u8.m3u8.HlsSource
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 边下边播的调度中心。
 *
 * 支持场景：
 *  · **点播（VOD）任务**：下载中 / 暂停 / 失败但仍有分片 → 起本地代理播放
 *  · **已完成任务**：临时分片已清掉，直接播放输出文件
 *
 * 不支持：
 *  · **直播流**（无 EXT-X-ENDLIST）：播放列表边下边长、media sequence 会滑动，
 *    本地分片文件名按序号命名，无法与不断变化的远端列表对齐，故明确禁用
 *  · **已取消任务**：临时分片已被删除
 *
 * 触发条件（[isPlayable]）：
 *  · 非直播、非取消
 *  · 至少已下载 2 个分片，或已下载 ≥ 6 MB
 *    —— 少于这个量播放器一开播就卡住，体验反而更差
 */
object PlaybackManager {

    /** 至少要有这么多分片才允许开播。 */
    private const val MIN_SEGMENTS = 2
    /** 或者至少已下载这么多字节。 */
    private const val MIN_BYTES = 6L * 1024 * 1024

    private lateinit var app: Context
    private lateinit var settings: Settings
    private lateinit var storage: Storage

    private val server by lazy { LocalPlayServer { HttpFactory.get(settings) } }

    /** 与 TaskRunner 共用同一套取流实现。 */
    private val source by lazy { HlsSource(settings) }

    private val tokens = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        settings = Settings(app)
        storage = Storage(app)
        sweepPlayCache()
    }

    /**
     * 清掉上次异常退出遗留的按需回源缓存。
     *
     * 这些文件在 [close] 里会删，但进程被强杀时没机会执行，
     * 不兜底就会永久躺在 cacheDir 里。
     */
    private fun sweepPlayCache() {
        val root = File(app.cacheDir, "play")
        if (!root.exists()) return
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.lastModified() < cutoff) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    /** 当前是否允许边下边播。 */
    fun isPlayable(task: DownloadTask): Boolean {
        if (task.status == TaskStatus.Completed) return !task.outputUri.isNullOrBlank()
        if (task.status == TaskStatus.Canceled) return false
        if (task.status == TaskStatus.Error) return false
        if (task.isLive) return false
        if (task.totalSegments <= 0) return false
        return task.doneSegments >= MIN_SEGMENTS || task.doneBytes >= MIN_BYTES
    }

    /**
     * 已完成任务的本地产物地址；未完成返回 null。
     *
     * 非空时播放页可以**完全跳过** [open]：不起本地代理、不碰网络、不查分片，
     * 直接把地址交给播放器。开播耗时从「起服务 + 拉播放列表」降到「打开一个文件」，
     * 这也是"下载完成后点播放应该秒开"的关键。
     */
    fun localFileUri(task: DownloadTask): String? =
        task.outputUri?.takeIf { it.isNotBlank() && task.status == TaskStatus.Completed }

    /** 不允许播放时给出原因，便于 UI 提示。 */
    fun blockReason(task: DownloadTask): String? {
        when {
            task.status == TaskStatus.Completed && task.outputUri.isNullOrBlank() ->
                return "输出文件不存在，可能已被删除"
            task.status == TaskStatus.Canceled -> return "任务已取消，临时分片已清理"
            task.status == TaskStatus.Error -> return "任务失败，无法播放"
            task.isLive -> return "直播流暂不支持边下边播"
            task.totalSegments <= 0 -> return "播放列表尚未解析完成"
            task.doneSegments < MIN_SEGMENTS && task.doneBytes < MIN_BYTES ->
                return "再下几个分片就能播了（已缓冲 ${task.doneSegments}/${task.totalSegments} 片）"
        }
        return null
    }

    /**
     * 打开一个可播放地址。
     *
     * @throws IllegalStateException 不满足播放条件
     * @throws java.io.IOException 播放列表回源失败
     */
    suspend fun open(task: DownloadTask): String = withContext(Dispatchers.IO) {
        // 已完成：直接播成品文件（本地单文件，不走代理也不回源）
        localFileUri(task)?.let { return@withContext it }

        val headers = HttpFactory.buildHeaders(
            task.url, settings, task.referer, task.userAgent, task.cookie
        )
        val (_, media, _) = source.resolveMediaPlaylist(task.url, headers, task.variantIndex)
        if (media.isLive) throw IllegalStateException("直播流暂不支持边下边播")
        if (media.segments.isEmpty()) throw IllegalStateException("播放列表里没有任何分片")

        val keys = source.fetchKeys(media.segments, headers)
        val token = UUID.randomUUID().toString().replace("-", "")
        val cacheDir = File(app.cacheDir, "play/${task.id}").apply { mkdirs() }

        val target = media.targetDuration.takeIf { it > 0 }
            ?: media.segments.maxOf { it.duration.toInt().coerceAtLeast(1) }.coerceAtLeast(1)

        val session = LocalPlayServer.Session(
            token = token,
            taskId = task.id,
            segments = media.segments,
            headers = headers,
            keys = keys,
            tmpDir = storage.taskTempDir(task.id),
            cacheDir = cacheDir,
            targetDuration = target
        )
        val url = server.register(session)
        tokens[task.id] = token
        url
    }

    /** 关闭播放：注销会话并清掉按需回源的缓存。 */
    fun close(taskId: String) {
        tokens.remove(taskId)?.let { server.unregister(it) }
        runCatching { File(app.cacheDir, "play/$taskId").deleteRecursively() }
        if (!server.hasSessions()) runCatching { server.stop() }
    }

}
