package com.flux.m3u8.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flux.m3u8.ui.theme.AccentGradient
import com.flux.m3u8.ui.theme.Danger
import com.flux.m3u8.ui.theme.Success
import com.flux.m3u8.ui.theme.Warning

/** 渐变进度条：比原生 LinearProgressIndicator 更有质感。 */
@Composable
fun GradientProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    tone: ProgressTone = ProgressTone.Accent,
    animate: Boolean = true
) {
    val target = progress.coerceIn(0f, 1f)
    val animated = if (animate) {
        val v by animateFloatAsState(
            targetValue = target,
            animationSpec = tween(300), label = "progress"
        )
        v
    } else {
        target
    }
    val colors = when (tone) {
        ProgressTone.Accent -> AccentGradient
        ProgressTone.Success -> listOf(Success, Success)
        ProgressTone.Warning -> listOf(Warning, Warning)
        ProgressTone.Danger -> listOf(Danger, Danger)
    }
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .background(Brush.horizontalGradient(colors))
            )
        }
    }
}

enum class ProgressTone { Accent, Success, Warning, Danger }

/** 统计卡片。 */
@Composable
fun StatCard(
    label: String,
    value: String,
    hint: String = "",
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.align(Alignment.BottomEnd)) { trailing() }
        }
    }
}

/** 速度曲线：把最近 N 次采样的速度画成折线。 */
@Composable
fun SpeedSparkline(
    samples: List<Long>,
    modifier: Modifier = Modifier,
    color: Color = AccentGradient.first()
) {
    if (samples.size < 2) {
        Box(modifier)
        return
    }
    val max = samples.maxOrNull()?.coerceAtLeast(1) ?: 1
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stepX = w / (samples.size - 1)
        val points = samples.mapIndexed { i, v ->
            Offset(i * stepX, h - (v.toFloat() / max) * (h - 4f) - 2f)
        }
        val stroke = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        // 填充
        val fill = Path().apply {
            addPath(stroke)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f))
            )
        )
        drawPath(
            stroke,
            Brush.horizontalGradient(AccentGradient),
            style = Stroke(width = 4f, pathEffect = PathEffect.cornerPathEffect(6f))
        )
    }
}

/** 空状态。 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.linearGradient(
                        AccentGradient.map { it.copy(alpha = 0.22f) }
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("↓", fontSize = 34.sp, color = AccentGradient.first())
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
