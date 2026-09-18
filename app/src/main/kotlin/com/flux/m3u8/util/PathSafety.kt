package com.flux.m3u8.util

/**
 * 目录/文件名安全化。
 *
 * 用户可以在设置里填自定义子目录名，这段字符串会被拼进文件路径。
 * 不过滤的话 `../../sdcard/xxx` 这类输入就能把文件写到任意位置（路径穿越）。
 *
 * 规则：
 *  · 统一分隔符，`\` 视作 `/`
 *  · 丢掉空段、`.`、`..`
 *  · 每段去掉文件名非法字符
 *  · 结果保证是相对路径（不以 `/` 开头）
 */
fun safeRelativePath(input: String): String {
    if (input.isBlank()) return ""
    return input
        .replace('\\', '/')
        .split('/')
        .map { seg ->
            seg.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
        }
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("/")
}

/** 判断一个相对路径是否安全（不含穿越、不是绝对路径）。 */
fun isSafeRelative(input: String): Boolean =
    input == safeRelativePath(input)
