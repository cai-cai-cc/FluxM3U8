package com.flux.m3u8.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 应用设置。
 *
 * 用 SharedPreferences 而非 DataStore：零额外依赖，同步读取，
 * 下载服务里读取设置时不用担心协程与线程切换。
 */
class Settings(context: Context) {

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val PREF = "flux_settings"

        private const val K_SAVE_DIR = "save_dir"          // "internal" 或 SAF 树 URI
        private const val K_CONNECTIONS = "connections"
        private const val K_MAX_RUNNING = "max_running"
        private const val K_CHUNK_THREADS = "chunk_threads"
        private const val K_CHUNK_THRESHOLD = "chunk_threshold"
        private const val K_AUTO_CONCURRENCY = "auto_concurrency"
        private const val K_SPEED_LIMIT = "speed_limit"    // KB/s，0 不限
        private const val K_RETRY = "retry"
        private const val K_RETRY_DELAY = "retry_delay"
        private const val K_TIMEOUT = "timeout"
        private const val K_TO_MP4 = "to_mp4"
        private const val K_CAMOUFLAGE = "camouflage"
        private const val K_KEEP_SEGMENTS = "keep_segments"
        private const val K_PROXY_HOST = "proxy_host"
        private const val K_PROXY_PORT = "proxy_port"
        private const val K_UA = "ua"
        private const val K_REFERER = "referer"
        private const val K_WAKE_LOCK = "wake_lock"
        private const val K_ONLY_WIFI = "only_wifi"
        private const val K_AUTO_RESUME = "auto_resume"
        private const val K_THEME_LIGHT = "theme_light"
        private const val K_DYNAMIC_COLOR = "dynamic_color"
        private const val K_INSECURE_TLS = "insecure_tls"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var saveDir: String
        get() = prefs.getString(K_SAVE_DIR, "internal") ?: "internal"
        set(v) = prefs.edit { putString(K_SAVE_DIR, v) }

    /**
     * 单任务并发连接数：同一时间同时下载多少个分片。
     * 这是影响速度最主要的参数，分片多时调大能显著提速。
     */
    var connections: Int
        get() = prefs.getInt(K_CONNECTIONS, 16)
        set(v) = prefs.edit { putInt(K_CONNECTIONS, v.coerceIn(1, 64)) }

    /** 同时下载的任务数。 */
    var maxRunning: Int
        get() = prefs.getInt(K_MAX_RUNNING, 2)
        set(v) = prefs.edit { putInt(K_MAX_RUNNING, v.coerceIn(1, 10)) }

    /**
     * 单分片分块线程数：把一个大分片切成几段并行拉取。
     * 分片少而大（比如 10 片 × 50MB）时，光靠分片并发跑不满带宽，
     * 必须靠这个才能压满。设为 1 表示不分块。
     */
    var chunkThreads: Int
        get() = prefs.getInt(K_CHUNK_THREADS, 4)
        set(v) = prefs.edit { putInt(K_CHUNK_THREADS, v.coerceIn(1, 16)) }

    /** 分片大小超过此值（KB）才启用分块，避免小分片因分块反而变慢。 */
    var chunkThresholdKb: Int
        get() = prefs.getInt(K_CHUNK_THRESHOLD, 2048)
        set(v) = prefs.edit { putInt(K_CHUNK_THRESHOLD, v.coerceIn(256, 102400)) }

    /** 自动并发：根据实测速度动态增减连接数，跑满即止。 */
    var autoConcurrency: Boolean
        get() = prefs.getBoolean(K_AUTO_CONCURRENCY, false)
        set(v) = prefs.edit { putBoolean(K_AUTO_CONCURRENCY, v) }

    /** 全局限速，单位 KB/s，0 表示不限速。 */
    var speedLimitKbps: Int
        get() = prefs.getInt(K_SPEED_LIMIT, 0)
        set(v) = prefs.edit { putInt(K_SPEED_LIMIT, v.coerceAtLeast(0)) }

