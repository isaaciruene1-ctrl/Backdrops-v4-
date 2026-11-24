package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.example.data.model.ChargingAnimationConfig
import com.example.data.model.LockCredentialType
import com.example.data.model.LockNotificationStyleConfig
import com.example.data.model.LockScreenSetDoc
import com.example.data.model.StudioWidgetItem
import com.example.data.model.UnlockButtonThemeConfig
import com.example.data.model.UserPreferencesDoc
import com.example.data.weather.OpenMeteoWeatherRepository
import com.example.engine.notifications.LockScreenNotificationRepository
import com.example.engine.overlay.BackdropLockActivity
import com.example.engine.overlay.CompatibilityStatus
import com.example.engine.overlay.LockScreenCompatibilityManager
import com.example.engine.overlay.LockScreenOverlayService
import com.example.engine.overlay.LockScreenOverlaySurface
import com.example.ui.components.ClockDesignConfig
import com.example.ui.components.ClockWidgetTelemetry
import com.example.ui.components.StudioOptionPill
import com.example.ui.components.StudioWidgetsConfiguratorCard
import com.example.ui.components.TactileSurface
import com.example.ui.theme.rememberStudioHaptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockScreenModeScreen(
    preferences: UserPreferencesDoc,
    savedSets: List<LockScreenSetDoc>,
    currentClockConfig: ClockDesignConfig,
    onBack: () -> Unit,
    onOpenCompatibility: () -> Unit,
    onSelectOverlaySetId: (String) -> Unit,
    onSetOverlayEnabled: (Boolean) -> Unit,
    onSetLockNotificationsEnabled: (Boolean) -> Unit = {},
    onSetHideSensitiveNotifications: (Boolean) -> Unit = {},
    onSetLockCredential: (LockCredentialType, String) -> Unit = { _, _ -> },
    onClearLockCredential: () -> Unit = {},
    onSetNotificationStyleConfig: (LockNotificationStyleConfig) -> Unit = {},
    onSetChargingAnimationConfig: (ChargingAnimationConfig) -> Unit = {},
    onSetUnlockButtonThemeConfig: (UnlockButtonThemeConfig) -> Unit = {},
    onSetStudioWidgets: (List<StudioWidgetItem>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberStudioHaptics()

    var diagnostics by remember { mutableStateOf(LockScreenCompatibilityManager.inspectDevice(context)) }
    var hasPostNotif by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var showFullPreview by remember { mutableStateOf(false) }
    var previewChargingOverlay by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPostNotif = granted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                diagnostics = LockScreenCompatibilityManager.inspectDevice(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasPostNotif = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activeSet = savedSets.firstOrNull { it.id == preferences.activeOverlaySetId } ?: savedSets.firstOrNull()
    val previewClock = activeSet?.let { ClockDesignConfig.fromJsonString(it.clockConfigJson) } ?: currentClockConfig

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Lock Screen Mode", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("lockscreen_mode_back")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(
                    onClick = onOpenCompatibility,
                    modifier = Modifier.testTag("lockscreen_mode_compat_action")
                ) {
                    Icon(Icons.Rounded.Devices, contentDescription = "Compatibility Diagnostics")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
            ) {
                // Enable / Disable Banner
                item {
                    TactileSurface(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        text = "Enable Backdrop Lock Screen",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (preferences.lockScreenOverlayEnabled && diagnostics.canDrawOverlays) {
                                            "Active • Launches custom lock screen on display wake"
                                        } else {
                                            "Displays custom clock & notifications on display wake"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = preferences.lockScreenOverlayEnabled,
                                    onCheckedChange = { enabled ->
                                        haptics.performConfirm()
                                        if (enabled && !diagnostics.canDrawOverlays) {
                                            runCatching {
                                                context.startActivity(
                                                    LockScreenCompatibilityManager.buildOverlayPermissionIntent(context)
                                                )
                                            }
                                        } else {
                                            onSetOverlayEnabled(enabled)
                                            if (enabled) {
                                                LockScreenOverlayService.start(context)
                                            } else {
                                                LockScreenOverlayService.stop(context)
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("lockscreen_enable_switch")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        previewChargingOverlay = false
                                        showFullPreview = true
                                    },
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("lockscreen_preview_button")
                                ) {
                                    Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Preview", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                if (!preferences.lockScreenOverlayEnabled) {
                                    Button(
                                        onClick = {
                                            haptics.performConfirm()
                                            if (!diagnostics.canDrawOverlays) {
                                                runCatching {
                                                    context.startActivity(
                                                        LockScreenCompatibilityManager.buildOverlayPermissionIntent(context)
                                                    )
                                                }
                                            } else {
                                                onSetOverlayEnabled(true)
                                                LockScreenOverlayService.start(context)
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("lockscreen_apply_button")
                                    ) {
                                        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Apply Mode", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            haptics.performTick(25L)
                                            onSetOverlayEnabled(false)
                                            LockScreenOverlayService.stop(context)
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("lockscreen_disable_button")
                                    ) {
                                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Disable", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    haptics.performTick(20L)
                                    runCatching {
                                        context.startActivity(
                                            BackdropLockActivity.createLaunchIntent(context, previewMode = true)
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("lockscreen_test_activity_button")
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Full-Screen Lock Experience", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // Lock Screen PIN / Password Protection Card (with clear security notice)
                item {
                    LockCredentialSetupCard(
                        preferences = preferences,
                        onSaveCredential = onSetLockCredential,
                        onClearCredential = onClearLockCredential
                    )
                }

                // Security Guarantee Card
                item {
                    TactileSurface(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Security & Keyguard Coexistence",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Backdrop presents an official full-screen lock experience on wake over unsecured keyguards (Swipe/None gives instant single-swipe replacement). Your PIN, pattern, password & biometrics are never bypassed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Select Clock Design Set
                item {
                    TactileSurface(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Select Clock Design",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (savedSets.isEmpty()) {
                                Text(
                                    text = "Using your active Depth Studio clock (${currentClockConfig.face.label}). Save Lock-Screen Sets in Depth Studio to switch setups here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(savedSets, key = { it.id }) { setDoc ->
                                        StudioOptionPill(
                                            label = setDoc.name,
                                            selected = (activeSet?.id == setDoc.id),
                                            onClick = {
                                                haptics.performTick(24L)
                                                onSelectOverlaySetId(setDoc.id)
                                            },
                                            modifier = Modifier.testTag("lockscreen_set_pill_${setDoc.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Lock Screen Notifications & Privacy Controls
                item {
                    TactileSurface(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Lock Screen Notifications",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        text = "Show Notifications on Lock Screen",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Grouped by app with icon, sender & tap-to-expand preview",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = preferences.lockNotificationsEnabled,
                                    onCheckedChange = {
                                        haptics.performTick(22L)
                                        onSetLockNotificationsEnabled(it)
                                    },
                                    modifier = Modifier.testTag("lockscreen_notifications_switch")
                                )
                            }

                            if (preferences.lockNotificationsEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                        Text(
                                            text = "Hide Sensitive Content",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Keep sender visible while masking private message text until unlocked",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = preferences.hideSensitiveNotifications,
                                        onCheckedChange = {
                                            haptics.performTick(22L)
                                            onSetHideSensitiveNotifications(it)
                                        },
                                        modifier = Modifier.testTag("lockscreen_hide_sensitive_switch")
                                    )
                                }
                            }
                        }
                    }
                }

                // OEM Notification Presets & Custom Styling
                if (preferences.lockNotificationsEnabled) {
                    item {
                        LockNotificationStyleCard(
                            config = preferences.parsedNotificationStyle,
                            onUpdate = onSetNotificationStyleConfig
                        )
                    }
                }

                // Charging Overlay Customizer
                item {
                    ChargingAnimationCustomizerCard(
                        config = preferences.parsedChargingAnimation,
                        onUpdate = onSetChargingAnimationConfig,
                        onTestChargingPreview = {
                            previewChargingOverlay = true
                            showFullPreview = true
                        }
                    )
                }

                // Unlock Button Appearance Theme
                item {
                    UnlockButtonThemeCard(
                        config = preferences.parsedUnlockButtonTheme,
                        onUpdate = onSetUnlockButtonThemeConfig
                    )
                }

                // 9-Widget Suite Configurator
                item {
                    StudioWidgetsConfiguratorCard(
                        widgets = preferences.parsedStudioWidgets,
                        onUpdateWidgets = onSetStudioWidgets
                    )
                }

                // Required Permissions Checklist
                item {
                    TactileSurface(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Grant Required Permissions",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            PermissionChecklistRow(
                                icon = Icons.Rounded.Layers,
                                title = "Display Over Other Apps",
                                description = "Allows launching Backdrop's lock screen when the display wakes.",
                                isGranted = diagnostics.canDrawOverlays,
                                actionLabel = if (diagnostics.canDrawOverlays) "Granted" else "Grant",
                                onClick = {
                                    runCatching {
                                        context.startActivity(LockScreenCompatibilityManager.buildOverlayPermissionIntent(context))
                                    }
                                },
                                rowTag = "perm_overlay_row"
                            )

                            PermissionChecklistRow(
                                icon = Icons.Rounded.NotificationsActive,
                                title = "Notification Access",
                                description = "Lets Backdrop display live, expandable notification cards on the lock screen.",
                                isGranted = diagnostics.isNotificationAccessGranted,
                                actionLabel = if (diagnostics.isNotificationAccessGranted) "Enabled" else "Allow",
                                onClick = {
                                    runCatching {
                                        context.startActivity(LockScreenNotificationRepository.buildNotificationAccessIntent(context))
                                    }
                                },
                                rowTag = "perm_notification_access_row"
                            )

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                PermissionChecklistRow(
                                    icon = Icons.Rounded.NotificationsActive,
                                    title = "Foreground Status Notification",
                                    description = "Required by Android 14+ to keep wake detection active reliably.",
                                    isGranted = hasPostNotif,
                                    actionLabel = if (hasPostNotif) "Granted" else "Allow",
                                    onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                    rowTag = "perm_notif_row"
                                )
                            }

                            PermissionChecklistRow(
                                icon = Icons.Rounded.BatteryChargingFull,
                                title = "Unrestricted Battery Usage",
                                description = "Recommended so OEM battery savers do not kill screen-on wake detection.",
                                isGranted = diagnostics.isBatteryOptimizationIgnored,
                                actionLabel = if (diagnostics.isBatteryOptimizationIgnored) "Enabled" else "Configure",
                                onClick = {
                                    runCatching {
                                        context.startActivity(LockScreenCompatibilityManager.buildBatteryOptimizationIntent(context))
                                    }
                                },
                                rowTag = "perm_battery_row"
                            )
                        }
                    }
                }

                // OEM Compatibility summary link
                item {
                    TactileSurface(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(onClick = onOpenCompatibility)
                            .testTag("open_compat_diagnostics_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${diagnostics.detectedProfile.displayName} • ${diagnostics.effectiveStatus.label}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "View full OEM compatibility & device diagnostics",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (diagnostics.effectiveStatus == CompatibilityStatus.FULLY_SUPPORTED) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.WarningAmber
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    if (showFullPreview) {
        val notificationGroups by LockScreenNotificationRepository.groups.collectAsState()
        val weatherState by OpenMeteoWeatherRepository.state.collectAsState()
        val weatherReady = weatherState as? com.example.data.weather.WeatherTelemetryState.Ready
        Dialog(
            onDismissRequest = { showFullPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val telemetry = ClockWidgetTelemetry(
                weatherText = weatherReady?.compactReadout ?: "READY",
                batteryPercent = OpenMeteoWeatherRepository.readBatteryPercent(context),
                isWeatherAvailable = weatherReady != null
            )
            LockScreenOverlaySurface(
                selectedSet = activeSet,
                clockConfig = previewClock,
                telemetry = telemetry,
                preferences = preferences,
                notificationGroups = if (preferences.lockNotificationsEnabled) notificationGroups else emptyList(),
                hideSensitiveNotifications = preferences.hideSensitiveNotifications,
                isPreviewMode = true,
                previewChargingOverlay = previewChargingOverlay,
                onDismissNotification = { LockScreenNotificationRepository.dismissNotification(it) },
                onClearAllNotifications = { LockScreenNotificationRepository.clearAllClearable() },
                onUpdateWidgetItem = { updatedItem ->
                    val nextList = preferences.parsedStudioWidgets.map { existing ->
                        if (existing.type == updatedItem.type) updatedItem.sanitized() else existing
                    }
                    onSetStudioWidgets(nextList)
                },
                onDismissToUnlock = {
                    previewChargingOverlay = false
                    showFullPreview = false
                }
            )
        }
    }
}

@Composable
private fun PermissionChecklistRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    rowTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(rowTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isGranted) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Granted",
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            FilledTonalButton(
                onClick = onClick,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LockCredentialSetupCard(
    preferences: UserPreferencesDoc,
    onSaveCredential: (LockCredentialType, String) -> Unit,
    onClearCredential: () -> Unit
) {
    val haptics = rememberStudioHaptics()
    val configuredType = preferences.parsedLockCredentialType
    var selectedType by remember(configuredType) { mutableStateOf(configuredType) }
    var isEditing by remember(configuredType, preferences.hasLockCredential) {
        mutableStateOf(configuredType != LockCredentialType.NONE && !preferences.hasLockCredential)
    }
    var secretInput by remember { mutableStateOf("") }
    var confirmInput by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    TactileSurface(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lockscreen_credential_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Backdrop Lock Protection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (preferences.hasLockCredential) {
                            "${configuredType.label} lock enabled for Backdrop overlay"
                        } else {
                            "Optional PIN or Password before dismissing Backdrop Lock"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Prominent Amber Warning Banner
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFB74D).copy(alpha = 0.14f),
                border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lockscreen_security_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Visual convenience lock only: This PIN/Password is NOT as secure as your native Android lock screen, hardware keystore, or biometrics. Always keep your device's system lock enabled for full protection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Off / PIN / Password Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LockCredentialType.entries.forEach { type ->
                    StudioOptionPill(
                        label = type.label,
                        selected = selectedType == type,
                        onClick = {
                            haptics.performTick(20L)
                            validationError = null
                            secretInput = ""
                            confirmInput = ""
                            selectedType = type
                            if (type == LockCredentialType.NONE) {
                                isEditing = false
                                if (preferences.hasLockCredential) {
                                    onClearCredential()
                                }
                            } else {
                                isEditing = !(preferences.hasLockCredential && configuredType == type)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("lock_cred_type_${type.name}")
                    )
                }
            }

            if (selectedType != LockCredentialType.NONE) {
                if (preferences.hasLockCredential && configuredType == selectedType && !isEditing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                secretInput = ""
                                confirmInput = ""
                                validationError = null
                                isEditing = true
                            },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lock_cred_change_button")
                        ) {
                            Text("Change ${selectedType.label}")
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.performTick(24L)
                                selectedType = LockCredentialType.NONE
                                isEditing = false
                                onClearCredential()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lock_cred_clear_button")
                        ) {
                            Text("Remove")
                        }
                    }
                } else {
                    val isPin = selectedType == LockCredentialType.PIN
                    OutlinedTextField(
                        value = secretInput,
                        onValueChange = { raw ->
                            val clean = if (isPin) raw.filter { it.isDigit() }.take(8) else raw.take(32)
                            secretInput = clean
                            validationError = null
                        },
                        label = { Text(if (isPin) "Enter PIN (4–8 digits)" else "Enter Password (min 4 chars)") },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password
                        ),
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showSecret) "Hide" else "Show"
                                )
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lock_cred_input")
                    )

                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = { raw ->
                            val clean = if (isPin) raw.filter { it.isDigit() }.take(8) else raw.take(32)
                            confirmInput = clean
                            validationError = null
                        },
                        label = { Text("Confirm ${selectedType.label}") },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lock_cred_confirm_input")
                    )

                    if (!validationError.isNullOrBlank()) {
                        Text(
                            text = validationError.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("lock_cred_validation_error")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val minValid = if (isPin) {
                                    secretInput.length in 4..8 && secretInput.all { it.isDigit() }
                                } else {
                                    secretInput.length >= 4
                                }
                                when {
                                    !minValid -> {
                                        validationError = if (isPin) {
                                            "PIN must be 4 to 8 digits."
                                        } else {
                                            "Password must be at least 4 characters."
                                        }
                                    }
                                    secretInput != confirmInput -> {
                                        validationError = "${selectedType.label} entries do not match."
                                    }
                                    else -> {
                                        haptics.performConfirm()
                                        onSaveCredential(selectedType, secretInput)
                                        secretInput = ""
                                        confirmInput = ""
                                        isEditing = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lock_cred_save_button")
                        ) {
                            Text("Save ${selectedType.label}")
                        }
                        if (preferences.hasLockCredential) {
                            OutlinedButton(
                                onClick = {
                                    secretInput = ""
                                    confirmInput = ""
                                    validationError = null
                                    selectedType = configuredType
                                    isEditing = false
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }
    }
}
