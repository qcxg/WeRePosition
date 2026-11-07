package com.shiraka.wereposition.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Translucent, high-contrast glass treatment for controls placed over the map. */
fun Modifier.frostedSurface(
    isDark: Boolean,
    cornerRadius: Dp = 20.dp
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    val top = if (isDark) Color(0xE62B3A50) else Color(0xEDFFFFFF)
    val bottom = if (isDark) Color(0xD9162232) else Color(0xDDECF4FF)
    val edge = if (isDark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.95f)
    return this
        .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.15f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(1.dp, edge, shape)
}
