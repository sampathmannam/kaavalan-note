package com.kaavalan.note.features.capture

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaavalan.note.R

/**
 * An explicit, accessible voice state. The breathing mic is the only repeating motion
 * in capture: it answers "is the microphone live?" without competing with note entry.
 */
@Composable
internal fun VoiceCaptureIndicator(
    phase: VoiceCapturePhase,
    modifier: Modifier = Modifier,
) {
    if (!phase.isActive) return

    // This follows Android's Remove animations system setting. A static state label is
    // still fully informative when motion is disabled.
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val transition = rememberInfiniteTransition(label = "voice_capture_indicator")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motionEnabled && phase == VoiceCapturePhase.LISTENING) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice_listening_pulse",
    )
    val (title, supportingText) = when (phase) {
        VoiceCapturePhase.STARTING -> stringResource(R.string.voice_capture_starting) to
            stringResource(R.string.voice_capture_starting_support)
        VoiceCapturePhase.LISTENING -> stringResource(R.string.voice_capture_listening) to
            stringResource(R.string.voice_capture_listening_support)
        VoiceCapturePhase.FINISHING -> stringResource(R.string.voice_capture_finishing) to
            stringResource(R.string.voice_capture_finishing_support)
        VoiceCapturePhase.IDLE -> return
    }
    val listening = phase == VoiceCapturePhase.LISTENING
    val containerColor = if (listening) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (listening) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val activeColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_capture_status")
            .semantics {
                contentDescription = "$title. $supportingText"
                liveRegion = LiveRegionMode.Polite
            },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    if (listening) {
                        val radius = size.minDimension * (0.32f + pulse * 0.15f)
                        drawCircle(
                            color = activeColor.copy(alpha = 0.18f - pulse * 0.08f),
                            radius = radius,
                        )
                        drawCircle(
                            color = activeColor.copy(alpha = 0.52f - pulse * 0.24f),
                            radius = radius,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = activeColor,
                    modifier = Modifier.size(24.dp),
                )
                if (motionEnabled && !listening) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(3.dp),
                        color = activeColor,
                        strokeWidth = 2.dp,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.82f),
                )
            }
        }
    }
}
