package com.flux.m3u8.util

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/** 字节数格式化。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val value = bytes / 1024.0.pow(exp.toDouble())
    return if (exp == 0) "$bytes B" else String.format(Locale.getDefault(), "%.1f %s", value, units[exp])
}

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

/** 秒 → "1:23:45" 或 "12:34"。 */
fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%d:%02d", m, s)
}

/** 秒 → "3 分 20 秒"，用于展示视频总时长。 */
fun formatDurationCn(seconds: Long): String = when {
    seconds <= 0 -> "未知"
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
}

fun hostOf(url: String): String = try {
    java.net.URL(url).host
} catch (e: Exception) {
    url.take(32)
}

/** 文件名安全化：去掉安卓不支持的字符。 */
fun safeFileName(name: String, fallback: String = "video"): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
    return cleaned.ifBlank { fallback }.take(100)
}