    var retryTimes: Int
        get() = prefs.getInt(K_RETRY, 3)
        set(v) = prefs.edit { putInt(K_RETRY, v.coerceIn(0, 10)) }

    /**
     * 自动重试间隔（秒）。
     *
     * 分片下载失败后等这么久再重试，并且**按失败次数线性递增**
     * （第 n 次失败等 `n × 间隔`），给源站留出恢复时间。
     * 设为 0 表示失败后立即重试——只在确定是偶发抖动时才建议这么做，
     * 否则容易把已经限流的源站打得更死。
     */
    var retryDelaySeconds: Int
        get() = prefs.getInt(K_RETRY_DELAY, 1)
        set(v) = prefs.edit { putInt(K_RETRY_DELAY, v.coerceIn(0, 60)) }

    var timeoutSeconds: Int
        get() = prefs.getInt(K_TIMEOUT, 30)
        set(v) = prefs.edit { putInt(K_TIMEOUT, v.coerceIn(5, 120)) }

    var toMp4: Boolean
        get() = prefs.getBoolean(K_TO_MP4, false)
        set(v) = prefs.edit { putBoolean(K_TO_MP4, v) }

    /**
     * 防相册识别：开启后已完成任务可把视频文件后缀改为隐藏后缀，
     * 避免下载内容被系统相册收录。
     */
    var camouflage: Boolean
        get() = prefs.getBoolean(K_CAMOUFLAGE, false)
        set(v) = prefs.edit { putBoolean(K_CAMOUFLAGE, v) }

    var keepSegments: Boolean
        get() = prefs.getBoolean(K_KEEP_SEGMENTS, false)
        set(v) = prefs.edit { putBoolean(K_KEEP_SEGMENTS, v) }

    var proxyHost: String
        get() = prefs.getString(K_PROXY_HOST, "") ?: ""
        set(v) = prefs.edit { putString(K_PROXY_HOST, v.trim()) }

    var proxyPort: Int
        get() = prefs.getInt(K_PROXY_PORT, 0)
        set(v) = prefs.edit { putInt(K_PROXY_PORT, v) }

    var userAgent: String
        get() = prefs.getString(K_UA, DEFAULT_UA) ?: DEFAULT_UA
        set(v) = prefs.edit { putString(K_UA, v) }

    var referer: String
        get() = prefs.getString(K_REFERER, "") ?: ""
        set(v) = prefs.edit { putString(K_REFERER, v.trim()) }

    /** 下载时持有唤醒锁，保证熄屏后不掉速（默认开启）。 */
    var wakeLock: Boolean
        get() = prefs.getBoolean(K_WAKE_LOCK, true)
        set(v) = prefs.edit { putBoolean(K_WAKE_LOCK, v) }

    var onlyWifi: Boolean
        get() = prefs.getBoolean(K_ONLY_WIFI, false)
        set(v) = prefs.edit { putBoolean(K_ONLY_WIFI, v) }

    var autoResume: Boolean
        get() = prefs.getBoolean(K_AUTO_RESUME, true)
        set(v) = prefs.edit { putBoolean(K_AUTO_RESUME, v) }

    var themeLight: Boolean
        get() = prefs.getBoolean(K_THEME_LIGHT, false)
        set(v) = prefs.edit { putBoolean(K_THEME_LIGHT, v) }

    /** Android 12+ 动态取色（Material You）。 */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(K_DYNAMIC_COLOR, true)
        set(v) = prefs.edit { putBoolean(K_DYNAMIC_COLOR, v) }

    /**
     * 跳过 TLS 证书校验（信任全部证书 + 关闭主机名校验）。
     *
     * ⚠️ 打开后等同于对中间人攻击不设防：同一网络下的攻击者可以伪造证书
     * 窃听/篡改下载内容。**默认关闭**，只有确认源站证书链确实有问题时才临时打开。
     */
    var insecureTls: Boolean
        get() = prefs.getBoolean(K_INSECURE_TLS, false)
        set(v) = prefs.edit { putBoolean(K_INSECURE_TLS, v) }
}
