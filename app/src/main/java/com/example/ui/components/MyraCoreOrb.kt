package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.MyraCyanBright
import com.example.ui.theme.MyraCyanPrimary
import com.example.ui.theme.MyraVioletGlow
import com.example.ui.theme.MyraVioletSecondary
import kotlin.math.sin

enum class MyraCoreMode {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

@Composable
fun MyraCoreOrb(
    mode: MyraCoreMode,
    rmsLevel: Float = 0f,
    size: Dp = 160.dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "myra_core")

    val rotationFast by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mode) {
                    MyraCoreMode.THINKING -> 2200
                    MyraCoreMode.LISTENING -> 3200
                    MyraCoreMode.SPEAKING -> 3800
                    MyraCoreMode.IDLE -> 8000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_fast"
    )

    val rotationReverse by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (mode == MyraCoreMode.IDLE) 12000 else 5000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_reverse"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (mode == MyraCoreMode.IDLE) 2400 else 950,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val voiceBoost by animateFloatAsState(
        targetValue = if (mode == MyraCoreMode.LISTENING) rmsLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = 300f),
        label = "voice_boost"
    )

    val primaryRingColor = when (mode) {
        MyraCoreMode.LISTENING -> MyraCyanBright
        MyraCoreMode.THINKING -> MyraVioletGlow
        MyraCoreMode.SPEAKING -> Color(0xFF2DD4BF)
        MyraCoreMode.IDLE -> MyraCyanPrimary
    }

    val secondaryRingColor = when (mode) {
        MyraCoreMode.LISTENING -> MyraVioletGlow
        MyraCoreMode.THINKING -> MyraCyanBright
        MyraCoreMode.SPEAKING -> MyraCyanPrimary
        MyraCoreMode.IDLE -> MyraVioletSecondary
    }

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension / 2f

            // Ambient radial glow
            val glowRadius = maxRadius * (0.85f + voiceBoost * 0.15f) * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryRingColor.copy(alpha = 0.28f + voiceBoost * 0.25f),
                        secondaryRingColor.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius.coerceAtLeast(1f)
                ),
                radius = glowRadius.coerceAtLeast(1f),
                center = center
            )

            // Outer segmented orbital ring
            val outerStroke = (maxRadius * 0.045f).coerceAtLeast(2.dp.toPx())
            val outerRadius = maxRadius * 0.88f
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(primaryRingColor, secondaryRingColor, Color.Transparent, primaryRingColor)
                ),
                startAngle = rotationFast,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                size = androidx.compose.ui.geometry.Size(outerRadius * 2f, outerRadius * 2f),
                style = Stroke(width = outerStroke, cap = StrokeCap.Round)
            )

            // Middle counter-rotating arc
            val midRadius = maxRadius * (0.74f + voiceBoost * 0.05f)
            drawArc(
                color = secondaryRingColor.copy(alpha = 0.85f),
                startAngle = rotationReverse,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(center.x - midRadius, center.y - midRadius),
                size = androidx.compose.ui.geometry.Size(midRadius * 2f, midRadius * 2f),
                style = Stroke(width = outerStroke * 0.8f, cap = StrokeCap.Round)
            )
            drawArc(
                color = primaryRingColor.copy(alpha = 0.65f),
                startAngle = rotationReverse + 180f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - midRadius, center.y - midRadius),
                size = androidx.compose.ui.geometry.Size(midRadius * 2f, midRadius * 2f),
                style = Stroke(width = outerStroke * 0.8f, cap = StrokeCap.Round)
            )
        }

        // Center holographic MYRA emblem image
        val coreSize = size * 0.56f
        Box(
            modifier = Modifier
                .size(coreSize)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(listOf(primaryRingColor, secondaryRingColor)),
                    shape = CircleShape
                )
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_myra_core),
                contentDescription = "Jarvis AI Core Emblem",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun VoiceWaveformStrip(
    isActive: Boolean,
    rmsLevel: Float,
    label: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_strip")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.medium
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val barCount = 12
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until barCount) {
                val waveVal = if (isActive) {
                    val sinVal = (sin(phase + i * 0.7f) + 1f) * 0.5f
                    (0.25f + (0.4f + rmsLevel * 0.6f) * sinVal).coerceIn(0.2f, 1f)
                } else {
                    0.22f
                }
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height((22f * waveVal).dp)
                        .clip(CircleShape)
                        .background(
                            if (i % 2 == 0) MyraCyanBright else MyraVioletGlow
                        )
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
