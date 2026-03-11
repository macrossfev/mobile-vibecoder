package com.vibecoder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class VibeCoderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_MONITOR,
                    "Server Monitor",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Server status notifications"
                },
                NotificationChannel(
                    CHANNEL_ALERT,
                    "Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical alerts and warnings"
                }
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(channels)
        }
    }

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERT = "alert"
    }
}