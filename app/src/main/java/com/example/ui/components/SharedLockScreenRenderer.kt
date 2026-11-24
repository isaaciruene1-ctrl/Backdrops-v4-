package com.example.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.model.ChargingAnimationConfig
import com.example.data.model.ChargingAnimationPreset
import com.example.data.model.ChargingPosition
import com.example.data.model.StudioWidgetItem
import com.example.data.model.UiStyleMode
import com.example.data.model.UnlockButtonStyle
import com.example.data.model.UnlockButtonThemeConfig
import com.example.data.model.UserPreferencesDoc
import com.example.data.model.Wallpaper
import com.example.engine.notifications.LockNotificationGroup
import com.example.engine.notifications.LockNotificationItem
import com.example.ui.screens.CanvasLookFilter
import com.example.ui.theme.LocalUiStyleMode
import com.example.ui.theme.rememberStudioHaptics
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Unified Lock Screen Canvas Renderer shared identically by:
 * - Live Lock Screen Mode (`BackdropLockActivity` & `LockScreenOverlaySurface`)
 * - Lock Screen Mode interactive preview (`LockScreenModeScreen`)
 * - Preview Studio's Lock Screen tab (`PreviewStudioScreen`)
 *
 * Guarantees exact 1:1 visual parity across clock placement (`placementY`, `offsetXFraction`),
 * color customization, Glow & Liquid Glass materials, iOS-inspired stacked notifications,
 * charging animations, resizable Lock Screen widgets, full-bleed enlarged media player, and unlock bar.
 */
