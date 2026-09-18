package com.flux.m3u8.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自动恢复未完成任务。
 *
 * 只有"设置 → 启动后自动继续未完成任务"打开时才生效。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // goAsync() 告诉系统"我还没处理完"，避免 onReceive 返回后进程被回收
        val pending = goAsync()
        DownloadManager.init(context)
        // 交给 DownloadManager 自己的作用域执行，不再用 GlobalScope
        DownloadManager.restoreUnfinishedAsync(delayMs = 3000) { pending.finish() }
    }
}
