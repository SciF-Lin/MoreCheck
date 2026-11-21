package com.morecheck.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(val angle: Float, val speed: Float, val color: Color, val size: Float)

/**
 * A lightweight confetti burst. Re-triggers whenever [triggerKey] changes to a
 * positive value; 0 keeps it hidden.
 */
@Composable
fun ConfettiEffect(triggerKey: Int, modifier: Modifier = Modifier) {
    if (triggerKey <= 0) return

    val progress = remember { Animatable(0f) }
    val particles = remember {
        val random = Random(42)
        val colors = listOf(
            Color(0xFFF44336), Color(0xFFFF9800), Color(0xFFFFEB3B),
            Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFF9C27B0)
        )
        List(28) {
            Particle(
                angle = random.nextFloat() * 2f * PI.toFloat(),
                speed = 0.6f + random.nextFloat() * 0.6f,
                color = colors[random.nextInt(colors.size)],
                size = 3f + random.nextFloat() * 4f
            )
        }
    }

    LaunchedEffect(triggerKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
    }

    val p = progress.value
    if (p <= 0f || p >= 1f) return

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.38f
        val maxRadius = size.minDimension * 0.5f
        particles.forEach { particle ->
            val r = maxRadius * (0.15f + 0.85f * p) * particle.speed
            val x = cx + cos(particle.angle) * r
            val y = cy + sin(particle.angle) * r - p * maxRadius * 0.3f
            drawCircle(particle.color, radius = particle.size, center = Offset(x, y), alpha = 1f - p)
        }
    }
}