@Composable
fun SharedLockScreenRenderer(
    wallpaper: Wallpaper,
    customBitmap: Bitmap?,
    backdropBitmap: Bitmap?,
    foregroundSubjectCutout: Bitmap? = null,
    clockConfig: ClockDesignConfig,
    telemetry: ClockWidgetTelemetry,
    preferences: UserPreferencesDoc = UserPreferencesDoc(),
    zoomScale: Float = 1.0f,
    panOffset: Offset = Offset.Zero,
    rotationDegrees: Float = 0f,
    dimFraction: Float = 0f,
    lookFilter: CanvasLookFilter = CanvasLookFilter.ORIGINAL,
    notificationGroups: List<LockNotificationGroup> = emptyList(),
    expandedNotificationKeys: MutableMap<String, Boolean> = remember { mutableMapOf() },
    hideSensitiveNotifications: Boolean = false,
    isCharging: Boolean = false,
    previewChargingOverlay: Boolean = false,
    showChromeControls: Boolean = true,
    isPreviewMode: Boolean = false,
    useLiveTime: Boolean = true,
    onUnlockClick: () -> Unit = {},
    onOpenNotification: (LockNotificationItem) -> Unit = {},
    onDismissNotification: (String) -> Unit = {},
    onClearAllNotifications: () -> Unit = {},
    onUpdateWidgetItem: ((StudioWidgetItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val uiStyle = LocalUiStyleMode.current
    val haptics = rememberStudioHaptics()
    val isGlass = uiStyle == UiStyleMode.FROSTED_GLASS_OS

    val primaryColor = clockConfig.customPrimaryArgb?.let { Color(it.toInt()) } ?: Color(0xFFF6F8FC)
    val secondaryColor = clockConfig.customSecondaryArgb?.let { Color(it.toInt()) }
        ?: wallpaper.dominantColors.firstOrNull()?.let { Color(it) }
        ?: MaterialTheme.colorScheme.primary

    val notificationConfig = remember(preferences.notificationStyleJson) {
        preferences.parsedNotificationStyle
    }
    val chargingConfig = remember(preferences.chargingAnimationJson) {
        preferences.parsedChargingAnimation
    }
    val unlockTheme = remember(preferences.unlockButtonThemeJson) {
        preferences.parsedUnlockButtonTheme
    }
    val studioWidgets = remember(preferences.studioWidgetsJson) {
        preferences.parsedStudioWidgets
    }

    var clockUnscaledSize by remember { mutableStateOf(IntSize(420, 180)) }
    var isWidgetResizeMode by remember { mutableStateOf(false) }
    var expandedMediaState by remember { mutableStateOf<ActiveMediaPlaybackState?>(null) }

    LaunchedEffect(expandedMediaState != null) {
        while (expandedMediaState != null) {
            delay(1000L)
            val refreshed = StudioWidgetSystemBridge.readActiveMediaState(context)
            if (refreshed.hasSession) {
                expandedMediaState = refreshed
            }
        }
    }

    BackHandler(enabled = expandedMediaState != null || isWidgetResizeMode) {
        if (expandedMediaState != null) {
            expandedMediaState = null
        } else if (isWidgetResizeMode) {
            isWidgetResizeMode = false
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportPx = IntSize(
            width = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) },
            height = with(density) { maxHeight.toPx().roundToInt().coerceAtLeast(1) }
        )
        val marginPx = with(density) { 10.dp.toPx() }
        val resolvedClockBox = remember(clockUnscaledSize, viewportPx, clockConfig, marginPx) {
            ClockSpatialMath.computeClockBoxRect(
                unscaledWidthPx = clockUnscaledSize.width.toFloat(),
                unscaledHeightPx = clockUnscaledSize.height.toFloat(),
                viewportSize = viewportPx,
                config = clockConfig,
                minMarginPx = marginPx
            )
        }

        // 1. Background Wallpaper + Transform + Look Filter
        val artworkModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (customBitmap == null) {
                    val s = zoomScale.coerceIn(0.85f, 4.0f)
                    scaleX = s
                    scaleY = s
                    translationX = panOffset.x
                    translationY = panOffset.y
                    rotationZ = rotationDegrees
                }
            }

        val validCustomBmp = customBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        if (validCustomBmp != null) {
            val customImg = remember(validCustomBmp) { validCustomBmp.asImageBitmap() }
            Image(
                bitmap = customImg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = lookFilter.toComposeColorFilter(),
                modifier = artworkModifier
            )
        } else {
            WallpaperAsyncImage(
                wallpaper = wallpaper,
                useFullRes = true,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = lookFilter.toComposeColorFilter(),
                modifier = artworkModifier
            )
        }

        // Subtle vignette & legibility dim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = (0.24f + dimFraction * 0.5f).coerceAtMost(0.88f)),
                            Color.Black.copy(alpha = dimFraction.coerceAtMost(0.75f)),
                            Color.Black.copy(alpha = (0.48f + dimFraction * 0.45f).coerceAtMost(0.92f))
                        )
                    )
                )
        )

        // 2. Clock positioned accurately via ClockSpatialMath.computeClockBoxRect (placementY + offsetXFraction)
        val clockLeftPx = resolvedClockBox.leftPx.roundToInt()
        val clockTopPx = resolvedClockBox.topPx.roundToInt()
        Box(
            modifier = Modifier
                .offset { IntOffset(x = clockLeftPx, y = clockTopPx) }
        ) {
            HyperOsLockClock(
                face = clockConfig.face,
                font = clockConfig.font,
                finish = clockConfig.finish,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                weightLevel = clockConfig.weightLevel,
                scaleFactor = clockConfig.scale,
                widthScale = clockConfig.widthScale,
                heightScale = clockConfig.heightScale,
                opacity = clockConfig.opacity,
                alignment = clockConfig.alignment,
                useLiveTime = useLiveTime,
                is24HourFormat = clockConfig.is24HourFormat,
                showDateAndWeather = clockConfig.showDateAndWeather,
                widgetsEnabled = clockConfig.widgetsEnabled,
                showDateWidget = clockConfig.showDateWidget,
                showWeatherWidget = clockConfig.showWeatherWidget,
                showBatteryWidget = clockConfig.showBatteryWidget,
                showDecorativeWidget = clockConfig.showDecorativeWidget,
                telemetry = telemetry,
                signatureMode = clockConfig.signatureMode,
                signatureText = clockConfig.signatureText,
                liquidGlassConfig = clockConfig.liquidGlass,
                backdropBitmap = backdropBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 },
                viewportSize = viewportPx,
                clockBoxRect = resolvedClockBox,
                zoomScale = if (validCustomBmp == null) zoomScale else 1f,
                panOffset = if (validCustomBmp == null) panOffset else Offset.Zero,
                dimFraction = dimFraction,
                lookFilter = lookFilter,
                rotationDegrees = if (validCustomBmp == null) rotationDegrees else 0f,
                scaleTextWithClock = clockConfig.scaleTextWithClock,
                glowConfig = clockConfig.glowConfig,
                onMeasuredUnscaledSize = { measured ->
                    if (measured.width > 0 && measured.height > 0) {
                        clockUnscaledSize = measured
                    }
                }
            )
        }

        // Optional foreground multi-subject depth cutout layer over clock digits
        val validCutout = foregroundSubjectCutout?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        if (clockConfig.depthOverlapEnabled && validCutout != null) {
            val cutoutImg = remember(validCutout) { validCutout.asImageBitmap() }
            Image(
                bitmap = cutoutImg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = artworkModifier
            )
        }

        val hasEnabledWidgets = studioWidgets.any { it.enabled } && clockConfig.widgetsEnabled

        // 3. Top Lock Protection Status + Direct Corner Widget Resize Toggle
        if (showChromeControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (preferences.hasLockCredential) {
                    Row(
                        modifier = Modifier.testTag("lockscreen_status_bar"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Backdrop Lock Protected",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = preferences.parsedLockCredentialType.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (hasEnabledWidgets && onUpdateWidgetItem != null) {
                    Surface(
                        onClick = {
                            haptics.performTick(22L)
                            isWidgetResizeMode = !isWidgetResizeMode
                        },
                        shape = RoundedCornerShape(50),
                        color = if (isWidgetResizeMode) {
                            secondaryColor.copy(alpha = 0.88f)
                        } else {
                            Color.Black.copy(alpha = 0.44f)
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = Color.White.copy(alpha = if (isWidgetResizeMode) 0.72f else 0.28f)
                        ),
                        modifier = Modifier.testTag("lock_resize_widgets_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.OpenWith,
                                contentDescription = "Resize Widgets",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isWidgetResizeMode) "Done Resizing" else "Resize Widgets",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 4. Widgets + Dynamic Non-Overlapping Notifications Zone
        val clockBottomDp = with(density) { resolvedClockBox.bottomPx.toDp() }
        val topClearanceDp = maxOf(clockBottomDp + 14.dp, maxHeight * 0.16f)
        val clockBottomFrac = (topClearanceDp / maxHeight.coerceAtLeast(1.dp)).coerceIn(0.16f, 0.88f)
        val widgetReserveDp = if (hasEnabledWidgets) 128f else 0f
        val showChargingNow = chargingConfig.enabled &&
            chargingConfig.preset != ChargingAnimationPreset.OFF &&
            (isCharging || previewChargingOverlay)
        val bottomReserveDp = if (showChromeControls) {
            if (showChargingNow && chargingConfig.position == ChargingPosition.BOTTOM) 186f else 132f
        } else {
            92f
        }
        val notifZone = LockScreenNotificationLayoutSolver.solveAvailableZone(
            viewportHeightDp = maxHeight.value,
            clockBottomFraction = clockBottomFrac,
            activeWidgetHeightDp = widgetReserveDp,
            bottomReserveDp = bottomReserveDp,
            styleConfig = notificationConfig
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topClearanceDp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hasEnabledWidgets) {
                StudioWidgetsLockStrip(
                    widgets = studioWidgets,
                    telemetry = telemetry,
                    isCharging = isCharging,
                    is24HourFormat = clockConfig.is24HourFormat,
                    isResizeMode = isWidgetResizeMode && onUpdateWidgetItem != null,
                    onUpdateWidgetItem = onUpdateWidgetItem,
                    onExpandMediaPlayer = { media ->
                        isWidgetResizeMode = false
                        expandedMediaState = media
                    }
                )
            }

            if (notificationGroups.isNotEmpty()) {
                LockScreenNotificationsSection(
                    groups = notificationGroups,
                    styleConfig = notificationConfig,
                    expandedKeys = expandedNotificationKeys,
                    hideSensitive = hideSensitiveNotifications,
                    maxVisibleOverride = notifZone.effectiveMaxGroups,
                    forceCompact = notifZone.forceCompact,
                    accentColor = secondaryColor,
                    onOpenItem = onOpenNotification,
                    onDismissItem = onDismissNotification,
                    onClearAll = onClearAllNotifications,
                    modifier = Modifier.heightIn(max = notifZone.maxListHeightDp)
                )
            }
        }

        // 5. Animated Charging Overlay (Center or Bottom above Unlock Bar)
        AnimatedVisibility(
            visible = showChargingNow && expandedMediaState == null,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(if (chargingConfig.position == ChargingPosition.CENTER) Alignment.Center else Alignment.BottomCenter)
                .then(
                    if (chargingConfig.position == ChargingPosition.BOTTOM) {
                        Modifier.navigationBarsPadding().padding(bottom = if (showChromeControls) 114.dp else 24.dp)
                    } else {
                        Modifier
                    }
                )
        ) {
            LockScreenChargingAnimationOverlay(
                config = chargingConfig,
                batteryPercent = telemetry.batteryPercent,
                dynamicAccent = secondaryColor
            )
        }

        // 6. Themeable Unlock Button + Clean Bottom Hint ("Swipe up or tap unlock")
        if (showChromeControls && expandedMediaState == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    Text(
                        text = "Swipe up or tap unlock",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("lockscreen_unlock_hint")
                    )
                }

                ThemedUnlockButton(
                    themeConfig = unlockTheme,
                    dynamicAccent = secondaryColor,
                    isGlassMode = isGlass,
                    label = when {
                        isPreviewMode -> "Exit Preview"
                        preferences.hasLockCredential -> "Unlock with ${preferences.parsedLockCredentialType.label}"
                        else -> "Unlock Device"
                    },
                    onClick = onUnlockClick
                )
            }
        }

        // 7. iOS-Style Full-Bleed Edge-to-Edge Enlarged Media Player Overlay
        AnimatedVisibility(
            visible = expandedMediaState != null,
            enter = fadeIn(tween(240)) + scaleIn(initialScale = 0.94f, animationSpec = tween(260)),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.95f, animationSpec = tween(200))
        ) {
            val activeMedia = expandedMediaState ?: ActiveMediaPlaybackState()
            EnlargedFullBleedMediaPlayer(
                mediaState = activeMedia,
                fallbackWallpaper = wallpaper,
                fallbackBackdropBitmap = backdropBitmap,
                accentColor = secondaryColor,
                onUpdateMediaState = { expandedMediaState = it },
                onCollapse = { expandedMediaState = null }
            )
        }
    }
}

