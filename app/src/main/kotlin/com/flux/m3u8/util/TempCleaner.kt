package com.flux.m3u8.util

import android.content.Context
import java.io.File

/**
 * 临时文件（分片缓存）管理器。
 *
 * 临时目录位于 `cacheDir/seg/<任务ID>/`，存放未合并的 .ts 分片与 .part 半成品。
 * 正常流程下任务完成/取消时会自动删除，但总有删不掉的情况：
 *
 *  1. 进程被系统强杀、应用崩溃 → 清理代码根本没机会执行
 *  2. 任务失败后用户既没重试也没移除 → 分片一直堆着
 *  3. 手机重启后数据库里已无此任务 → 成了永远没人认领的孤儿目录
 *
 * 这个类的职责就是兜住这些漏网之鱼：启动时扫一遍、设置页可手动清。
 */
object TempCleaner {

    /** 孤儿目录保留时长：超过这个时间没被改动就认为已废弃。 */
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

    /** 临时文件根目录。 */
    fun root(context: Context): File = File(context.cacheDir, "seg")

    /** 某个任务的临时目录。 */
    fun taskDir(context: Context, taskId: String): File = File(root(context), taskId)

    /** 清理单个任务的临时文件。 */
    fun clearTask(context: Context, taskId: String) {
        runCatching { taskDir(context, taskId).deleteRecursively() }
    }

    /** 清空全部临时文件（设置页「立即清理」用）。 */
    fun clearAll(context: Context) {
        runCatching { root(context).deleteRecursively() }
    }

    /** 当前临时文件占用字节数。 */
    fun sizeOf(context: Context): Long = dirSize(root(context))

    /** 清理结果统计。 */
    data class SweepResult(
        val removedDirs: Int = 0,
        val removedParts: Int = 0,
        val freedBytes: Long = 0
    )

    /**
     * 扫描并清理残留。
     *
     * 三条清理规则：
     *  · 目录名不在 [knownIds] 里 → 数据库已无此任务，是孤儿，删
     *  · 目录超过 7 天没改动 → 多半是废弃任务，删
     *  · 所有 .part 文件 → 半截的下载半成品，一定没用，删
     *
     * 注意：属于已知任务且近期有改动的目录**一律保留**，
     * 否则会把正在下载或暂停待续传的分片误删。
     *
     * @param knownIds 数据库中仍然存在的所有任务 ID
     * @param finishedIds 已完成/已取消的任务 ID，这些任务的分片可以直接删
     */
    fun sweep(
        context: Context,
        knownIds: Set<String>,
        finishedIds: Set<String> = emptySet()
    ): SweepResult {
        val base = root(context)
        if (!base.exists()) return SweepResult()

        var dirs = 0
        var parts = 0
        var freed = 0L
        val now = System.currentTimeMillis()

        base.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) {
                val sz = dir.length()
                if (dir.delete()) { freed += sz; parts++ }
                return@forEach
            }

            val id = dir.name
            val isOrphan = id !in knownIds
            val isStale = now - dir.lastModified() > MAX_AGE_MS
            val isFinished = id in finishedIds

            when {
                isOrphan || isStale || isFinished -> {
                    val sz = dirSize(dir)
                    if (dir.deleteRecursively()) { freed += sz; dirs++ }
                }
                // 任务仍在进行/暂停：只清掉 .part 半成品，保留已完成的 .ts 分片
                else -> {
                    dir.listFiles()?.forEach { f ->
                        if (f.name.endsWith(".part")) {
                            val sz = f.length()
                            if (f.delete()) { freed += sz; parts++ }
                        }
                    }
                }
            }
        }

        return SweepResult(dirs, parts, freed)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (!dir.isDirectory) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { total += dirSize(it) }
        return total
    }
}
