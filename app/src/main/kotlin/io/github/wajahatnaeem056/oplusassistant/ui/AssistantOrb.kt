package io.github.wajahatnaeem056.oplusassistant.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val OrbBlue = Color(0xFF3D7BFF)
private val OrbViolet = Color(0xFF9B5CFF)
private val OrbCyan = Color(0xFF35D0FF)
private val OrbPink = Color(0xFFFF5CC8)
private val OrbDeep = Color(0xFF05061A)
private val OrbCore = Color(0xFF1B1F5E)

/**
 * Small glowing voice orb: dark core, three soft colour blobs orbiting inside it, a breathing
 * scale, a slow vertical float and a rotating rim light.
 *
 * @param size diameter of the orb itself. The canvas is 1.6x larger so the outer glow is not cut.
 * @param level voice loudness 0..1. 0 is a calm idle, 1 makes the blobs spread and the orb swell.
 */
@Composable
fun AssistantOrb(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    level: Float = 0.35f,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3_400, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "drift",
    )
    // Smoothed so a jumpy loudness value still moves the orb fluidly.
    val amp by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "amp",
    )

    Canvas(modifier.size(size * 1.6f)) {
        val bob = (drift * 2f - 1f) * 5.dp.toPx()
        val c = Offset(this.size.width / 2f, this.size.height / 2f + bob)
        val r = size.toPx() / 2f * (1f + 0.05f * breathe + 0.12f * amp)

        // Outer glow.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    OrbViolet.copy(alpha = 0.50f + 0.20f * amp),
                    OrbBlue.copy(alpha = 0.16f),
                    Color.Transparent,
                ),
                center = c,
                radius = r * 1.55f,
            ),
            radius = r * 1.55f,
            center = c,
        )

        val orb = Path().apply { addOval(Rect(c, r)) }
        clipPath(orb) {
            // Dark core.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(OrbCore, OrbDeep),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )

            // Orbiting colour blobs, added together so overlaps glow.
            val blobs = listOf(
                Triple(OrbBlue, 1.0f, 0f),
                Triple(OrbViolet, -1.4f, 130f),
                Triple(if (amp > 0.5f) OrbPink else OrbCyan, 0.7f, 250f),
            )
            blobs.forEachIndexed { i, (color, speed, phase) ->
                val angle = ((spin * speed + phase) * PI / 180.0).toFloat()
                val dist = r * (0.30f + 0.14f * amp + 0.05f * i)
                val pos = Offset(c.x + cos(angle) * dist, c.y + sin(angle) * dist)
                val blobR = r * (0.62f + 0.10f * sin(breathe * PI.toFloat() + i))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.90f), Color.Transparent),
                        center = pos,
                        radius = blobR,
                    ),
                    radius = blobR,
                    center = pos,
                    blendMode = BlendMode.Plus,
                )
            }

            // Glassy highlight, top-left.
            val hl = Offset(c.x - r * 0.30f, c.y - r * 0.38f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                    center = hl,
                    radius = r * 0.65f,
                ),
                radius = r * 0.65f,
                center = hl,
            )
        }

        // Rotating rim light.
        rotate(degrees = spin * 1.5f, pivot = c) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        OrbCyan.copy(alpha = 0.75f),
                        OrbViolet.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                    center = c,
                ),
                radius = r,
                center = c,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}
