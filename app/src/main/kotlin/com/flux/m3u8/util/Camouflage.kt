package com.flux.m3u8.util

/**
 * 「防相册识别」：给视频文件换上系统相册无法识别的后缀，
 * 避免下载的内容出现在系统相册 / 视频列表里造成尴尬。
 *
 * 做法是**在原有文件名后追加隐藏后缀**（如 `foo.mp4` → `foo.mp4.flux`）：
 *  · 伪装 / 还原互逆，原始扩展名信息保留在文件名里，无需额外状态；
 *  · 隐藏后缀不在系统 MIME 映射表中，MediaStore 不会把它收录进相册。
 */
object Camouflage {

    /** 隐藏后缀（不在系统 MIME 映射表中，相册扫描不会收录）。 */
    const val EXT = ".flux"

    /** 该文件名当前是否处于伪装状态。 */
    fun isHidden(name: String?): Boolean = name?.endsWith(EXT) == true

    /** 伪装：在文件名后追加隐藏后缀。 */
    fun hideName(name: String): String = name + EXT

    /** 还原：去掉隐藏后缀，恢复为可识别的视频文件。 */
    fun restoreName(name: String): String = name.removeSuffix(EXT)
}
