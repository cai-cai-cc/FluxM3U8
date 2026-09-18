package com.flux.m3u8.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.flux.m3u8.R
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.TaskStatus
import com.flux.m3u8.ui.MainActivity
import com.flux.m3u8.util.formatSpeed
import kotlin.math.roundToInt

/**
 * 通知管理。
 *
 * 常驻通知不只是"显示进度"——在安卓上，**没有前台通知就不允许后台长跑**，
 * 它是保活的必要凭证。所以这里的内容必须持续更新，否则系统可能判定为僵死服务。
 */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "flux_download"
        const val CHANNEL_DONE = "flux_done"
        const val NOTIF_ID = 1001
        private const val DONE_ID_BASE = 2000

        const val ACTION_PAUSE = "com.flux.m3u8.action.PAUSE_ALL"
        const val ACTION_RESUME = "com.flux.m3u8.action.RESUME_ALL"
        const val ACTION_STOP = "com.flux.m3u8.action.STOP_ALL"
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var doneCounter = 0

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloading = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            val done = NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.channel_done_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_done_desc) }
            nm.createNotificationChannel(downloading)
            nm.createNotificationChannel(done)
        }
    }

    /** 构建常驻通知。 */
    fun build(tasks: List<DownloadTask>, speed: Long): android.app.Notification {
        val active = tasks.filter { it.status.isActive }
        val paused = tasks.count { it.status == TaskStatus.Paused }
        val allPaused = active.isEmpty() && paused > 0

        val title = when {
            active.isNotEmpty() -> "正在下载 ${active.size} 个任务"
            allPaused -> "已暂停（$paused 个任务）"
            else -> "后台下载服务运行中"
        }

        val content = when {
            active.isNotEmpty() -> {
                val first = active.first()
                val pct = (first.progress * 100).roundToInt()
                "${first.name} · $pct% · ${formatSpeed(speed)}"
            }
            allPaused -> "点击“全部开始”恢复下载"
            else -> "没有进行中的任务"
        }

        val bigText = buildString {
            active.take(5).forEach { t ->
                val pct = (t.progress * 100).roundToInt()
                append("• ${t.name}  $pct%")
                if (t.speedBps > 0) append("  ${formatSpeed(t.speedBps)}")
                append('\n')
            }
            if (active.size > 5) append("…还有 ${active.size - 5} 个")
            if (paused > 0) append("\n已暂停：$paused 个")
        }.trim()

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openIntent)
            .setOngoing(true)          // 常驻，不可手动滑掉
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (active.isNotEmpty()) {
            val total = active.size
            val pct = (active.map { it.progress }.average() * 100).roundToInt()
            builder.setProgress(100, pct, false)
            builder.addAction(android.R.drawable.ic_media_pause, "全部暂停", actionIntent(ACTION_PAUSE))
        } else if (allPaused) {
            builder.addAction(android.R.drawable.ic_media_play, "全部开始", actionIntent(ACTION_RESUME))
        }
        if (active.isNotEmpty() || allPaused) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "全部停止", actionIntent(ACTION_STOP))
        }
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).apply { this.action = action }
        val code = action.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, code, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getService(context, code, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    /** 只撤掉常驻的进度通知，保留"完成/失败"的一次性通知。 */
    fun cancelOngoing() {
        runCatching { nm.cancel(NOTIF_ID) }
    }

    /** 撤掉本应用的全部通知（用户完全退出应用时用）。 */
    fun cancelAll() {
        runCatching { nm.cancelAll() }
    }

    /** 任务完成/失败提醒。 */
    fun notifyDone(task: DownloadTask) {
        val success = task.status == TaskStatus.Completed
        val notif = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(if (success) R.drawable.ic_stat_done else R.drawable.ic_stat_download)
            .setContentTitle(if (success) "${task.name} 下载完成" else "${task.name} 下载失败")
            .setContentText(if (success) task.outputName ?: "已保存到下载目录" else task.error ?: "未知错误")
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, task.id.hashCode(),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        nm.notify(DONE_ID_BASE + (doneCounter++ % 50), notif)
    }
}
