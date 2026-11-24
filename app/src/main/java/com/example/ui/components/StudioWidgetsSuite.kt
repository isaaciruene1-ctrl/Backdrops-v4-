package com.example.ui.components

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.catalog.WallpaperCatalog
import com.example.data.model.StudioWidgetItem
import com.example.data.model.StudioWidgetType
import com.example.data.model.UiStyleMode
import com.example.engine.notifications.BackdropNotificationListenerService
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalThemeCustomization
import com.example.ui.theme.LocalUiStyleMode
import com.example.ui.theme.rememberStudioHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

@Immutable
data class CalendarEventEntry(
    val id: Long,
    val title: String,
    val timeLabel: String
)

@Immutable
data class ActiveMediaPlaybackState(
    val hasSession: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "No active media",
    val artist: String = "Tap play in your music app",
    val albumArt: Bitmap? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val controller: MediaController? = null
)

@Immutable
data class DeviceDiagnosticsSummary(
    val ramUsedPercent: Int,
    val ramSummary: String,
    val storageUsedPercent: Int,
    val storageFreeGb: Int,
    val tierLabel: String
)

object StudioWidgetSystemBridge {

    fun hasCalendarPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun loadUpcomingCalendarEvents(context: Context, limit: Int = 3): List<CalendarEventEntry> {
        if (!hasCalendarPermission(context)) return emptyList()
        return runCatching {
            val now = System.currentTimeMillis()
            val end = now + 7L * 24L * 60L * 60L * 1000L
            val results = mutableListOf<CalendarEventEntry>()
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val args = arrayOf(now.toString(), end.toString())
            val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext() && results.size < limit) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else results.size.toLong()
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty().ifBlank { "Event" } else "Event"
                    val startMs = if (startIdx >= 0) cursor.getLong(startIdx) else now
                    results += CalendarEventEntry(
                        id = id,
                        title = title,
                        timeLabel = fmt.format(Date(startMs))
                    )
                }
            }
            results
        }.getOrDefault(emptyList())
    }

    fun readActiveMediaState(context: Context): ActiveMediaPlaybackState {
        return runCatching {
            val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return ActiveMediaPlaybackState()
            val component = ComponentName(context, BackdropNotificationListenerService::class.java)
            val controllers = mgr.getActiveSessions(component)
            val active = controllers.firstOrNull { c ->
                val st = c.playbackState?.state
                st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING
            } ?: controllers.firstOrNull() ?: return ActiveMediaPlaybackState()
            val meta = active.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Playing Media" }
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().ifBlank { active.packageName } }
            val isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING
            val albumArt = extractBoundedAlbumArt(meta)
            val durationMs = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
            val positionMs = (active.playbackState?.position ?: 0L).coerceAtLeast(0L)
            ActiveMediaPlaybackState(
                hasSession = true,
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                albumArt = albumArt,
                durationMs = durationMs,
                positionMs = if (durationMs > 0L) positionMs.coerceAtMost(durationMs) else positionMs,
                controller = active
            )
        }.getOrDefault(ActiveMediaPlaybackState())
    }

    internal fun extractBoundedAlbumArt(meta: MediaMetadata?): Bitmap? {
        if (meta == null) return null
        val raw = runCatching {
            meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull() ?: return null
        if (raw.isRecycled || raw.width <= 0 || raw.height <= 0) return null
        val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && raw.config == Bitmap.Config.HARDWARE) {
            runCatching { raw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
        } else {
            raw
        }
        val maxEdge = max(software.width, software.height)
        return if (maxEdge > 1024) {
            val scale = 1024f / maxEdge.toFloat()
            val dstW = (software.width * scale).roundToInt().coerceAtLeast(1)
            val dstH = (software.height * scale).roundToInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(software, dstW, dstH, true) }.getOrDefault(software)
        } else {
            software
        }
    }

    fun readDiagnostics(context: Context, tierLabel: String): DeviceDiagnosticsSummary {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }
        val totalMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1024L)
        val availMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(128L)
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
        val ramPct = ((usedMb * 100f) / totalMb).roundToInt().coerceIn(5, 98)

        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val totalBytes = stat?.totalBytes ?: (64L * 1024 * 1024 * 1024)
        val availBytes = stat?.availableBytes ?: (24L * 1024 * 1024 * 1024)
        val usedPct = (((totalBytes - availBytes).toFloat() / totalBytes.coerceAtLeast(1L)) * 100f)
            .roundToInt()
            .coerceIn(4, 98)
        val freeGb = (availBytes / (1024L * 1024 * 1024)).toInt().coerceAtLeast(0)
        return DeviceDiagnosticsSummary(
            ramUsedPercent = ramPct,
            ramSummary = "${(usedMb / 1024f).let { String.format(Locale.US, "%.1f", it) }}/${(totalMb / 1024f).roundToInt()}GB",
            storageUsedPercent = usedPct,
            storageFreeGb = freeGb,
            tierLabel = tierLabel
        )
    }
}

