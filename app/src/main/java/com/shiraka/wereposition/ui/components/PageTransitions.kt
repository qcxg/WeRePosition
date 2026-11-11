package com.shiraka.wereposition.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** The same slide distance and timing used by Settings detail pages. */
internal fun horizontalPageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(tween(260)) { it / 3 * direction } + fadeIn(tween(180)))
        .togetherWith(slideOutHorizontally(tween(260)) { -it / 3 * direction } + fadeOut(tween(180)))
}
