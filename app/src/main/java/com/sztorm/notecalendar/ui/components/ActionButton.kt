package com.sztorm.notecalendar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme
        .contentColorFor(containerColor),
    content: @Composable () -> Unit
) = AnimatedVisibility(
    visible = visible,
    enter = scaleIn() + expandIn(),
    exit = shrinkOut() + scaleOut()
) {
    // Material3 FloatingActionButton has no enabled parameter; emulate the disabled
    // state by dimming the colors and ignoring clicks.
    val resolvedContainerColor =
        if (enabled) containerColor else containerColor.copy(alpha = 0.38f)
    val resolvedContentColor =
        if (enabled) contentColor else contentColor.copy(alpha = 0.38f)

    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor = resolvedContainerColor,
        contentColor = resolvedContentColor,
        modifier = modifier.padding(8.dp)
    ) {
        content()
    }
}

@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme
        .contentColorFor(containerColor),
    icon: Painter,
    contentDescription: String,
) = ActionButton(
    modifier = modifier,
    visible = visible,
    onClick = onClick,
    containerColor = containerColor,
    contentColor = contentColor,
    content = {
        Icon(painter = icon, contentDescription = contentDescription)
    }
)