private enum class WidgetResizeCorner(val tag: String, val dxSign: Float, val dySign: Float) {
    TOP_START("tl", -1f, -1f),
    TOP_END("tr", 1f, -1f),
    BOTTOM_START("bl", -1f, 1f),
    BOTTOM_END("br", 1f, 1f)
}

@Composable
fun StudioWidgetsLockStrip(
    widgets: List<StudioWidgetItem>,
    telemetry: ClockWidgetTelemetry,
    isCharging: Boolean,
    is24HourFormat: Boolean,
    isResizeMode: Boolean = false,
    onUpdateWidgetItem: ((StudioWidgetItem) -> Unit)? = null,
    onExpandMediaPlayer: ((ActiveMediaPlaybackState) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val activeWidgets = remember(widgets) { widgets.filter { it.enabled }.take(4) }
    if (activeWidgets.isEmpty()) return

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lockscreen_widgets_strip"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(activeWidgets, key = { it.type.name }) { item ->
            StudioWidgetCard(
                item = item,
                telemetry = telemetry,
                isCharging = isCharging,
                is24HourFormat = is24HourFormat,
                isResizeMode = isResizeMode,
                onUpdateWidgetItem = onUpdateWidgetItem,
                onExpandMediaPlayer = onExpandMediaPlayer,
                onDarkSurface = true
            )
        }
    }
}

