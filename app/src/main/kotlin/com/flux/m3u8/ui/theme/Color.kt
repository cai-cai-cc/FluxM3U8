package com.flux.m3u8.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 品牌色：靛蓝 → 青，与桌面版一致。
 * 深色下把主色调亮一档，避免在高饱和背景上看不清。
 */
val Indigo = Color(0xFF6D7CFF)
val IndigoLight = Color(0xFF8B98FF)
val Cyan = Color(0xFF22D3EE)
val AccentGradient = listOf(Indigo, Cyan)

// 深色主题表面层级（从底到顶）
val BgDark = Color(0xFF0A0B10)
val SurfaceDark = Color(0xFF12141C)
val SurfaceDarkHigh = Color(0xFF1A1D28)

val Success = Color(0xFF34D399)
val Warning = Color(0xFFFBBF24)
val Danger = Color(0xFFF87171)
