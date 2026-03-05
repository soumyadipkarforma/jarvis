package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * Application class for Jarvis Assistant.
 * Handles global initialization such as notification channels.
 */
class JarvisApplication : Application() {

    companion object {
        const val TAG = "JarvisAssistant"
        const val NOTIFICATION_CHANNEL_ID = "jarvis_foreground"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Jarvis Application initialized")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Jarvis Assistant background service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
