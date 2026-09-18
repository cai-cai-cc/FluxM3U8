package com.flux.m3u8.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.flux.m3u8.util.NetworkUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 前台下载服务 —— 后台保活的载体。
 *
 * 安卓上想让下载在退出界面、锁屏之后继续跑，必须做到三件事，本服务全部做了：
 *  1. **前台服务**：调用 startForeground 挂一条常驻通知，系统不会轻易回收
 *  2. **CPU 唤醒锁**：屏幕熄灭后 CPU 继续工作，这是"锁屏不掉速"的关键
 *  3. **WiFi 锁**：防止系统为省电在熄屏后断开 WiFi
 *
 * 另外 manifest 里声明了 `android:stopWithTask="false"`，
 * 从最近任务列表划掉应用时服务不会被一起杀掉。
 */
class DownloadService : Service() {

    private lateinit var notifier: Notifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastStatus: Map<String, String> = emptyMap()

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
        notifier = Notifier(this)
        notifier.createChannels()
        // 没有任何需要保活的任务（活跃/暂停）时不要挂常驻通知——
        // 被系统重启的 START_STICKY 场景下，否则会凭空冒出一条通知。
        if (DownloadManager.hasWork()) {
            startAsForeground()
        } else {
            notifier.cancelOngoing()
            stopSelf()
        }
        observeTasks()
        observeCompletion()
        observeNetwork()
        applyNetworkPolicy()
    }

    private fun startAsForeground() {
        val notif = notifier.build(DownloadManager.tasks.value, DownloadManager.totalSpeed())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, Notifier.NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ServiceCompat.startForeground(this, Notifier.NOTIF_ID, notif, 0)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限时可能抛异常，忽略即可继续
        }
    }

    /** 任务状态变化时刷新通知、按需开关锁。 */
    private fun observeTasks() {
        scope.launch {
            DownloadManager.tasks
                .map { list -> list.map { it.id to it.status.name } }
                .distinctUntilChanged()
                .collect {
                    if (!DownloadManager.hasWork()) {
                        // 全部收工：撤掉常驻通知并结束服务，避免通知栏残留
                        notifier.cancelOngoing()
                        stopSelf()
                        return@collect
                    }
                    notifier.build(DownloadManager.tasks.value, DownloadManager.totalSpeed()).let {
                        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                            .notify(Notifier.NOTIF_ID, it)
                    }
                    updateLocks()
                }
        }

        // 速度变化也刷新（1 秒一次），让通知里的速度是活的
        scope.launch {
            while (isActive) {
                delay(1000)
                if (DownloadManager.tasks.value.any { it.status.isActive }) {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .notify(Notifier.NOTIF_ID,
                            notifier.build(DownloadManager.tasks.value, DownloadManager.totalSpeed()))
                }
            }
        }
    }

    /** 完成/失败时弹一次性通知。 */
    private fun observeCompletion() {
        scope.launch {
            DownloadManager.tasks.collect { list ->
                val current = list.associate { it.id to it.status.name }
                if (lastStatus.isNotEmpty()) {
                    list.forEach { task ->
                        val before = lastStatus[task.id]
                        val now = task.status.name
                        if (before != null && before != now &&
                            (task.status == com.flux.m3u8.model.TaskStatus.Completed ||
                                    task.status == com.flux.m3u8.model.TaskStatus.Error)
                        ) {
                            notifier.notifyDone(task)
                        }
                    }
                }
                lastStatus = current
            }
        }
    }

    private fun updateLocks() {
        val hasActive = DownloadManager.tasks.value.any { it.status.isActive }
        if (hasActive) acquireLocks() else releaseLocks()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (!DownloadManager.settings().wakeLock) return
        if (wakeLock?.isHeld != true) {
            // 不能用带超时的 acquire(ms)：超时候锁会被自动释放，
            // 长任务下载到一半就失去保活，"锁屏不掉速"直接失效。
            // 这里改为无限期持有，由 releaseLocks() 显式释放。
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FluxM3U8:Download")
                .apply { acquire() }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FluxM3U8:Download")
                ?.apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Notifier.ACTION_PAUSE -> DownloadManager.pauseAll()
            Notifier.ACTION_RESUME -> DownloadManager.resumeAll()
            Notifier.ACTION_STOP -> {
                DownloadManager.cancelAll()
                notifier.cancelOngoing()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 被系统以 START_STICKY 重启（intent 为 null）时，若没有事情可做就直接结束，
        // 不要凭空挂一条常驻通知。
        if (intent == null && !DownloadManager.hasWork()) {
            notifier.cancelOngoing()
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        return START_STICKY   // 被系统杀掉后尽量重启，恢复下载
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 从最近任务划掉时：
        //  · 还有活跃下载 → 保持前台继续跑
        //  · 只剩暂停任务/无事可做 → 直接结束服务并清通知，避免通知栏残留
        if (DownloadManager.hasActive()) {
            startAsForeground()
        } else {
            notifier.cancelAll()
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseLocks()
        netCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        netCallback = null
        scope.cancel()
        super.onDestroy()
    }

    // ──────────────── 网络监听（仅 Wi-Fi） ────────────────

    /** 注册网络回调，网络切换时按「仅 Wi-Fi」开关自动暂停/恢复。 */
    private fun observeNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = applyNetworkPolicy()
            override fun onLost(network: Network) = applyNetworkPolicy()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                applyNetworkPolicy()
        }
        netCallback = cb
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb) }
    }

    private fun applyNetworkPolicy() {
        runCatching { DownloadManager.onNetworkPolicy(NetworkUtil.isOnWifi(this)) }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 后台启动限制：忽略，等用户回到前台会自动补上
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
