package com.shiraka.wereposition.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.max

data class GlassNavigationItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * Content-sized floating navigation: one outer capsule, one moving indicator, selected icon + label, and
 * unselected labels. When translated labels cannot fit, every destination falls
 * back to an icon so the bar never clips later items.
 */
@Composable
@OptIn(ExperimentalHazeApi::class)
fun FloatingGlassNavigationBar(
    items: List<GlassNavigationItem>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    maximumWidth: Dp = 520.dp,
) {
    if (items.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val regularStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val selectedStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val animation = tween<Dp>(
        durationMillis = NAVIGATION_MOTION_DURATION_MS,
        easing = FastOutSlowInEasing,
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val labelWidths = items.map { item ->
            val regular = textMeasurer.measure(
                text = AnnotatedString(item.label),
                style = regularStyle,
                maxLines = 1,
                layoutDirection = layoutDirection,
            ).size.width
            val selected = textMeasurer.measure(
                text = AnnotatedString(item.label),
                style = selectedStyle,
                maxLines = 1,
                layoutDirection = layoutDirection,
            ).size.width
            with(density) { max(regular, selected).toDp() }
        }
        val availableWidth = maxWidth.coerceAtMost(maximumWidth).coerceAtLeast(8.dp)
        val labelsTotal = labelWidths.fold(0.dp) { total, width -> total + width }
        // Reserve the selected icon and its gap in the selected item instead of
        // scaling every item when a fifth destination is visible. Scaling the
        // item slot does not scale the Compose text/icon content, which made
        // the selected label extend beyond the moving indicator on narrow
        // screens.
        val selectedContentExtra = 20.dp + 4.dp
        val minimumLabelPadding = 12.dp
        val minimumLabeledWidth = 8.dp + labelsTotal + selectedContentExtra +
            minimumLabelPadding * items.size
        val useIconOnly = minimumLabeledWidth > availableWidth
        val itemWidths = if (useIconOnly) {
            val iconItemWidth = ((availableWidth - 8.dp) / items.size.toFloat())
                .coerceIn(40.dp, 56.dp)
            List(items.size) { iconItemWidth }
        } else {
            val horizontalSpace = (
                (availableWidth - 8.dp - labelsTotal - selectedContentExtra) /
                    items.size.toFloat()
            ).coerceIn(minimumLabelPadding, 48.dp)
            labelWidths.mapIndexed { index, labelWidth ->
                labelWidth + horizontalSpace +
                    if (index == safeIndex) selectedContentExtra else 0.dp
            }
        }

        val targetBarWidth = 8.dp + itemWidths.fold(0.dp) { total, width -> total + width }
        val animatedBarWidth = animateDpAsState(
            targetValue = targetBarWidth,
            animationSpec = animation,
            label = "glass-bar-width",
        ).value
        val animatedItemWidths = itemWidths.mapIndexed { index, targetWidth ->
            animateDpAsState(
                targetValue = targetWidth,
                animationSpec = animation,
                label = "glass-nav-item-width-$index",
            ).value
        }
        val targetIndicatorStart = 6.dp + itemWidths.take(safeIndex).fold(0.dp) { total, width ->
            total + width
        }
        val targetIndicatorWidth = (itemWidths[safeIndex] - 4.dp).coerceAtLeast(0.dp)
        val indicatorStart = animateDpAsState(
            targetIndicatorStart,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 450f),
            label = "glass-indicator-start",
        ).value
        val indicatorWidth = animateDpAsState(
            targetIndicatorWidth,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 500f),
            label = "glass-indicator-width",
        ).value
        val scheme = MaterialTheme.colorScheme
        val darkSurface = scheme.surface.luminance() < 0.5f
        val rim = Brush.verticalGradient(listOf(
            scheme.outline.copy(alpha = if (darkSurface) 0.58f else 0.36f),
            scheme.outlineVariant.copy(alpha = 0.28f),
            scheme.outline.copy(alpha = if (darkSurface) 0.40f else 0.24f),
        ))
        val innerRim = Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (darkSurface) 0.24f else 0.68f),
            Color.White.copy(alpha = 0.02f),
            scheme.primary.copy(alpha = 0.12f),
        ))
        Box(
            modifier = Modifier
                .width(animatedBarWidth)
                .height(60.dp)
                .shadow(
                    elevation = 5.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.07f),
                    spotColor = Color.Black.copy(alpha = 0.07f),
                )
                .clip(CircleShape)
                .hazeEffect(state = hazeState) {
                    blurRadius = 18.dp
                    noiseFactor = 0.03f
                    inputScale = HazeInputScale.Fixed(0.5f)
                }
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.surface.copy(alpha = 0.38f),
                            scheme.surface.copy(alpha = 0.24f),
                        ),
                    ),
                )
                .border(1.dp, rim, CircleShape)
                .drawWithCache {
                    val inset = 1.8.dp.toPx()
                    val stroke = Stroke(0.7.dp.toPx())
                    onDrawWithContent {
                        drawContent()
                        // An inset reflection gives the glass rim depth without enlarging the bar.
                        drawRoundRect(
                            brush = innerRim,
                            topLeft = Offset(inset, inset),
                            size = Size((size.width - inset * 2).coerceAtLeast(0f),
                                (size.height - inset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius((size.height / 2 - inset).coerceAtLeast(0f)),
                            style = stroke,
                        )
                    }
                }
                .testTag("glass-navigation-bar"),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorStart.coerceIn(4.dp,
                        (animatedBarWidth - indicatorWidth - 4.dp).coerceAtLeast(4.dp)), y = 9.dp)
                    .width(indicatorWidth)
                    .height(42.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest.copy(alpha = 0.64f))
                    .border(0.7.dp, innerRim, CircleShape)
                    .testTag("glass-nav-indicator"),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val selected = index == safeIndex
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    val pressed = interactionSource.collectIsPressedAsState().value
                    val contentScale = animateFloatAsState(
                        targetValue = if (pressed) 0.94f else if (selected) 1.025f else 1f,
                        animationSpec = spring(dampingRatio = 0.64f, stiffness = 650f),
                        label = "glass-nav-content-scale-$index",
                    ).value
                    val contentMotion = Modifier.graphicsLayer {
                        scaleX = contentScale
                        scaleY = contentScale
                    }
                    Box(
                        modifier = Modifier
                            .width(animatedItemWidths[index])
                            .height(60.dp)
                            .semantics {
                                contentDescription = item.label
                                role = Role.Tab
                                this.selected = selected
                            }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    if (!selected) onDestinationSelected(index)
                                },
                            )
                            .testTag("glass-nav-item-$index"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (useIconOnly) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                modifier = contentMotion.size(24.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                modifier = contentMotion,
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                AnimatedVisibility(
                                    visible = selected,
                                    enter = fadeIn(tween(NAVIGATION_MOTION_DURATION_MS)) +
                                        expandHorizontally(
                                            animationSpec = tween(
                                                NAVIGATION_MOTION_DURATION_MS,
                                                easing = FastOutSlowInEasing,
                                            ),
                                            expandFrom = Alignment.Start,
                                        ),
                                    exit = fadeOut(tween(NAVIGATION_MOTION_DURATION_MS / 2)) +
                                        shrinkHorizontally(
                                            animationSpec = tween(
                                                NAVIGATION_MOTION_DURATION_MS,
                                                easing = FastOutSlowInEasing,
                                            ),
                                            shrinkTowards = Alignment.Start,
                                        ),
                                    ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = scheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                }
                                Text(
                                    text = item.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                    style = if (selected) selectedStyle else regularStyle,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

const val NAVIGATION_MOTION_DURATION_MS = 210
