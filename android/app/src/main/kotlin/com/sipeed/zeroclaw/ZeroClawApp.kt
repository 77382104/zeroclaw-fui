package com.sipeed.zeroclaw

import android.app.NotificationChannel
import android.app.NotificationManager
import io.flutter.app.FlutterApplication

class ZeroClawApp : FlutterApplication() {
    companion object {
        const val CHANNEL_ID = "zeroclaw_service"
        const val CHANNEL_NAME = "ZeroClaw Service"
    }

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark("Application.onCreate")
        createNotificationChannel()
        StartupTrace.mark("Notification channel created")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ZeroClaw background service"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
