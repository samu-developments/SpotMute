package com.developments.samu.muteforspotify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.developments.samu.muteforspotify.service.LoggerService

private val LOG_TAG: String = MuteWidget::class.java.simpleName

class MuteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateAppWidget(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ON_UPDATE_WIDGET -> updateMuteWidget(context, intent)  // called from loggerService to update widget ui
            ON_CLICK_BTN -> updateMuteWidget(context, intent, toggleService = true)
            else -> super.onReceive(context, intent)
        }
    }

    private fun updateMuteWidget(context: Context?, intent: Intent?, toggleService: Boolean = false) {
        if (context == null || intent == null) return

        val widgetManager = AppWidgetManager.getInstance(context)
        val remoteView = RemoteViews(context.packageName, R.layout.widget_mute).apply {
            setImageViewResource(R.id.btn_img_widget, getImageResource(muteIcon = LoggerService.isServiceRunning()))
        }

        // Only this method worked for updating images.. Why?
        widgetManager.updateAppWidget(ComponentName(context, MuteWidget::class.java), remoteView)

        // If called from widget button click.
        if (toggleService) toggleMuteService(context)
    }

    private fun toggleMuteService(context: Context) {

        val loggerServiceIntentForeground =
            Intent(LoggerService.ACTION_START_FOREGROUND, Uri.EMPTY, context, LoggerService::class.java)

        if (LoggerService.isServiceRunning()) context.stopService(loggerServiceIntentForeground)
        else ContextCompat.startForegroundService(context, loggerServiceIntentForeground)  // Sdk 26 >= can't start service unconditionally
    }

    companion object {

        const val ON_UPDATE_WIDGET = "update_widget_mute"
        const val ON_CLICK_BTN = "on_btn_click"

        fun updateAppWidget(context: Context, widgetManager: AppWidgetManager, widgetId: Int) {

            val intent = Intent(context, MuteWidget::class.java).apply {
                action = ON_CLICK_BTN
                // not used for now. no need to update individual widgets.
                // widgetManager.updateAppWidget(widgetId, views) seems to not work for updating f.ex images
                //putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, IntArray(widgetId))
            }
            val pendingIntent = PendingIntent.getBroadcast(context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT)

            val views = RemoteViews(context.packageName, R.layout.widget_mute).apply {
                setImageViewResource(R.id.btn_img_widget, getImageResource(muteIcon = LoggerService.isServiceRunning()))
            }

            views.setOnClickPendingIntent(R.id.btn_img_widget, pendingIntent)

            widgetManager.updateAppWidget(widgetId, views)
        }

        fun getImageResource(muteIcon: Boolean) =
            if (muteIcon) R.drawable.ic_tile_volume_off_widget
            else R.drawable.ic_tile_volume_on_widget
    }
}