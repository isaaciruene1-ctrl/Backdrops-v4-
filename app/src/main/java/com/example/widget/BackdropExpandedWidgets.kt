package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.BatteryManager
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.catalog.WallpaperCatalog
import com.example.data.media.WallpaperMediaService
import com.example.data.media.WallpaperTarget
import com.example.data.model.DiscoveryCollection
import com.example.data.model.StudioWidgetType
import com.example.data.model.UiStyleMode
import com.example.data.model.UserPreferencesDoc
import com.example.data.repository.GuestLocalUserStore
import com.example.data.weather.OpenMeteoWeatherRepository
import com.example.data.weather.WeatherTelemetryState
import com.example.engine.performance.ZeroLagPerformanceEngine
import com.example.ui.components.StudioWidgetSystemBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object BackdropExpandedWidgetContract {
    const val ACTION_WEATHER_REFRESH = "com.example.widget.ACTION_WEATHER_REFRESH"
    const val ACTION_MUSIC_PREV = "com.example.widget.ACTION_MUSIC_PREV"
    const val ACTION_MUSIC_PLAY_PAUSE = "com.example.widget.ACTION_MUSIC_PLAY_PAUSE"
    const val ACTION_MUSIC_NEXT = "com.example.widget.ACTION_MUSIC_NEXT"

    val ALL_EXPANDED_PROVIDERS: List<Class<out AppWidgetProvider>> = listOf(
        BackdropDigitalClockWidgetProvider::class.java,
        BackdropAnalogClockWidgetProvider::class.java,
        BackdropWeatherWidgetProvider::class.java,
        BackdropCalendarWidgetProvider::class.java,
        BackdropBatteryWidgetProvider::class.java,
        BackdropMusicWidgetProvider::class.java,
        BackdropQuickInfoWidgetProvider::class.java,
        BackdropPhotoFrameWidgetProvider::class.java,
        BackdropSystemInfoWidgetProvider::class.java
    )
}

abstract class BaseExpandedHomeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val appContext = context.applicationContext
        appWidgetIds.forEach { widgetId ->
            val views = buildViews(appContext, widgetId)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    protected abstract fun buildViews(context: Context, appWidgetId: Int): RemoteViews

    protected fun applySurfaceTheme(views: RemoteViews, prefs: UserPreferencesDoc) {
        val bgRes = if (prefs.uiStyle == UiStyleMode.FROSTED_GLASS_OS.name) {
            R.drawable.widget_glass_surface_bg
        } else {
            R.drawable.widget_m3_surface_bg
        }
        views.setInt(R.id.widget_container, "setBackgroundResource", bgRes)
    }

    protected fun launchAppPendingIntent(
        context: Context,
        requestCode: Int,
        wallpaperId: String = ""
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (wallpaperId.isNotBlank()) {
                putExtra(BackdropWidgetContract.EXTRA_OPEN_STUDIO, true)
                putExtra(BackdropWidgetContract.EXTRA_WALLPAPER_ID, wallpaperId)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    protected fun broadcastPendingIntent(
        context: Context,
        requestCode: Int,
        providerClass: Class<out AppWidgetProvider>,
        actionName: String
    ): PendingIntent {
        val intent = Intent(context, providerClass).apply {
            action = actionName
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    protected fun runAsyncAction(block: suspend () -> Unit) {
        val pending = runCatching { goAsync() }.getOrNull()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block()
            } finally {
                pending?.finish()
            }
        }
    }
}

class BackdropDigitalClockWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_digital_clock)
        applySurfaceTheme(views, prefs)
        val tierLabel = if (prefs.uiStyle == UiStyleMode.FROSTED_GLASS_OS.name) "GLASS" else "M3"
        views.setTextViewText(R.id.widget_digital_clock_badge, tierLabel)
        views.setOnClickPendingIntent(
            R.id.widget_container,
            launchAppPendingIntent(context, 4100 + appWidgetId, prefs.activeLockWallpaperId)
        )
        return views
    }
}

class BackdropAnalogClockWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_analog_clock)
        applySurfaceTheme(views, prefs)
        views.setImageViewBitmap(R.id.widget_analog_dial, renderAnalogDialBitmap(prefs.customAccentHex))
        val dateText = SimpleDateFormat("EEE d", Locale.getDefault()).format(Date()).uppercase()
        views.setTextViewText(R.id.widget_analog_date, dateText)
        views.setOnClickPendingIntent(
            R.id.widget_container,
            launchAppPendingIntent(context, 4200 + appWidgetId, prefs.activeLockWallpaperId)
        )
        return views
    }

    private fun renderAnalogDialBitmap(accentHex: Long): Bitmap {
        val size = 220
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.43f

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(cx, cy, r, ringPaint)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 12) {
            val angle = (i * 30.0 - 90.0) * PI / 180.0
            val inner = if (i % 3 == 0) r * 0.78f else r * 0.86f
            canvas.drawLine(
                cx + (cos(angle) * inner).toFloat(),
                cy + (sin(angle) * inner).toFloat(),
                cx + (cos(angle) * (r * 0.94f)).toFloat(),
                cy + (sin(angle) * (r * 0.94f)).toFloat(),
                tickPaint
            )
        }

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR)
        val minute = cal.get(Calendar.MINUTE)
        val hourAngle = ((hour + minute / 60f) * 30f - 90f) * PI / 180.0
        val minAngle = (minute * 6f - 90f) * PI / 180.0

        val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(
            cx, cy,
            cx + (cos(hourAngle) * r * 0.52f).toFloat(),
            cy + (sin(hourAngle) * r * 0.52f).toFloat(),
            hourPaint
        )

        val minPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(
                ((accentHex shr 16) and 0xFF).toInt(),
                ((accentHex shr 8) and 0xFF).toInt(),
                (accentHex and 0xFF).toInt()
            )
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(
            cx, cy,
            cx + (cos(minAngle) * r * 0.76f).toFloat(),
            cy + (sin(minAngle) * r * 0.76f).toFloat(),
            minPaint
        )

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy, 7f, centerPaint)
        return bmp
    }
}

class BackdropWeatherWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == BackdropExpandedWidgetContract.ACTION_WEATHER_REFRESH) {
            val appContext = context.applicationContext
            runAsyncAction {
                val prefs = GuestLocalUserStore.getInstance(appContext).getCurrentPreferencesSync()
                if (prefs.weatherUseLocation) {
                    OpenMeteoWeatherRepository.refreshByDeviceCoarseLocation(appContext)
                } else if (prefs.weatherCity.isNotBlank()) {
                    OpenMeteoWeatherRepository.refreshByCity(prefs.weatherCity)
                }
                BackdropWidgetContract.refreshAllWidgets(appContext)
            }
        }
    }

    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_weather)
        applySurfaceTheme(views, prefs)

        when (val w = OpenMeteoWeatherRepository.state.value) {
            is WeatherTelemetryState.Ready -> {
                views.setTextViewText(R.id.widget_weather_city, w.cityLabel.ifBlank { "LOCAL" }.uppercase())
                views.setTextViewText(R.id.widget_weather_temp, "${w.temperatureC}°")
                views.setTextViewText(R.id.widget_weather_condition, "${w.conditionLabel} • Tap to refresh")
            }
            is WeatherTelemetryState.Loading -> {
                views.setTextViewText(R.id.widget_weather_city, w.sourceLabel.uppercase())
                views.setTextViewText(R.id.widget_weather_temp, "--°")
                views.setTextViewText(R.id.widget_weather_condition, "Updating forecast...")
            }
            else -> {
                val city = prefs.weatherCity.ifBlank { "SET CITY IN SETTINGS" }.uppercase()
                views.setTextViewText(R.id.widget_weather_city, city)
                views.setTextViewText(R.id.widget_weather_temp, "--°")
                views.setTextViewText(R.id.widget_weather_condition, "Tap to refresh")
            }
        }

        views.setOnClickPendingIntent(
            R.id.widget_container,
            broadcastPendingIntent(
                context = context,
                requestCode = 4300 + appWidgetId,
                providerClass = BackdropWeatherWidgetProvider::class.java,
                actionName = BackdropExpandedWidgetContract.ACTION_WEATHER_REFRESH
            )
        )
        return views
    }
}

class BackdropCalendarWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_calendar)
        applySurfaceTheme(views, prefs)

        val now = Date()
        views.setTextViewText(
            R.id.widget_calendar_month,
            SimpleDateFormat("MMMM d", Locale.getDefault()).format(now).uppercase()
        )
        views.setTextViewText(
            R.id.widget_calendar_day,
            SimpleDateFormat("EEE", Locale.getDefault()).format(now).uppercase()
        )

        if (!StudioWidgetSystemBridge.hasCalendarPermission(context)) {
            views.setTextViewText(R.id.widget_calendar_event_1, "Calendar access paused")
            views.setTextViewText(R.id.widget_calendar_event_2, "Open Backdrops Lock Screen Mode to grant access")
        } else {
            val events = StudioWidgetSystemBridge.loadUpcomingCalendarEvents(context, limit = 2)
            if (events.isEmpty()) {
                views.setTextViewText(R.id.widget_calendar_event_1, "Clear schedule ahead")
                views.setTextViewText(R.id.widget_calendar_event_2, "No upcoming events in the next 7 days")
            } else {
                val first = events[0]
                views.setTextViewText(R.id.widget_calendar_event_1, "${first.title} • ${first.timeLabel}")
                val second = events.getOrNull(1)
                views.setTextViewText(
                    R.id.widget_calendar_event_2,
                    second?.let { "${it.title} • ${it.timeLabel}" } ?: "Next 7 days synced"
                )
            }
        }

        views.setOnClickPendingIntent(
            R.id.widget_container,
            launchAppPendingIntent(context, 4400 + appWidgetId)
        )
        return views
    }
}

class BackdropBatteryWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_battery)
        applySurfaceTheme(views, prefs)

        val pct = OpenMeteoWeatherRepository.readBatteryPercent(context)
        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val mv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3900) ?: 3900
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280

        views.setTextViewText(R.id.widget_battery_percent, "$pct%")
        views.setTextViewText(
            R.id.widget_battery_status,
            if (isCharging) "Charging • ${tempTenths / 10f}°C" else "Active • ${tempTenths / 10f}°C"
        )
        views.setTextViewText(
            R.id.widget_battery_chip,
            String.format(Locale.US, "%.1fV", mv / 1000f)
        )
        views.setOnClickPendingIntent(
            R.id.widget_container,
            launchAppPendingIntent(context, 4500 + appWidgetId)
        )
        return views
    }
}

class BackdropMusicWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action == BackdropExpandedWidgetContract.ACTION_MUSIC_PREV ||
            action == BackdropExpandedWidgetContract.ACTION_MUSIC_PLAY_PAUSE ||
            action == BackdropExpandedWidgetContract.ACTION_MUSIC_NEXT
        ) {
            val appContext = context.applicationContext
            runAsyncAction {
                val media = StudioWidgetSystemBridge.readActiveMediaState(appContext)
                val controls = media.controller?.transportControls
                when (action) {
                    BackdropExpandedWidgetContract.ACTION_MUSIC_PREV -> controls?.skipToPrevious()
                    BackdropExpandedWidgetContract.ACTION_MUSIC_NEXT -> controls?.skipToNext()
                    BackdropExpandedWidgetContract.ACTION_MUSIC_PLAY_PAUSE -> {
                        if (media.isPlaying) controls?.pause() else controls?.play()
                    }
                }
                BackdropWidgetContract.refreshAllWidgets(appContext)
            }
        }
    }

    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_music)
        applySurfaceTheme(views, prefs)

        val media = StudioWidgetSystemBridge.readActiveMediaState(context)
        views.setTextViewText(R.id.widget_music_title, media.title)
        views.setTextViewText(R.id.widget_music_artist, media.artist)
        views.setImageViewResource(
            R.id.widget_music_play_pause,
            if (media.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

        views.setOnClickPendingIntent(
            R.id.widget_music_header,
            launchAppPendingIntent(context, 4600 + appWidgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_music_prev,
            broadcastPendingIntent(
                context,
                4610 + appWidgetId,
                BackdropMusicWidgetProvider::class.java,
                BackdropExpandedWidgetContract.ACTION_MUSIC_PREV
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_music_play_pause,
            broadcastPendingIntent(
                context,
                4620 + appWidgetId,
                BackdropMusicWidgetProvider::class.java,
                BackdropExpandedWidgetContract.ACTION_MUSIC_PLAY_PAUSE
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_music_next,
            broadcastPendingIntent(
                context,
                4630 + appWidgetId,
                BackdropMusicWidgetProvider::class.java,
                BackdropExpandedWidgetContract.ACTION_MUSIC_NEXT
            )
        )
        return views
    }
}

class BackdropQuickInfoWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_quick_info)
        applySurfaceTheme(views, prefs)

        val dateLabel = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date())
        val battery = OpenMeteoWeatherRepository.readBatteryPercent(context)
        val weatherPart = when (val w = OpenMeteoWeatherRepository.state.value) {
            is WeatherTelemetryState.Ready -> "${w.temperatureC}° ${w.conditionLabel}"
            else -> prefs.weatherCity.ifBlank { "Backdrops" }
        }
        val nextAgenda = StudioWidgetSystemBridge.loadUpcomingCalendarEvents(context, limit = 1)
            .firstOrNull()?.title

        views.setTextViewText(R.id.widget_quick_date, dateLabel)
        views.setTextViewText(
            R.id.widget_quick_summary,
            listOfNotNull(weatherPart, "$battery% Battery", nextAgenda).joinToString(" • ")
        )
        views.setTextViewText(
            R.id.widget_quick_badge,
            if (prefs.uiStyle == UiStyleMode.FROSTED_GLASS_OS.name) "FROSTED" else "M3"
        )
        views.setOnClickPendingIntent(
            R.id.widget_container,
            launchAppPendingIntent(context, 4700 + appWidgetId)
        )
        return views
    }
}

class BackdropPhotoFrameWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_photo_frame)
        applySurfaceTheme(views, prefs)

        val frameUri = prefs.parsedStudioWidgets
            .firstOrNull { it.type == StudioWidgetType.PHOTO_FRAME }
            ?.customPhotoUri
            .orEmpty()

        val wp = WallpaperCatalog.getById(prefs.activeHomeWallpaperId)
            ?: WallpaperCatalog.getById(prefs.activeLockWallpaperId)
            ?: WallpaperCatalog.allWallpapers.firstOrNull()

        val customBmp = if (frameUri.isNotBlank()) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(frameUri))?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        } else null

        if (customBmp != null) {
            views.setImageViewBitmap(R.id.widget_photo_frame_img, customBmp)
            views.setTextViewText(R.id.widget_photo_frame_caption, "Studio Photo Frame")
        } else if (wp != null) {
            val bmp = BaseBackdropWidgetProvider.resolveWidgetArtworkBitmap(context, wp, 360, 360)
            views.setImageViewBitmap(R.id.widget_photo_frame_img, bmp)
            views.setTextViewText(R.id.widget_photo_frame_caption, wp.title)
        }

        views.setOnClickPendingIntent(
            R.id.widget_container,
            launchAppPendingIntent(context, 4800 + appWidgetId, wp?.id.orEmpty())
        )
        return views
    }
}

class BackdropSystemInfoWidgetProvider : BaseExpandedHomeWidgetProvider() {
    override fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val prefs = GuestLocalUserStore.getInstance(context).getCurrentPreferencesSync()
        val views = RemoteViews(context.packageName, R.layout.widget_expanded_system_info)
        applySurfaceTheme(views, prefs)

        val tier = ZeroLagPerformanceEngine.resolveEffectiveLiquidGlassTier(context, prefs.parsedLiquidGlassTier)
        val diag = StudioWidgetSystemBridge.readDiagnostics(context, tier.label)
        views.setTextViewText(R.id.widget_sys_tier, diag.tierLabel.uppercase())
        views.setTextViewText(R.id.widget_sys_ram, "RAM ${diag.ramUsedPercent}% (${diag.ramSummary})")
        views.setTextViewText(R.id.widget_sys_storage, "Storage ${diag.storageFreeGb} GB free")
        views.setOnClickPendingIntent(
            R.id.widget_container,
            launchAppPendingIntent(context, 4900 + appWidgetId)
        )
        return views
    }
}
