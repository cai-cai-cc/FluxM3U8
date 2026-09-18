package com.flux.m3u8.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.flux.m3u8.download.DownloadManager
import com.flux.m3u8.ui.screens.AppRoot

class MainActivity : ComponentActivity() {

    /** 从浏览器/其他应用分享进来的 m3u8 链接。 */
    private var sharedUrl by mutableStateOf<String?>(null)
    /** 分享文本里提取出的标题（可能为空，如只分享了一串链接）。 */
    private var sharedTitle by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        DownloadManager.init(this)
        handleIncomingIntent(intent)

        setContent {
            AppRoot(
                sharedUrl = sharedUrl,
                sharedTitle = sharedTitle,
                onSharedHandled = { sharedUrl = null; sharedTitle = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 让 getIntent() 反映最新一条 Intent，避免拿到旧链接
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 回到前台：解除"已退出"标记，并在仍有未完成任务时恢复前台服务/通知
        DownloadManager.onAppForeground()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim()
        // 分享文本可能带标题，例如「视频标题\nhttps://…m3u8」或「标题 https://…」。
        // 取文本里第一个 http(s) 链接当下载地址，其余部分当标题（没有则留空）。
        val url = Regex("""https?://\S+""")
            .find(raw.orEmpty())?.value?.trimEnd('.', ',', '，', '。')
        if (url != null) {
            sharedUrl = url
            sharedTitle = raw?.replace(url, "")
                ?.lineSequence()?.firstOrNull { it.isNotBlank() }
                ?.trim()?.trimEnd('.', ',', '，', '。')
            // 消费掉这条分享 Intent：播放器进入/退出全屏会切换横竖屏，
            // 虽然已在 Manifest 声明 configChanges 避免重建，但进程被回收后
            // 从最近任务重新打开仍可能让 onCreate 再拿到同一条 Intent，
            // 不清空就会在播放/退出播放时反复弹出「新建任务」。
            intent?.action = null
            intent?.removeExtra(Intent.EXTRA_TEXT)
            intent?.data = null
        }
    }
}