@Composable
fun StudioWidgetCard(
    item: StudioWidgetItem,
    telemetry: ClockWidgetTelemetry,
    isCharging: Boolean = false,
    is24HourFormat: Boolean = true,
    isResizeMode: Boolean = false,
    onUpdateWidgetItem: ((StudioWidgetItem) -> Unit)? = null,
    onExpandMediaPlayer: ((ActiveMediaPlaybackState) -> Unit)? = null,
    onDarkSurface: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val uiStyle = LocalUiStyleMode.current
    val customTokens = LocalThemeCustomization.current
    val haptics = rememberStudioHaptics()
    val isGlass = uiStyle == UiStyleMode.FROSTED_GLASS_OS || onDarkSurface

    val cardBg = if (onDarkSurface) {
        Color(0xFF101522).copy(alpha = if (uiStyle == UiStyleMode.FROSTED_GLASS_OS) 0.56f else 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val cardFg = if (onDarkSurface) Color.White else MaterialTheme.colorScheme.onSurface
    val cardSub = if (onDarkSurface) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.type) {
        if (item.type == StudioWidgetType.DIGITAL_CLOCK || item.type == StudioWidgetType.ANALOG_CLOCK) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    var calendarGranted by remember { mutableStateOf(StudioWidgetSystemBridge.hasCalendarPermission(context)) }
    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarGranted = granted
    }

    val safeWScale = item.widthScale.coerceIn(0.65f, 1.65f)
    val safeHScale = item.heightScale.coerceIn(0.65f, 1.65f)
    val baseWidthDp = when (item.type) {
        StudioWidgetType.MUSIC_PLAYER,
        StudioWidgetType.CALENDAR,
        StudioWidgetType.QUICK_INFO -> 228f
        else -> 166f
    }
    val baseHeightDp = 114f
    val resolvedWidthDp = (baseWidthDp * safeWScale).coerceIn(112f, 360f).dp
    val resolvedHeightDp = (baseHeightDp * safeHScale).coerceIn(78f, 196f).dp
    val baseWidthPx = with(density) { baseWidthDp.dp.toPx().coerceAtLeast(1f) }
    val baseHeightPx = with(density) { baseHeightDp.dp.toPx().coerceAtLeast(1f) }

    val latestItem by rememberUpdatedState(item)
    val latestOnUpdateWidget by rememberUpdatedState(onUpdateWidgetItem)

    Box(
        modifier = modifier
            .then(
                if (isResizeMode && onUpdateWidgetItem != null) {
                    Modifier.padding(8.dp)
                } else {
                    Modifier
                }
            )
    ) {
        Surface(
            color = cardBg,
            contentColor = cardFg,
            shape = RoundedCornerShape(22.dp),
            border = when {
                isResizeMode -> BorderStroke(1.6.dp, accent.copy(alpha = 0.92f))
                isGlass -> BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
                else -> null
            },
            tonalElevation = if (onDarkSurface) 0.dp else 3.dp,
            modifier = Modifier
                .width(resolvedWidthDp)
                .height(resolvedHeightDp)
                .testTag("studio_widget_${item.type.name.lowercase()}")
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                when (item.type) {
                    StudioWidgetType.DIGITAL_CLOCK -> {
                        val d = Date(nowMs)
                        val hhmm = SimpleDateFormat(if (is24HourFormat) "HH:mm" else "h:mm a", Locale.getDefault()).format(d)
                        val sec = SimpleDateFormat(":ss", Locale.getDefault()).format(d)
                        val date = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(d)
                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                                Text(date, style = MaterialTheme.typography.labelSmall, color = cardSub, maxLines = 1)
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = hhmm,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 26.sp,
                                    color = cardFg
                                )
                                Text(
                                    text = sec,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = accent,
                                    modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                                )
                            }
                        }
                    }

                    StudioWidgetType.ANALOG_CLOCK -> {
                        val cal = remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs } }
                        val hr = cal.get(Calendar.HOUR)
                        val min = cal.get(Calendar.MINUTE)
                        val sec = cal.get(Calendar.SECOND)
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Canvas(modifier = Modifier.size((72f * minOf(safeWScale, safeHScale)).coerceIn(48f, 110f).dp)) {
                                val r = size.minDimension * 0.45f
                                val c = Offset(size.width / 2f, size.height / 2f)
                                drawCircle(color = cardSub.copy(alpha = 0.4f), radius = r, center = c, style = Stroke(2.dp.toPx()))
                                val hAngle = ((hr + min / 60f) * 30f - 90f) * PI / 180.0
                                val mAngle = (min * 6f - 90f) * PI / 180.0
                                val sAngle = (sec * 6f - 90f) * PI / 180.0
                                drawLine(cardFg, c, Offset(c.x + (r * 0.52f * cos(hAngle)).toFloat(), c.y + (r * 0.52f * sin(hAngle)).toFloat()), 3.dp.toPx(), StrokeCap.Round)
                                drawLine(cardFg, c, Offset(c.x + (r * 0.78f * cos(mAngle)).toFloat(), c.y + (r * 0.78f * sin(mAngle)).toFloat()), 2.2.dp.toPx(), StrokeCap.Round)
                                drawLine(accent, c, Offset(c.x + (r * 0.84f * cos(sAngle)).toFloat(), c.y + (r * 0.84f * sin(sAngle)).toFloat()), 1.4.dp.toPx(), StrokeCap.Round)
                                drawCircle(color = accent, radius = 3.dp.toPx(), center = c)
                            }
                            Column {
                                Text("Chrono", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cardFg)
                                Text("${cal.get(Calendar.DAY_OF_MONTH)} ${SimpleDateFormat("MMM", Locale.getDefault()).format(Date(nowMs))}", style = MaterialTheme.typography.labelSmall, color = cardSub)
                            }
                        }
                    }

                    StudioWidgetType.WEATHER -> {
                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Weather", style = MaterialTheme.typography.labelSmall, color = cardSub)
                                Icon(Icons.Rounded.WbSunny, contentDescription = "Weather condition", tint = Color(0xFFFFCA62), modifier = Modifier.size(20.dp))
                            }
                            Text(
                                text = telemetry.weatherText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = cardFg,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (telemetry.isWeatherAvailable) "Open-Meteo Live" else "Sync in Settings",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent
                            )
                        }
                    }

                    StudioWidgetType.CALENDAR -> {
                        val events = remember(calendarGranted, nowMs / 60_000L) {
                            if (calendarGranted) StudioWidgetSystemBridge.loadUpcomingCalendarEvents(context, limit = 2)
                            else emptyList()
                        }
                        val cal = Calendar.getInstance()
                        val dayStr = SimpleDateFormat("EEEE • MMM d", Locale.getDefault()).format(cal.time)
                        Column(
                            verticalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(enabled = !calendarGranted && !isResizeMode) {
                                    calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                                Text(dayStr, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cardFg, maxLines = 1)
                            }
                            if (!calendarGranted) {
                                Text(
                                    text = "Tap to sync device calendar events",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accent
                                )
                            } else if (events.isEmpty()) {
                                Text(
                                    text = "No upcoming events in the next 7 days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cardSub,
                                    maxLines = 2
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    events.forEach { ev ->
                                        Text(
                                            text = "${ev.timeLabel} • ${ev.title}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = cardFg,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    StudioWidgetType.BATTERY -> {
                        val pct = telemetry.batteryPercent.coerceIn(0, 100)
                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(if (isCharging) "Charging" else "Battery", style = MaterialTheme.typography.labelSmall, color = cardSub)
                                Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = if (isCharging) Color(0xFF5CF28E) else accent, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = cardFg
                            )
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                                color = if (isCharging) Color(0xFF5CF28E) else accent,
                                trackColor = cardSub.copy(alpha = 0.25f)
                            )
                        }
                    }

                    StudioWidgetType.MUSIC_PLAYER -> {
                        var mediaState by remember { mutableStateOf(StudioWidgetSystemBridge.readActiveMediaState(context)) }
                        LaunchedEffect(isResizeMode) {
                            while (true) {
                                mediaState = StudioWidgetSystemBridge.readActiveMediaState(context)
                                delay(1000L)
                            }
                        }
                        val validArt = mediaState.albumArt?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
                        Column(
                            verticalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(enabled = !isResizeMode && onExpandMediaPlayer != null) {
                                    haptics.performTick(16L)
                                    onExpandMediaPlayer?.invoke(mediaState)
                                }
                                .testTag("studio_widget_music_expand_area")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (validArt != null) {
                                    val artImg = remember(validArt) { validArt.asImageBitmap() }
                                    Image(
                                        bitmap = artImg,
                                        contentDescription = "Album art for ${mediaState.title}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    Surface(
                                        color = accent.copy(alpha = 0.24f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mediaState.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cardFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(mediaState.artist, style = MaterialTheme.typography.labelSmall, color = cardSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.performTick(16L)
                                        mediaState.controller?.transportControls?.skipToPrevious()
                                        mediaState = StudioWidgetSystemBridge.readActiveMediaState(context)
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", tint = cardFg, modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    onClick = {
                                        haptics.performTick(20L)
                                        val tc = mediaState.controller?.transportControls
                                        if (mediaState.isPlaying) tc?.pause() else tc?.play()
                                        mediaState = StudioWidgetSystemBridge.readActiveMediaState(context)
                                    },
                                    shape = CircleShape,
                                    color = accent,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (mediaState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = if (mediaState.isPlaying) "Pause" else "Play",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        haptics.performTick(16L)
                                        mediaState.controller?.transportControls?.skipToNext()
                                        mediaState = StudioWidgetSystemBridge.readActiveMediaState(context)
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", tint = cardFg, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    StudioWidgetType.QUICK_INFO -> {
                        val dateStr = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())
                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                            Text("Quick Telemetry", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "$dateStr • ${telemetry.weatherText}",
                                style = MaterialTheme.typography.labelLarge,
                                color = cardFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Battery ${telemetry.batteryPercent}% • ${customTokens.liquidGlassTier.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = cardSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    StudioWidgetType.PHOTO_FRAME -> {
                        val customUri = item.customPhotoUri
                        val fallbackWp = WallpaperCatalog.allWallpapers.first()
                        val customBmp by produceState<Bitmap?>(initialValue = null, key1 = customUri) {
                            if (customUri.isNotBlank()) {
                                value = withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.contentResolver.openInputStream(Uri.parse(customUri))?.use {
                                            BitmapFactory.decodeStream(it)
                                        }
                                    }.getOrNull()
                                }
                            }
                        }
                        val validPhoto = customBmp?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
                        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
                            if (validPhoto != null) {
                                val photoImg = remember(validPhoto) { validPhoto.asImageBitmap() }
                                Image(
                                    bitmap = photoImg,
                                    contentDescription = "Photo Frame",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                WallpaperAsyncImage(
                                    wallpaper = fallbackWp,
                                    useFullRes = false,
                                    contentDescription = "Curated Photo Frame",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    StudioWidgetType.SYSTEM_INFO -> {
                        val diag = remember(customTokens.liquidGlassTier) {
                            StudioWidgetSystemBridge.readDiagnostics(context, customTokens.liquidGlassTier.label)
                        }
                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Rounded.Memory, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                                Text("System", style = MaterialTheme.typography.labelSmall, color = cardSub)
                            }
                            Text("RAM ${diag.ramUsedPercent}% (${diag.ramSummary})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cardFg, maxLines = 1)
                            Text("${diag.storageFreeGb}GB free • ${diag.tierLabel}", style = MaterialTheme.typography.labelSmall, color = cardSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (isResizeMode && onUpdateWidgetItem != null) {
            // Subtle center size & reset badge when widget dimensions differ from 1.0x
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${(safeWScale * 100).roundToInt()}%×${(safeHScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 10.sp
                )
                if (kotlin.math.abs(safeWScale - 1f) > 0.03f || kotlin.math.abs(safeHScale - 1f) > 0.03f) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = "Reset ${item.type.title} size",
                        tint = Color.White,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable {
                                haptics.performSnapDetent()
                                onUpdateWidgetItem(item.copy(widthScale = 1f, heightScale = 1f))
                            }
                            .testTag("widget_resize_reset_${item.type.name.lowercase()}")
                    )
                }
            }

            // 4 direct corner drag handles (TL / TR / BL / BR) with 48dp accessible hit targets
            WidgetResizeCorner.entries.forEach { corner ->
                val alignment = when (corner) {
                    WidgetResizeCorner.TOP_START -> Alignment.TopStart
                    WidgetResizeCorner.TOP_END -> Alignment.TopEnd
                    WidgetResizeCorner.BOTTOM_START -> Alignment.BottomStart
                    WidgetResizeCorner.BOTTOM_END -> Alignment.BottomEnd
                }
                WidgetCornerGripHandle(
                    corner = corner,
                    uiStyle = uiStyle,
                    useClassic = customTokens.useClassicClockHandles,
                    modifier = Modifier
                        .align(alignment)
                        .size(48.dp)
                        .pointerInput(item.type, isResizeMode) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val current = latestItem
                                val nextW = (current.widthScale + (dragAmount.x * corner.dxSign) / baseWidthPx)
                                    .coerceIn(0.65f, 1.65f)
                                val nextH = (current.heightScale + (dragAmount.y * corner.dySign) / baseHeightPx)
                                    .coerceIn(0.65f, 1.65f)
                                latestOnUpdateWidget?.invoke(
                                    current.copy(widthScale = nextW, heightScale = nextH).sanitized()
                                )
                            }
                        }
                        .testTag("widget_resize_handle_${item.type.name.lowercase()}_${corner.tag}")
                )
            }
        }
    }
}

@Composable
private fun WidgetCornerGripHandle(
    corner: WidgetResizeCorner,
    uiStyle: UiStyleMode,
    useClassic: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            useClassic -> {
                Canvas(modifier = Modifier.size(20.dp)) {
                    drawCircle(color = surface, radius = size.minDimension * 0.42f)
                    drawCircle(color = primary, radius = size.minDimension * 0.42f, style = Stroke(2.dp.toPx()))
                }
            }
            uiStyle == UiStyleMode.FROSTED_GLASS_OS -> {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val startAngle = when (corner) {
                        WidgetResizeCorner.TOP_START -> 180f
                        WidgetResizeCorner.TOP_END -> 270f
                        WidgetResizeCorner.BOTTOM_END -> 0f
                        WidgetResizeCorner.BOTTOM_START -> 90f
                    }
                    drawArc(
                        brush = Brush.linearGradient(listOf(Color.White, primary.copy(alpha = 0.72f))),
                        startAngle = startAngle,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            else -> {
                Canvas(modifier = Modifier.size(20.dp)) {
                    val m = 3.dp.toPx()
                    val arm = 8.dp.toPx()
                    val p = Path().apply {
                        when (corner) {
                            WidgetResizeCorner.TOP_START -> {
                                moveTo(m, m + arm); lineTo(m, m); lineTo(m + arm, m)
                            }
                            WidgetResizeCorner.TOP_END -> {
                                moveTo(size.width - m - arm, m); lineTo(size.width - m, m); lineTo(size.width - m, m + arm)
                            }
                            WidgetResizeCorner.BOTTOM_START -> {
                                moveTo(m, size.height - m - arm); lineTo(m, size.height - m); lineTo(m + arm, size.height - m)
                            }
                            WidgetResizeCorner.BOTTOM_END -> {
                                moveTo(size.width - m - arm, size.height - m); lineTo(size.width - m, size.height - m); lineTo(size.width - m, size.height - m - arm)
                            }
                        }
                    }
                    drawPath(p, surface, style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
                    drawPath(p, primary, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
                }
            }
        }
    }
}

/**
 * Interactive configurator for the 9-Widget Suite inside Lock Screen Mode / Studio.
 */
@Composable
fun StudioWidgetsConfiguratorCard(
    widgets: List<StudioWidgetItem>,
    onUpdateWidgets: (List<StudioWidgetItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = rememberStudioHaptics()
    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* UI automatically refreshes when granted */ }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val next = widgets.map {
                if (it.type == StudioWidgetType.PHOTO_FRAME) it.copy(customPhotoUri = uri.toString(), enabled = true)
                else it
            }
            onUpdateWidgets(next)
        }
    }

    TactileSurface(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("studio_widgets_configurator_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Style, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Studio & Lock Screen Widgets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Enable up to 4 active widgets on Lock Screen or add to your Home Screen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            widgets.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("widget_toggle_row_${item.type.name}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("${item.type.title} (${item.type.gridSpanLabel})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(item.type.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.type == StudioWidgetType.PHOTO_FRAME && item.enabled) {
                        FilledTonalButton(
                            onClick = {
                                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Photo", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Switch(
                        checked = item.enabled,
                        onCheckedChange = { enable ->
                            haptics.performTick(18L)
                            if (enable && item.type == StudioWidgetType.CALENDAR && !StudioWidgetSystemBridge.hasCalendarPermission(context)) {
                                calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                            onUpdateWidgets(widgets.map {
                                if (it.type == item.type) it.copy(enabled = enable) else it
                            })
                        },
                        modifier = Modifier.testTag("widget_switch_${item.type.name}")
                    )
                }
            }
        }
    }
}
