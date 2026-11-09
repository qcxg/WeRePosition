package com.shiraka.wereposition.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/** The same sampled blur and Material tint used by the floating navigation bar. */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassOverlaySurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    onClick: (() -> Unit)? = null,
    strongTint: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .hazeEffect(state = hazeState) {
                blurRadius = 18.dp
                noiseFactor = 0.03f
                inputScale = HazeInputScale.Fixed(0.5f)
            }
            .background(Brush.verticalGradient(listOf(
                scheme.surface.copy(alpha = if (strongTint) 0.88f else 0.38f),
                scheme.surface.copy(alpha = if (strongTint) 0.80f else 0.24f)
            )))
            .border(0.7.dp, scheme.outlineVariant.copy(alpha = 0.20f), shape),
        content = content
    )
}
