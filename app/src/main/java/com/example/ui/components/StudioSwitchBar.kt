package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.UiStyleMode
import com.example.ui.theme.LocalUiStyleMode
import com.example.ui.theme.rememberStudioHaptics

enum class StudioDestination(val label: String, val icon: ImageVector) {
    DEPTH("Depth", Icons.Rounded.AutoAwesome),
    PREVIEW("Preview", Icons.Rounded.Visibility),
    LOCK("Lock Mode", Icons.Rounded.Lock)
}

/**
 * 3-Way Studio Trifecta Switcher:
 * Connects Depth Studio, Preview Studio, and Lock Screen Mode seamlessly.
 *
 * Visual System Adaptive:
 * - Material 3: Semantic M3 Expressive Segmented Header Pill.
 * - Frosted Glass OS: Refractive sliding glass capsule with spring physics.
 */
@Composable
fun StudioSwitchBar(
    currentDestination: StudioDestination,
    onSelectDestination: (StudioDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalUiStyleMode.current
    val isFrosted = uiStyle == UiStyleMode.FROSTED_GLASS_OS
    val haptics = rememberStudioHaptics()

    val destinations = StudioDestination.entries

    if (isFrosted) {
        // Frosted Glass OS Sliding Capsule
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.42f),
            modifier = modifier
                .height(44.dp)
                .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(50))
                .testTag("studio_switch_bar_frosted")
        ) {
            Row(
                modifier = Modifier
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { dest ->
                    val isSelected = dest == currentDestination
                    val bgAlpha = if (isSelected) 0.32f else 0.0f
                    val borderAlpha = if (isSelected) 0.45f else 0.0f
                    val contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f)

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = bgAlpha))
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = Color.White.copy(alpha = borderAlpha),
                                shape = RoundedCornerShape(50)
                            )
                            .clickable {
                                if (!isSelected) {
                                    haptics.performTick(25L)
                                    onSelectDestination(dest)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                            .testTag("studio_switch_${dest.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.height(15.dp)
                            )
                            Text(
                                text = dest.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    } else {
        // Material 3 Expressive Segmented Pill
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = modifier
                .height(44.dp)
                .testTag("studio_switch_bar_m3")
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { dest ->
                    val isSelected = dest == currentDestination
                    val containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    }
                    val contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(containerColor)
                            .clickable {
                                if (!isSelected) {
                                    haptics.performTick(25L)
                                    onSelectDestination(dest)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                            .testTag("studio_switch_${dest.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.height(15.dp)
                            )
                            Text(
                                text = dest.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
