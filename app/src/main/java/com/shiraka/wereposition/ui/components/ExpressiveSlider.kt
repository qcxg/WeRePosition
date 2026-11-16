package com.shiraka.wereposition.ui.components

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Hoisted value with the current Material 3 slider state API and expressive track. */
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors()
) {
    val state = rememberSliderState(value, steps, trackRange = valueRange)
    state.value = value
    Slider(state = state, onValueChange = onValueChange, modifier = modifier,
        enabled = enabled, onValueChangeFinished = onValueChangeFinished, colors = colors)
}