@Composable
private fun EnlargedFullBleedMediaPlayer(
    mediaState: ActiveMediaPlaybackState,
    fallbackWallpaper: Wallpaper,
    fallbackBackdropBitmap: Bitmap?,
    accentColor: Color,
    onUpdateMediaState: (ActiveMediaPlaybackState) -> Unit,
    onCollapse: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberStudioHaptics()
    val validAlbumArt = mediaState.albumArt?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    val validFallbackBmp = fallbackBackdropBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D14))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCollapse
            )
            .testTag("enlarged_media_player_overlay")
    ) {
        // Edge-to-edge full-bleed album art filling the entire screen
        when {
            validAlbumArt != null -> {
                val artImg = remember(validAlbumArt) { validAlbumArt.asImageBitmap() }
                Image(
                    bitmap = artImg,
                    contentDescription = "Full-screen album artwork for ${mediaState.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            validFallbackBmp != null -> {
                val bgImg = remember(validFallbackBmp) { validFallbackBmp.asImageBitmap() }
                Image(
                    bitmap = bgImg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                WallpaperAsyncImage(
                    wallpaper = fallbackWallpaper,
                    useFullRes = true,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Subtle ambient center vinyl/music emblem when artwork isn't supplied by external player
        if (validAlbumArt == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 120.dp)
                    .size(164.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.86f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // Bottom gradient scrim smoothly elevating controls & typography over full-bleed artwork
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.28f),
                        0.45f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.68f),
                        1.0f to Color.Black.copy(alpha = 0.94f)
                    )
                )
        )

        // Top minimize affordance pill
        Surface(
            onClick = {
                haptics.performTick(16L)
                onCollapse()
            },
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.48f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
                .testTag("enlarged_media_collapse_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Minimize full-screen media player",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Bottom Gradient Controls Dock (intercept clicks so tapping buttons won't collapse view)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = mediaState.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mediaState.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val progress = if (mediaState.durationMs > 0L) {
                (mediaState.positionMs.toFloat() / mediaState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.24f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatPlaybackDuration(mediaState.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    Text(
                        text = if (mediaState.durationMs > 0L) formatPlaybackDuration(mediaState.durationMs) else "--:--",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        haptics.performTick(18L)
                        mediaState.controller?.transportControls?.skipToPrevious()
                        val refreshed = StudioWidgetSystemBridge.readActiveMediaState(context)
                        if (refreshed.hasSession) onUpdateMediaState(refreshed)
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("enlarged_media_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous track",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Surface(
                    onClick = {
                        haptics.performTick(24L)
                        val tc = mediaState.controller?.transportControls
                        if (mediaState.isPlaying) tc?.pause() else tc?.play()
                        val refreshed = StudioWidgetSystemBridge.readActiveMediaState(context)
                        onUpdateMediaState(
                            if (refreshed.hasSession) refreshed
                            else mediaState.copy(isPlaying = !mediaState.isPlaying)
                        )
                    },
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("enlarged_media_play_pause_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (mediaState.isPlaying) "Pause" else "Play",
                            tint = Color(0xFF0E121B),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        haptics.performTick(18L)
                        mediaState.controller?.transportControls?.skipToNext()
                        val refreshed = StudioWidgetSystemBridge.readActiveMediaState(context)
                        if (refreshed.hasSession) onUpdateMediaState(refreshed)
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("enlarged_media_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next track",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}

private fun formatPlaybackDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}

@Composable
fun ThemedUnlockButton(
    themeConfig: UnlockButtonThemeConfig,
    dynamicAccent: Color,
    isGlassMode: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when (themeConfig.style) {
        UnlockButtonStyle.DYNAMIC_ACCENT -> dynamicAccent.copy(alpha = themeConfig.transparency)
        UnlockButtonStyle.SOLID -> Color.White.copy(alpha = themeConfig.transparency.coerceAtLeast(0.85f))
        UnlockButtonStyle.FROSTED -> Color(0xFF141824).copy(alpha = (themeConfig.transparency * 0.68f).coerceIn(0.32f, 0.85f))
        UnlockButtonStyle.TRANSPARENT -> Color.Black.copy(alpha = 0.24f)
        UnlockButtonStyle.M3_TONAL -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = themeConfig.transparency)
        UnlockButtonStyle.CUSTOM -> Color(themeConfig.customContainerArgb.toInt()).copy(alpha = themeConfig.transparency)
    }
    val contentColor = when (themeConfig.style) {
        UnlockButtonStyle.DYNAMIC_ACCENT -> Color.White
        UnlockButtonStyle.SOLID -> Color(0xFF10131C)
        UnlockButtonStyle.FROSTED,
        UnlockButtonStyle.TRANSPARENT -> Color.White
        UnlockButtonStyle.M3_TONAL -> MaterialTheme.colorScheme.onSecondaryContainer
        UnlockButtonStyle.CUSTOM -> Color(themeConfig.customContentArgb.toInt())
    }
    val border = when {
        themeConfig.style == UnlockButtonStyle.FROSTED || (isGlassMode && themeConfig.style != UnlockButtonStyle.SOLID) ->
            BorderStroke(1.dp, Color.White.copy(alpha = 0.44f))
        themeConfig.style == UnlockButtonStyle.TRANSPARENT ->
            BorderStroke(1.dp, Color.White.copy(alpha = 0.55f))
        else -> null
    }

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(themeConfig.cornerRadiusDp.dp),
        border = border,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier
            .height(48.dp)
            .testTag("lockscreen_unlock_button")
    ) {
        Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LockScreenChargingAnimationOverlay(
    config: ChargingAnimationConfig,
    batteryPercent: Int,
    dynamicAccent: Color,
    modifier: Modifier = Modifier
) {
    val accent = if (config.dynamicAccentColor) dynamicAccent else Color(config.customAccentArgb.toInt())
    val safePct = batteryPercent.coerceIn(0, 100)
    val durationMs = (1900f / config.speedMultiplier.coerceIn(0.5f, 2f)).roundToInt().coerceIn(700, 4200)
    val transition = rememberInfiniteTransition(label = "lockscreen_charging")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "charging_phase"
    )
    val pulseScale = 0.94f + 0.08f * sin(phase * 2.0 * PI).toFloat()
    val sizeDp = (108f * config.sizeScale.coerceIn(0.65f, 1.5f)).dp

    when (config.preset) {
        ChargingAnimationPreset.OFF -> Unit

        ChargingAnimationPreset.MINIMAL_BEAM -> {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF0E131E).copy(alpha = 0.72f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                modifier = modifier.testTag("lockscreen_charging_overlay")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = "Charging",
                        tint = accent,
                        modifier = Modifier.size(18.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                    )
                    if (config.showPercentage) {
                        Text("$safePct%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    if (config.showStatusText) {
                        Text("Fast Charging", style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = modifier
                    .size(sizeDp)
                    .testTag("lockscreen_charging_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width * 0.5f, size.height * 0.5f)
                    val radius = size.minDimension * 0.42f
                    when (config.preset) {
                        ChargingAnimationPreset.PULSE_RING -> {
                            drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = radius, center = c)
                            drawCircle(
                                color = accent.copy(alpha = (0.35f * (1f - phase)).coerceIn(0.05f, 0.45f)),
                                radius = radius * (0.85f + phase * 0.30f),
                                center = c,
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawArc(
                                color = accent,
                                startAngle = -90f,
                                sweepAngle = (safePct / 100f) * 360f,
                                useCenter = false,
                                topLeft = Offset(c.x - radius, c.y - radius),
                                size = Size(radius * 2f, radius * 2f),
                                style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        ChargingAnimationPreset.LIQUID_WAVE -> {
                            drawCircle(color = Color(0xFF0E131D).copy(alpha = 0.65f), radius = radius, center = c)
                            val fillTop = c.y + radius - (safePct / 100f) * (radius * 2f)
                            val wavePath = Path().apply {
                                val startX = c.x - radius
                                val endX = c.x + radius
                                moveTo(startX, c.y + radius)
                                lineTo(startX, fillTop)
                                val steps = 16
                                for (i in 0..steps) {
                                    val t = i / steps.toFloat()
                                    val x = startX + (endX - startX) * t
                                    val wave = sin(t * 2f * PI + phase * 2f * PI).toFloat() * 4.dp.toPx()
                                    lineTo(x, fillTop + wave)
                                }
                                lineTo(endX, c.y + radius)
                                close()
                            }
                            drawPath(path = wavePath, color = accent.copy(alpha = 0.45f))
                            drawCircle(color = accent.copy(alpha = 0.78f), radius = radius, center = c, style = Stroke(width = 2.5.dp.toPx()))
                        }
                        ChargingAnimationPreset.ORB_HALO -> {
                            drawCircle(color = Color.Black.copy(alpha = 0.42f), radius = radius, center = c)
                            drawArc(
                                color = accent,
                                startAngle = phase * 360f,
                                sweepAngle = 220f,
                                useCenter = false,
                                topLeft = Offset(c.x - radius, c.y - radius),
                                size = Size(radius * 2f, radius * 2f),
                                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        else -> Unit
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = "Charging",
                        tint = accent,
                        modifier = Modifier.size((18f * config.sizeScale).dp)
                    )
                    if (config.showPercentage) {
                        Text(
                            text = "$safePct%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    if (config.showStatusText) {
                        Text(
                            text = "Charging",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }
        }
    }
}
