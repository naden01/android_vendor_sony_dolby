/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lunaris.dolby.audio.AudioVisualizer
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

// Visualizer bar spring: high stiffness + moderate damping gives a bouncy,
// slightly overshooting motion that settles quickly.
private const val BAR_STIFFNESS = 0.55f
private const val BAR_DAMPING = 0.60f

@Composable
fun AnimatedEqualizerIconDynamic(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 24.dp,
    barCount: Int = 5
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer_dynamic")
    
    val barHeights = List(barCount) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 800 + (index * 50),
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_height_$index"
        )
    }

    Canvas(modifier = modifier.size(size)) {
        val canvasWidth = this.size.width
        val canvasHeight = this.size.height
        
        val barWidths = List(barCount) { index ->
            val normalizedPosition = index.toFloat() / (barCount - 1)
            val centerOffset = (normalizedPosition - 0.5f) * 2
            val widthFactor = 1.0f - (centerOffset * centerOffset).pow(0.6f)
            val scaledWidth = 0.5f + (widthFactor * 0.5f)
            scaledWidth
        }
        
        val totalWidthFactor = barWidths.sum() + (barCount - 1) * 0.3f
        val baseBarWidth = canvasWidth / totalWidthFactor
        
        var currentX = 0f
        
        barHeights.forEachIndexed { index, heightAnimation ->
            val barWidth = baseBarWidth * barWidths[index]
            val barHeight = canvasHeight * heightAnimation.value
            val y = canvasHeight - barHeight

            drawRoundRect(
                color = color,
                topLeft = Offset(currentX, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
            
            currentX += barWidth + (baseBarWidth * 0.3f)
        }
    }
}

private fun gaussian(x: Float, center: Float, sigma: Float): Float =
    exp(-((x - center) * (x - center)) / (2f * sigma * sigma))

@Composable
fun AnimatedWaveformBanner(
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    accentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    barCount: Int = 56,
    animated: Boolean = true,
    liveAudio: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_banner")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveform_phase"
    )

    val swell by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveform_swell"
    )

    // One shared capture instance app-wide.
    val visualizer = remember(barCount, liveAudio) {
        if (liveAudio) AudioVisualizer.acquire(barCount) else null
    }
    DisposableEffect(visualizer) {
        onDispose { if (visualizer != null) AudioVisualizer.releaseShared() }
    }

    // Target band levels arrive at the capture rate (~20 Hz); interpolate them
    // on every display frame so the bars glide instead of stepping, and
    // crossfade between live levels and the idle animation.
    val targets = remember(barCount) { FloatArray(barCount) }
    val display = remember(barCount) { FloatArray(barCount) }
    val velocity = remember(barCount) { FloatArray(barCount) }
    var audioActive by remember { mutableStateOf(false) }
    var liveMix by remember { mutableFloatStateOf(0f) }
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(visualizer) {
        val v = visualizer ?: return@LaunchedEffect
        launch { v.levels.collect { it.copyInto(targets) } }
        launch { v.active.collect { audioActive = it } }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            liveMix += ((if (audioActive) 1f else 0f) - liveMix) * 0.15f
            for (i in display.indices) {
                val target = if (audioActive) targets[i] else 0f
                // Underdamped spring: snaps up, overshoots a touch, falls fast.
                val force = (target - display[i]) * BAR_STIFFNESS
                velocity[i] = (velocity[i] + force) * BAR_DAMPING
                display[i] = (display[i] + velocity[i]).coerceIn(0f, 1.08f)
            }
            frameTick++
        }
    }

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

        val centerY = canvasHeight / 2f
        val slotWidth = canvasWidth / barCount
        val barWidth = slotWidth * 0.42f
        val radius = barWidth / 2f
        val maxHalfHeight = centerY * 0.86f
        val mix = liveMix.coerceIn(0f, 1f)

        // Observe the frame counter so a redraw is requested every frame.
        frameTick.hashCode()

        for (index in 0 until barCount) {
            val position = (index + 0.5f) / barCount
            val centerX = position * canvasWidth

            val envelope = (
                gaussian(position, 0.34f, 0.13f) * 0.95f +
                gaussian(position, 0.62f, 0.11f) * 0.78f +
                gaussian(position, 0.48f, 0.05f) * 0.45f +
                gaussian(position, 0.82f, 0.09f) * 0.30f +
                gaussian(position, 0.14f, 0.10f) * 0.26f
            ).coerceIn(0f, 1f)

            val idleLevel = envelope *
                (if (animated) 0.72f + 0.28f * sin(phase + position * 14f) else 0.85f) *
                (if (animated) swell else 0.9f)

            // Live spectrum: bars follow the captured audio, blended with the
            // idle envelope during the crossfade so the silhouette stays.
            val liveLevel = display.getOrElse(index) { 0f }.coerceIn(0f, 1f)
            val level = (liveLevel * mix + idleLevel * (1f - mix)).coerceIn(0f, 1f)

            val halfHeight = maxHalfHeight * level

            val alpha = (0.28f + level * 0.72f).coerceIn(0f, 1f)
            val color = if (level > 0.55f) {
                lerpColor(barColor, accentColor, (level - 0.55f) / 0.45f)
            } else {
                barColor
            }

            if (halfHeight <= radius * 1.15f) {
                drawCircle(
                    color = color.copy(alpha = alpha * 0.65f),
                    radius = radius * 0.72f,
                    center = Offset(centerX, centerY)
                )
            } else {
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(centerX - radius, centerY - halfHeight),
                    size = Size(barWidth, halfHeight * 2f),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
        }
    }
}

private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t
    )
}
